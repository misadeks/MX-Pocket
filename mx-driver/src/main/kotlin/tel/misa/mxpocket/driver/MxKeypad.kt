package tel.misa.mxpocket.driver

import android.graphics.Bitmap
import android.hardware.usb.UsbDevice
import android.hardware.usb.UsbManager
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.util.concurrent.atomic.AtomicReference
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext

/** No Activity, UI, automation or media dependency. Call suspend operations from a caller-owned scope. */
class MxKeypad private constructor(
    private val session: HidSession,
    private val log: (String) -> Unit,
) : KeypadSink {
    override suspend fun setKeyImage(index: Int, bitmap: Bitmap) {
        val region = KeypadProtocol.keyRegion(index)
        val jpeg = withContext(Dispatchers.Default) {
            val scaled = Bitmap.createScaledBitmap(bitmap, region.width, region.height, true)
            try {
                var encoded: ByteArray? = null
                for (quality in listOf(90, 75, 60, 40)) {
                    val stream = ByteArrayOutputStream()
                    if (!scaled.compress(Bitmap.CompressFormat.JPEG, quality, stream)) {
                        throw IOException("Android JPEG encoding failed")
                    }
                    val bytes = stream.toByteArray()
                    if (bytes.size <= KeypadProtocol.MAX_JPEG_BYTES) {
                        encoded = bytes
                        break
                    }
                }
                encoded ?: throw IOException("JPEG exceeds supported image limit after quality reduction")
            } finally {
                if (scaled !== bitmap) scaled.recycle()
            }
        }
        setKeyJpeg(index, jpeg)
    }

    suspend fun setKeyJpeg(index: Int, jpeg: ByteArray) {
        session.upload(KeypadProtocol.keyRegion(index), jpeg)
        log("LCD ${index + 1}: ${jpeg.size} JPEG bytes acknowledged (visibility not verified)")
    }

    override fun close() = session.close()

    companion object {
        suspend fun connect(
            manager: UsbManager,
            device: UsbDevice,
            onEvent: (KeyEvent) -> Unit,
            onLog: (String) -> Unit,
            onError: (Throwable) -> Unit,
        ): MxKeypad {
            val usb = UsbReportTransport.open(manager, device, onLog)
            val decoder = AtomicReference<KeyInput?>()
            val events = ButtonEvents(onEvent)
            val session = HidSession(usb, usb.inputSizes, events::close) { report ->
                decoder.get()?.accept(report)?.forEach(events::deliver)
            }
            usb.onReport = { report ->
                onLog("RX ${report.take(32).hex()}")
                session.receive(report)
            }
            usb.onFailure = { error ->
                session.close(error) { onError(error) }
            }
            try {
                withContext(Dispatchers.IO) { usb.start() }
                delay(100)
                val root = session.command(0, 0, 0x1b, 0x04)
                if (root.size < 7 || root.u8(4) == 0) throw IOException("Reprogrammable controls feature unavailable")
                val feature = root.u8(4)
                onLog("Discovered HID++ 0x1b04 index=0x${feature.toString(16)}")
                decoder.set(KeyInput(feature))
                for (id in listOf(0x01a1, 0x01a2)) {
                    session.command(feature, 3, id shr 8, id and 255, 3)
                    onLog("Divert page control 0x${id.toString(16)}: acknowledged")
                }
                onLog("Using source-supported LCD VLP index=2, 480x480 panel / nine 118x118 regions")
                return MxKeypad(session, onLog)
            } catch (e: Exception) {
                session.close()
                if (e is CancellationException) throw e
                throw IOException("Keypad initialization failed: ${e.message}", e)
            }
        }
    }
}
