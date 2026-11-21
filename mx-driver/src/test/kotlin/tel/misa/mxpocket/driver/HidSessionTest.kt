package tel.misa.mxpocket.driver

import java.io.IOException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.async
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.withTimeout
import org.junit.Assert.*
import org.junit.Test

@OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
class HidSessionTest {
    private fun report(vararg bytes: Int): ByteArray =
        bytes.map(Int::toByte).toByteArray().copyOf(if (bytes[0] == 0x11) 20 else 32)

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
        session.receive(report(0x11, -1, 0x0b, 0, 1, 0xa1))
        assertEquals(1, input.size)
        assertFalse(first.isCompleted)
        session.receive(report(0x13, -1, 0x0b, 0x3b, 1, 0xa1))
        runCurrent()
        assertEquals(2, transport.sent.size)
        session.receive(report(0x11, -1, 0x0b, 0x3b, 1, 0xa2))
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
            session.receive(report(0x13, -1, 2, 0x2b, 0xc1, if (count == 2) 0xfb else 0))
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
        transport.respond = { session.receive(report(0x11, -1, -1, 0x0b, 0x3b, 7)) }
        var failure: Throwable? = null
        try { session.command(0x0b, 3) } catch (e: IOException) { failure = e }
        assertTrue(failure?.message?.contains("0x7") == true)
    }

    @Test fun truncatedAckAndErrorDoNotResolvePendingCommand() = runTest {
        val transport = FakeTransport()
        val session = HidSession(transport) { fail("Malformed input was forwarded") }
        val command = async { session.command(0x0b, 3) }
        runCurrent()
        val answer = report(0x13, -1, 0x0b, 0x3b)
        session.receive(answer.copyOf(31))
        session.receive(report(0x11, -1, -1, 0x0b, 0x3b, 7).copyOf(19))
        runCurrent()
        assertFalse(command.isCompleted)
        session.receive(answer)
        assertArrayEquals(answer, command.await())
    }

    @Test fun reportLengthAndIdMustBelongToCurrentInputRoute() = runTest {
        val sizes = mapOf(0x11 to 20, 0x13 to 32)
        val session = HidSession(FakeTransport(), sizes, {}) { fail("Undeclared input was forwarded") }
        val command = async { session.command(0x0b, 3) }
        runCurrent()
        val answer = report(0x11, -1, 0x0b, 0x3b)
        session.receive(answer.copyOf(32))
        session.receive(answer.copyOf(4095).apply { this[0] = 0x14 })
        runCurrent()
        assertFalse(command.isCompleted)
        session.receive(answer)
        assertArrayEquals(answer, command.await())
    }

    @Test fun closeFromInputCallbackReleasesOnlyDeliveredKeysAndDropsQueuedReports() {
        val transport = FakeTransport()
        val decoder = KeyInput(0x0b)
        val seen = mutableListOf<KeyEvent>()
        var decoded = 0
        lateinit var session: HidSession
        val buttons = ButtonEvents { event ->
            seen += event
            if (event.pressed) session.close()
        }
        session = HidSession(transport, KeypadProtocol.reportSizes, buttons::close) {
            decoded++
            decoder.accept(it).forEach(buttons::deliver)
        }
        session.receive(report(0x13, -1, 2, 0, 0, 1, 1, 2))
        session.receive(report(0x11, -1, 0x0b, 0, 1, 0xa1))
        session.close(IOException("Late read failure")) { fail("Late failure was forwarded") }
        assertEquals(listOf(KeyEvent(1, true), KeyEvent(1, false, true)), seen)
        assertEquals(1, decoded)
        assertTrue(transport.closed)
    }

    @Test fun closeAfterReaderAckBeforeCallerResumesCannotReportSuccess() = runTest {
        val session = HidSession(FakeTransport()) {}
        val result = async { runCatching { session.command(0, 0) } }
        runCurrent()
        session.receive(report(0x11, -1, 0, 0x0b, 8))
        session.close()
        assertTrue(result.await().exceptionOrNull() is IOException)
    }

    @Test fun callerTimeoutRemainsCancellationAndClosesPendingExchange() = runTest {
        val transport = FakeTransport()
        val session = HidSession(transport) {}
        val error = runCatching { withTimeout(100) { session.command(0, 0) } }.exceptionOrNull()
        assertTrue(error is TimeoutCancellationException)
        assertTrue(transport.closed)
    }
}
