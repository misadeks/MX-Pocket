package tel.misa.mxpocket.driver

import android.hardware.usb.UsbConstants
import android.hardware.usb.UsbDevice
import android.hardware.usb.UsbDeviceConnection
import android.hardware.usb.UsbEndpoint
import android.hardware.usb.UsbInterface
import android.hardware.usb.UsbManager
import android.hardware.usb.UsbRequest
import java.io.IOException
import java.nio.ByteBuffer
import java.util.concurrent.TimeoutException
import java.util.concurrent.atomic.AtomicBoolean
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull

internal class UsbReportTransport private constructor(
    private val connection: UsbDeviceConnection,
    private val interfaces: List<UsbInterface>,
    private val routes: Map<Int, Route>,
    private val inputs: Map<UsbEndpoint, Map<Int, Int>>,
    val inputSizes: Map<Int, Int>,
    private val log: (String) -> Unit,
) : ReportTransport {
    private data class Route(val iface: UsbInterface, val endpoint: UsbEndpoint?, val size: Int)
    private data class Transfer(
        val buffer: ByteBuffer,
        val endpoint: UsbEndpoint,
        val completion: CompletableDeferred<Unit>? = null,
        val expected: Int = 0,
    )
    private val guard = Any()
    private val controlGuard = Any()
    private val transfers = mutableMapOf<UsbRequest, Transfer>()
    private val closed = AtomicBoolean(false)
    private var started = false
    var onReport: (ByteArray) -> Unit = {}
    var onFailure: (Throwable) -> Unit = {}

    fun start() {
        synchronized(guard) {
            check(!closed.get() && !started)
            started = true
            try {
                inputs.forEach { (endpoint, sizes) ->
                    val request = UsbRequest()
                    if (!request.initialize(connection, endpoint)) {
                        request.close()
                        throw IOException("Cannot initialize interrupt IN 0x${endpoint.address.toString(16)}")
                    }
                    val transfer = Transfer(ByteBuffer.allocateDirect(sizes.values.max()), endpoint)
                    transfers[request] = transfer
                    if (!request.queue(transfer.buffer)) throw IOException("Cannot queue interrupt IN")
                }
            } catch (e: Exception) {
                close()
                throw e
            }
        }
        Thread({ dispatch() }, "MX-USB-completions").start()
    }

    override suspend fun send(report: ByteArray) = withContext(Dispatchers.IO) {
        require(report.isNotEmpty())
        val route = routes[report.u8(0)] ?: throw IOException("No descriptor-backed route for report")
        require(report.size == route.size) {
            "Report 0x${report.u8(0).toString(16)}: expected ${route.size} bytes, got ${report.size}"
        }
        val endpoint = route.endpoint
        if (endpoint == null) {
            val written = synchronized(controlGuard) {
                if (closed.get()) throw IOException("USB connection closed")
                connection.controlTransfer(0x21, 0x09, 0x0200 or report.u8(0), route.iface.id,
                    report, report.size, 1500)
            }
            if (written != report.size) throw IOException("HID SET_REPORT wrote $written/${report.size}")
            return@withContext
        }
        val completion = CompletableDeferred<Unit>()
        synchronized(guard) {
            if (closed.get() || !started) throw IOException("USB transport is not active")
            val request = UsbRequest()
            if (!request.initialize(connection, endpoint)) {
                request.close()
                throw IOException("Cannot initialize interrupt OUT")
            }
            val buffer = ByteBuffer.allocateDirect(report.size).apply { put(report); flip() }
            transfers[request] = Transfer(buffer, endpoint, completion, report.size)
            if (!request.queue(buffer)) {
                transfers.remove(request)
                request.close()
                throw IOException("Cannot queue interrupt OUT")
            }
        }
        if (withTimeoutOrNull(2000) { completion.await() } == null) {
            close()
            throw IOException("Interrupt OUT completion timed out")
        }
    }

    private fun dispatch() {
        try {
            while (!closed.get()) {
                val completed = try {
                    connection.requestWait(250)
                } catch (_: TimeoutException) {
                    continue // Requests remain queued; do not requeue or close them on a poll timeout.
                }
                if (completed == null) {
                    if (!closed.get()) throw IOException("USB requestWait failed (detach or endpoint error)")
                    break
                }
                var report: ByteArray? = null
                synchronized(guard) {
                    if (closed.get()) return
                    val transfer = transfers[completed] ?: throw IOException("Unknown USB completion")
                    val actual = transfer.buffer.position()
                    if (transfer.completion != null) {
                        transfers.remove(completed)
                        completed.close()
                        if (actual != transfer.expected) {
                            transfer.completion.completeExceptionally(
                                IOException("Short interrupt OUT $actual/${transfer.expected}"))
                        } else transfer.completion.complete(Unit)
                    } else {
                        if (actual <= 0) throw IOException("Empty interrupt IN completion")
                        transfer.buffer.flip()
                        val bytes = ByteArray(actual).also { transfer.buffer.get(it) }
                        if (bytes.isComplete(inputs.getValue(transfer.endpoint))) report = bytes
                        transfer.buffer.clear()
                        if (!completed.queue(transfer.buffer)) throw IOException("Cannot requeue interrupt IN")
                    }
                }
                report?.let(onReport)
            }
        } catch (e: Exception) {
            if (!closed.get()) {
                // Let the session invalidate the reader before failing any pending OUT completion.
                try { onFailure(e) } finally { close() }
            }
        }
    }

    override fun close() {
        if (!closed.compareAndSet(false, true)) return
        // close can be called from an Activity lifecycle callback; kernel operations must not block it.
        Thread({ dispose() }, "MX-USB-close").start()
    }

    private fun dispose() {
        val outstanding = synchronized(guard) { transfers.toMap().also { transfers.clear() } }
        outstanding.values.forEach { transfer ->
            transfer.completion?.completeExceptionally(IOException("USB connection closed"))
        }
        synchronized(controlGuard) {
            try {
                outstanding.keys.forEach { it.cancel() }
                interfaces.forEach { iface ->
                    log("releaseInterface ${iface.id}: ${connection.releaseInterface(iface)}")
                }
            } finally {
                try { connection.close() } finally { outstanding.keys.forEach { it.close() } }
            }
        }
        log("USB connection closed; queued requests cancelled")
    }

    companion object {
        suspend fun open(manager: UsbManager, device: UsbDevice, log: (String) -> Unit): UsbReportTransport {
            var opened: UsbReportTransport? = null
            try {
                return withContext(Dispatchers.IO) {
                require(UsbDiagnostics.isKeypad(device)) { "Only MX Creative Keypad is supported" }
                if (!manager.hasPermission(device)) throw SecurityException("Android USB permission is required")
                val connection = manager.openDevice(device)
                    ?: throw IOException("openDevice failed; check permission and USB connection")
                val claimed = mutableListOf<UsbInterface>()
                try {
                    val raw = connection.rawDescriptors ?: throw IOException("USB descriptors unavailable")
                    if (raw.size >= 14) log("bcdDevice=0x%02X%02X".format(raw.u8(13), raw.u8(12)))
                    val routes = mutableMapOf<Int, Route>()
                    val inputReports = mutableMapOf<Int, Int>()
                    val inputs = mutableMapOf<UsbEndpoint, Map<Int, Int>>()
                    for (index in 0 until device.interfaceCount) {
                        val iface = device.getInterface(index)
                        if (iface.interfaceClass != UsbConstants.USB_CLASS_HID) continue
                        if (iface.alternateSetting != 0) {
                            log("Skipping inactive alternate ${iface.alternateSetting} of interface ${iface.id}")
                            continue
                        }
                        var success = connection.claimInterface(iface, false)
                        log("claimInterface ${iface.id} force=false: $success")
                        if (!success) {
                            success = connection.claimInterface(iface, true)
                            log("claimInterface ${iface.id} force=true (kernel HID takeover): $success")
                        }
                        if (!success) throw IOException("Cannot claim HID interface ${iface.id}; kernel/OEM conflict")
                        claimed += iface
                        val length = HidDescriptor.reportDescriptorLength(raw, iface.id, iface.alternateSetting)
                            ?: throw IOException("No HID report descriptor length on interface ${iface.id}")
                        if (length !in 1..16384) throw IOException("Unsupported report descriptor length $length")
                        val descriptor = ByteArray(length)
                        val read = connection.controlTransfer(0x81, 0x06, 0x2200, iface.id, descriptor, length, 1500)
                        log("GET_DESCRIPTOR report iface=${iface.id}: $read/$length bytes")
                        if (read != length) throw IOException("Cannot read HID report descriptor on ${iface.id}")
                        val layout = HidDescriptor.parse(descriptor)
                        log("HID ${iface.id} pages=${layout.usagePages.map { "0x${it.toString(16)}" }} " +
                            "input=${layout.input} output=${layout.output} feature=${layout.feature}")
                        val vendor = layout.output.keys.any { it in setOf(0x11, 0x14) } ||
                            layout.input.keys.any { it in setOf(0x11, 0x13) }
                        if (!vendor) {
                            if (!connection.releaseInterface(iface)) throw IOException("Cannot release unrelated HID")
                            claimed.remove(iface)
                            log("Released non-protocol HID interface ${iface.id}")
                            continue
                        }
                        val endpoints = (0 until iface.endpointCount).map(iface::getEndpoint)
                        val interruptIn = endpoints.filter {
                            it.type == UsbConstants.USB_ENDPOINT_XFER_INT && it.direction == UsbConstants.USB_DIR_IN
                        }
                        val interruptOut = endpoints.filter {
                            it.type == UsbConstants.USB_ENDPOINT_XFER_INT && it.direction == UsbConstants.USB_DIR_OUT
                        }
                        if (interruptIn.size > 1 || interruptOut.size > 1) {
                            throw IOException("Ambiguous HID endpoints on ${iface.id}")
                        }
                        layout.output.filterKeys { it in setOf(0x11, 0x14) }.forEach { (id, size) ->
                            if (routes.containsKey(id)) throw IOException("Ambiguous report route 0x${id.toString(16)}")
                            routes[id] = Route(iface, interruptOut.singleOrNull(), size)
                            log("Report 0x${id.toString(16)} -> interface ${iface.id}, " +
                                (interruptOut.singleOrNull()?.let { "interrupt OUT 0x${it.address.toString(16)}" }
                                    ?: "control HID SET_REPORT"))
                        }
                        if (layout.input.isNotEmpty()) {
                            layout.input.filterKeys { it in KeypadProtocol.reportSizes }.forEach { (id, size) ->
                                if (inputReports.put(id, size) != null) {
                                    throw IOException("Ambiguous HID input 0x${id.toString(16)}")
                                }
                            }
                            val endpoint = interruptIn.singleOrNull()
                                ?: throw IOException("No interrupt IN for HID input on ${iface.id}")
                            inputs[endpoint] = layout.input
                            log("Input -> interrupt 0x${endpoint.address.toString(16)}, maxPacket=${endpoint.maxPacketSize}")
                        }
                    }
                    if (routes[0x11]?.size != 20 || routes[0x14]?.size != KeypadProtocol.REPORT_BYTES ||
                        inputReports[0x11] != 20 || inputReports[0x13] != 32 || inputs.isEmpty()) {
                        throw IOException("Unsupported descriptor revision: need output 11/20, 14/4095, input 11/20 and 13/32")
                    }
                    UsbReportTransport(connection, claimed.toList(), routes, inputs, inputReports, log)
                        .also { opened = it }
                } catch (e: Exception) {
                    claimed.forEach { log("Cleanup release ${it.id}: ${connection.releaseInterface(it)}") }
                    connection.close()
                    throw e
                }
            }
            } catch (e: Exception) {
                // withContext may discard a completed open result when the caller was cancelled.
                opened?.close()
                throw e
            }
        }
    }
}
