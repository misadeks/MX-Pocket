package tel.misa.mxpocket.driver

import java.io.Closeable
import java.io.IOException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.delay
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withTimeoutOrNull

interface ReportTransport : Closeable {
    suspend fun send(report: ByteArray)
}

/** One in-flight HID++ exchange; USB completion alone does not establish protocol acceptance. */
class HidSession internal constructor(
    private val transport: ReportTransport,
    private val inputSizes: Map<Int, Int>,
    private val onClosed: () -> Unit,
    private val input: (ByteArray) -> Unit,
) : Closeable {
    constructor(transport: ReportTransport, input: (ByteArray) -> Unit) :
        this(transport, KeypadProtocol.reportSizes, {}, input)
    private data class Pending(val request: ByteArray, val answer: CompletableDeferred<ByteArray>)
    private val serial = Mutex()
    private val guard = Any()
    private var pending: Pending? = null
    private var closed = false

    fun receive(report: ByteArray) {
        if (!report.isComplete(inputSizes) || report.size < 4) return
        synchronized(guard) {
            if (closed) return
            val p = pending
            if (p != null && report.u8(1) == p.request.u8(1)) {
                if (report.u8(2) == 0xff && report.size >= 6 &&
                    report.u8(3) == p.request.u8(2) && report.u8(4) == p.request.u8(3)) {
                    p.answer.completeExceptionally(IOException("HID++ error 0x${report.u8(5).toString(16)}"))
                    return
                }
                if (report.u8(2) == p.request.u8(2) && report.u8(3) == p.request.u8(3)) {
                    p.answer.complete(report.copyOf())
                    return
                }
            }
            // Serialize delivery (not only decoding) with close and synthetic releases.
            input(report)
        }
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
                if (KeypadProtocol.imageStatus(response, inputSizes) == 0xfb) {
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
            if (closed) throw IOException("HID session is closed")
            pending = p
        }
        try {
            transport.send(request)
            val answer = withTimeoutOrNull(1500) { p.answer.await() }
                ?: throw IOException("HID++ acknowledgement timed out for ${request.take(5).hex()}")
            synchronized(guard) {
                if (closed) throw IOException("HID session disconnected")
            }
            return answer
        } catch (e: Exception) {
            close()
            throw e
        } finally {
            synchronized(guard) { if (pending === p) pending = null }
        }
    }

    override fun close() = close(IOException("HID session disconnected")) {}

    internal fun close(cause: Throwable, notify: () -> Unit) {
        synchronized(guard) {
            if (closed) return
            closed = true
            pending?.answer?.completeExceptionally(cause)
            pending = null
            try {
                transport.close()
            } finally {
                try { onClosed() } finally { notify() }
            }
        }
    }
}

internal fun Iterable<Byte>.hex(): String = joinToString(" ") { "%02x".format(it.toInt() and 255) }
