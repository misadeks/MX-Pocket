package tel.misa.mxpocket.driver

import java.io.Closeable
import java.io.IOException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.delay
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withTimeout

interface ReportTransport : Closeable {
    suspend fun send(report: ByteArray)
}

/** One in-flight HID++ exchange; USB completion alone does not establish protocol acceptance. */
class HidSession(private val transport: ReportTransport, private val input: (ByteArray) -> Unit) : Closeable {
    private data class Pending(val request: ByteArray, val answer: CompletableDeferred<ByteArray>)
    private val serial = Mutex()
    private val guard = Any()
    private var pending: Pending? = null
    private var closed = false

    fun receive(report: ByteArray) {
        if (report.size < 4 || report.u8(0) !in setOf(0x11, 0x13, 0x14)) return
        val consumed = synchronized(guard) {
            val p = pending
            if (p == null || report.u8(1) != p.request.u8(1)) false
            else if (report.u8(2) == 0xff && report.size >= 6 &&
                report.u8(3) == p.request.u8(2) && report.u8(4) == p.request.u8(3)) {
                p.answer.completeExceptionally(IOException("HID++ error 0x${report.u8(5).toString(16)}"))
                true
            } else if (report.u8(2) == p.request.u8(2) && report.u8(3) == p.request.u8(3)) {
                p.answer.complete(report.copyOf())
                true
            } else false
        }
        if (!consumed) input(report)
    }

    suspend fun command(feature: Int, function: Int, vararg params: Int): ByteArray =
        serial.withLock { exchange(KeypadProtocol.command(feature, function, *params)) }

    suspend fun upload(region: KeypadProtocol.Region, jpeg: ByteArray) = serial.withLock {
        val packets = KeypadProtocol.imageReports(region, jpeg)
        val deadline = System.nanoTime() + 1_000_000_000L
        while (true) {
            var busy = false
            for (packet in packets) {
                val response = exchange(packet)
                if (KeypadProtocol.imageStatus(response) == 0xfb) {
                    busy = true
                    break
                }
            }
            if (!busy) break
            if (System.nanoTime() >= deadline) throw IOException("Keypad remained busy accepting image")
            delay(10)
        }
    }

    private suspend fun exchange(request: ByteArray): ByteArray {
        val p = Pending(request, CompletableDeferred())
        synchronized(guard) {
            check(!closed) { "HID session is closed" }
            pending = p
        }
        try {
            transport.send(request)
            return withTimeout(1500) { p.answer.await() }
        } catch (e: TimeoutCancellationException) {
            // A late response could satisfy a subsequent identical command. Fail closed.
            close()
            throw IOException("HID++ acknowledgement timed out for ${request.take(5).hex()}", e)
        } catch (e: CancellationException) {
            close()
            throw e
        } catch (e: IOException) {
            close()
            throw e
        } finally {
            synchronized(guard) { if (pending === p) pending = null }
        }
    }

    override fun close() {
        synchronized(guard) {
            if (closed) return
            closed = true
            pending?.answer?.completeExceptionally(IOException("HID session disconnected"))
            pending = null
        }
        transport.close()
    }
}

internal fun Iterable<Byte>.hex(): String = joinToString(" ") { "%02x".format(it.toInt() and 255) }
