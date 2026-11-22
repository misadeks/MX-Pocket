package tel.misa.mxpocket.preview.usb

import com.sun.jna.Memory
import com.sun.jna.Native
import com.sun.jna.Pointer
import com.sun.jna.platform.win32.Kernel32
import com.sun.jna.platform.win32.WinBase.OVERLAPPED
import com.sun.jna.platform.win32.WinNT.HANDLE
import com.sun.jna.ptr.IntByReference
import com.sun.jna.win32.StdCallLibrary
import java.io.IOException
import kotlinx.coroutines.CompletableDeferred

/** com.sun.jna.platform.win32.Kernel32's byte[] overloads cannot outlive the initial
 * native call. Keep native storage for both the buffer and OVERLAPPED until completion. */
internal interface OverlappedApi : StdCallLibrary {
    fun ReadFile(file: HANDLE, buffer: Pointer, size: Int, unused: Pointer?, ov: Pointer): Boolean
    fun WriteFile(file: HANDLE, buffer: Pointer, size: Int, unused: Pointer?, ov: Pointer): Boolean
    fun GetOverlappedResult(file: HANDLE, ov: Pointer, count: IntByReference, wait: Boolean): Boolean
    fun CancelIoEx(file: HANDLE, ov: Pointer?): Boolean
}

internal class OverlappedHidPort(
    override val collection: HidCollection,
    private val handle: HANDLE,
) : HidPort {
    private val api = overlappedApi
    private val kernel = Kernel32.INSTANCE
    private val guard = Any()
    private var closing = false
    private var active = 0
    private val released = CompletableDeferred<Unit>()

    override fun read(): ByteArray = transfer(null)
    override fun write(bytes: ByteArray) { transfer(bytes) }

    private fun transfer(bytes: ByteArray?): ByteArray {
        val size = bytes?.size ?: collection.inputBytes
        val event = kernel.CreateEvent(null, true, false, null) ?: throw winError("Create HID I/O event")
        try {
            Memory(size.toLong()).use { buffer ->
                if (bytes != null) buffer.write(0, bytes, 0, size)
                val ov = OVERLAPPED().apply { hEvent = event; write() }
                synchronized(guard) {
                    if (closing) throw IOException("Keypad collection closed")
                    val ok = if (bytes == null) api.ReadFile(handle, buffer, size, null, ov.pointer)
                             else api.WriteFile(handle, buffer, size, null, ov.pointer)
                    if (!ok && Native.getLastError() != 997) throw winError("Submit HID I/O") // ERROR_IO_PENDING
                    active++
                }
                try {
                    // Continuous pending interrupt read. Disconnect cancels it; writes have a deadline.
                    val wait = kernel.WaitForSingleObject(event, if (bytes == null) -1 else 2000)
                    if (wait != 0) api.CancelIoEx(handle, ov.pointer)
                    val count = IntByReference()
                    val ok = api.GetOverlappedResult(handle, ov.pointer, count, true) // drain even after cancellation
                    if (wait == 258) throw IOException("HID output transfer timed out")
                    if (wait != 0 || !ok) throw winError("Complete HID I/O")
                    if (count.value != size) throw IOException("Short HID transfer: ${count.value}/$size")
                    return if (bytes == null) buffer.getByteArray(0, size) else bytes
                } finally {
                    synchronized(guard) {
                        active--
                        if (closing && active == 0) release()
                    }
                }
            }
        } finally {
            kernel.CloseHandle(event)
        }
    }

    override fun close() = synchronized(guard) {
        if (!closing) {
            closing = true
            if (active == 0) release() else api.CancelIoEx(handle, null)
        }
        Unit
    }

    private fun release() {
        if (kernel.CloseHandle(handle)) released.complete(Unit)
        else released.completeExceptionally(winError("Close HID collection"))
    }

    override suspend fun awaitClosed() = released.await()

    companion object {
        private val overlappedApi = Native.load("kernel32", OverlappedApi::class.java)
    }
}
