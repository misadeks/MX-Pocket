package tel.misa.mxpocket.driver

import java.io.IOException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.async
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.runCurrent
import org.junit.Assert.*
import org.junit.Test

@OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
class HidSessionTest {
    private class FakeTransport : ReportTransport {
        val sent = mutableListOf<ByteArray>()
        var closed = false
        var respond: (ByteArray) -> Unit = {}
        override suspend fun send(report: ByteArray) { sent += report; respond(report) }
        override fun close() { closed = true }
    }

    @Test fun serializesCommandsAndSeparatesInputFromAnswers() = runTest {
        val transport = FakeTransport()
        val input = mutableListOf<ByteArray>()
        val session = HidSession(transport) { input += it }
        val first = async { session.command(0x0b, 3, 1, 0xa1, 3) }
        val second = async { session.command(0x0b, 3, 1, 0xa2, 3) }
        runCurrent()
        assertEquals(1, transport.sent.size)
        session.receive(byteArrayOf(0x11, -1, 0x0b, 0, 1, 0xa1.toByte()))
        assertEquals(1, input.size)
        assertFalse(first.isCompleted)
        session.receive(byteArrayOf(0x13, -1, 0x0b, 0x3b, 1, 0xa1.toByte()))
        runCurrent()
        assertEquals(2, transport.sent.size)
        session.receive(byteArrayOf(0x11, -1, 0x0b, 0x3b, 1, 0xa2.toByte()))
        first.await(); second.await()
        session.close()
        assertTrue(transport.closed)
    }

    @Test fun retriesWholeImageOnlyAfterExplicitBusyAndWaitsForEveryAck() = runTest {
        val transport = FakeTransport()
        val session = HidSession(transport) {}
        var count = 0
        transport.respond = {
            count++
            session.receive(byteArrayOf(0x13, -1, 2, 0x2b, 0xc1.toByte(),
                if (count == 2) 0xfb.toByte() else 0))
        }
        val jpeg = ByteArray(5000).apply {
            this[0] = -1; this[1] = 0xd8.toByte(); this[4998] = -1; this[4999] = 0xd9.toByte()
        }
        session.upload(KeypadProtocol.keyRegion(0), jpeg)
        assertEquals(listOf(0xa1, 0x62, 0xa1, 0x62), transport.sent.map { it.u8(4) })
    }

    @Test fun disconnectAndCancellationCloseTransportAndPendingExchange() = runTest {
        val transport = FakeTransport()
        val session = HidSession(transport) {}
        val failure = CompletableDeferred<Throwable>()
        launch {
            try { session.command(0, 0, 0x1b, 4) } catch (e: IOException) { failure.complete(e) }
        }
        runCurrent()
        session.close()
        runCurrent()
        assertTrue(failure.await() is IOException)
        assertTrue(transport.closed)
        val otherTransport = FakeTransport()
        val other = HidSession(otherTransport) {}
        val command = launch { other.command(0, 0) }
        runCurrent()
        command.cancelAndJoin()
        assertTrue(otherTransport.closed)
    }

    @Test fun timeoutFailsClosedInsteadOfAcceptingLateReplies() = runTest {
        val transport = FakeTransport()
        val session = HidSession(transport) {}
        var failure: Throwable? = null
        try { session.command(0, 0) } catch (e: IOException) { failure = e }
        assertNotNull(failure)
        assertTrue(transport.closed)
    }

    @Test fun propagatesHidErrorsWithoutCallingThemSuccess() = runTest {
        val transport = FakeTransport()
        val session = HidSession(transport) {}
        transport.respond = { session.receive(byteArrayOf(0x11, -1, -1, 0x0b, 0x3b, 7)) }
        var failure: Throwable? = null
        try { session.command(0x0b, 3) } catch (e: IOException) { failure = e }
        assertTrue(failure?.message?.contains("0x7") == true)
    }
}
