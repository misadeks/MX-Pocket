package tel.misa.mxpocket.preview.usb

import java.io.IOException
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference
import kotlinx.coroutines.*
import tel.misa.mxpocket.driver.*

/** Opt-in session; does nothing until discover/connect. Callbacks are ordered worker callbacks:
 * enqueue events without blocking (including synthetic releases) and marshal UI changes yourself. */
class WindowsKeypad private constructor(
    private val transport: WindowsTransport,
    private val hid: HidSession,
    private val onFailure: (Throwable) -> Unit,
) {
    class Device internal constructor(internal val parent: String, val label: String) {
        override fun toString() = label
    }

    private val ended = AtomicBoolean()

    suspend fun setKeyJpeg(index: Int, jpeg: ByteArray) = setRegionJpeg(KeypadProtocol.keyRegion(index), jpeg)

    /** Do not mutate jpeg before return. Waits for every ACK; never promises visible pixels.
     * Finish a submitted image before repainting. Cancellation of an in-flight exchange is fatal. */
    suspend fun setRegionJpeg(region: KeypadProtocol.Region, jpeg: ByteArray) = withContext(Dispatchers.IO) {
        try {
            hid.upload(region, jpeg)
        } catch (e: CancellationException) {
            if (transport.closed.get()) end(e)
            throw e
        } catch (e: IOException) {
            end(e)
            throw e
        }
    }

    private fun end(error: Throwable?) {
        if (!ended.compareAndSet(false, true)) return
        try {
            hid.close(error ?: IOException("Keypad disconnected")) {}
        } finally {
            if (error != null) onFailure(error)
        }
    }

    /** Idempotent; returns after native reads/writes have drained and all handles are released. */
    suspend fun disconnect() = withContext(NonCancellable + Dispatchers.IO) {
        try { end(null) } finally { transport.awaitClosed() }
    }

    companion object {
        private fun backend(): WindowsHid {
            requireWindowsRuntime(System.getProperty("os.name"), System.getProperty("os.arch"))
            return Win32Hid
        }

        suspend fun discover(): List<Device> = withContext(Dispatchers.IO) { nativeErrors { discover(backend()) } }

        internal fun discover(backend: WindowsHid) = backend.enumerate().map { it.usbParent }.distinct().sorted()
            .mapIndexed { index, parent -> Device(parent, "MX Creative Keypad ${index + 1}") }

        suspend fun connect(device: Device, onEvent: (KeyEvent) -> Unit, onFailure: (Throwable) -> Unit): WindowsKeypad =
            nativeErrors { connect(::backend, device, onEvent, onFailure) }

        internal suspend fun connect(
            backend: WindowsHid, device: Device, onEvent: (KeyEvent) -> Unit, onFailure: (Throwable) -> Unit,
        ) = connect({ backend }, device, onEvent, onFailure)

        private suspend fun connect(
            loadBackend: () -> WindowsHid, device: Device, onEvent: (KeyEvent) -> Unit, onFailure: (Throwable) -> Unit,
        ): WindowsKeypad {
            // withContext can discard its result on prompt cancellation. Keep ownership until
            // after the dispatcher handoff, including partial open and failed initialization.
            var keypad: WindowsKeypad? = null
            try {
                return withContext(Dispatchers.IO) {
                    val backend = loadBackend()
                    val matching = backend.enumerate().filter { it.usbParent == device.parent }
                    val selected = Route.entries.map { route ->
                        matching.singleOrNull { it.route == route }?.also { it.validate() }
                            ?: throw IOException("Missing or ambiguous ${route.name} HID collection; reconnect and rescan")
                    }
                    val ports = mutableListOf<HidPort>()
                    try {
                        selected.forEach { ports += backend.open(it) }
                        val transport = WindowsTransport(ports)
                        val buttons = ButtonEvents(onEvent)
                        val decoder = AtomicReference<KeyInput?>()
                        val hid = HidSession(transport, transport.inputSizes, buttons::close) { report ->
                            decoder.get()?.accept(report)?.forEach(buttons::deliver)
                        }
                        val result = WindowsKeypad(transport, hid, onFailure)
                        keypad = result
                        transport.start(hid::receive, result::end)
                        hid.initializeKeypad(decoder::set) {}
                        result
                    } finally {
                        if (keypad == null) ports.forEach { it.close() }
                    }
                }
            } catch (e: Throwable) {
                keypad?.disconnect()
                throw e
            }
        }

        private inline fun <T> nativeErrors(block: () -> T): T = try {
            block()
        } catch (e: LinkageError) {
            throw IOException("Windows HID native libraries unavailable; use the x64 Windows distribution and JDK", e)
        }
    }
}

private class WindowsTransport(private val ports: List<HidPort>) : ReportTransport {
    private val readers = CoroutineScope(Dispatchers.IO + SupervisorJob())
    val closed = AtomicBoolean()
    val inputSizes = ports.map { it.collection }.filter { it.inputBytes > 0 }.associate { it.route.id to it.inputWireBytes }

    fun start(receive: (ByteArray) -> Unit, fail: (Throwable) -> Unit) {
        for (port in ports.filter { it.collection.inputBytes > 0 }) readers.launch {
            try {
                while (!closed.get()) port.collection.decode(port.read())?.let(receive)
            } catch (e: Exception) {
                if (!closed.get()) fail(e)
            }
        }
    }

    override suspend fun send(report: ByteArray) {
        if (closed.get()) throw IOException("Keypad disconnected")
        val port = ports.single { it.collection.route.id == report.u8(0) }
        port.write(port.collection.encode(report))
    }

    override fun close() {
        if (closed.compareAndSet(false, true)) {
            ports.forEach { it.close() }
            readers.cancel()
        }
    }

    suspend fun awaitClosed() {
        readers.coroutineContext.job.join()
        ports.forEach { it.awaitClosed() }
    }
}
