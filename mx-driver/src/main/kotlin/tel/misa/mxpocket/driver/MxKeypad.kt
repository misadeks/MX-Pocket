package tel.misa.mxpocket.driver

import android.graphics.Bitmap
import android.hardware.usb.UsbDevice
import android.hardware.usb.UsbManager
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.util.concurrent.atomic.AtomicReference
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/** No Activity, UI, automation or media dependency. Call suspend operations from a caller-owned scope. */
class MxKeypad private constructor(
    private val session: HidSession,
    private val log: (String) -> Unit,
) : KeypadSink {
    override suspend fun setKeyImage(index: Int, bitmap: Bitmap) {
        setKeyJpeg(index, encodeKeyJpeg(index, bitmap))
    }

    /** Prepare a key-sized JPEG without uploading or retaining the caller's bitmap. */
    suspend fun encodeKeyJpeg(index: Int, bitmap: Bitmap): ByteArray =
        encodeKeyJpeg(index, bitmap, preferFewerReports = false)

    /** For photo tiles, keep the higher quality unless lowering it saves an acknowledged report. */
    suspend fun encodeKeyJpeg(index: Int, bitmap: Bitmap, preferFewerReports: Boolean): ByteArray {
        val region = KeypadProtocol.keyRegion(index)
        return withContext(Dispatchers.Default) {
            val start = System.nanoTime()
            val scaled = Bitmap.createScaledBitmap(bitmap, region.width, region.height, true)
            try {
                var encoded: ByteArray? = null
                val qualities = if (preferFewerReports) listOf(90, 50, 40) else listOf(90, 75, 60, 40)
                for (quality in qualities) {
                    val stream = ByteArrayOutputStream()
                    if (!scaled.compress(Bitmap.CompressFormat.JPEG, quality, stream)) {
                        throw IOException("Android JPEG encoding failed")
                    }
                    val bytes = stream.toByteArray()
                    if (bytes.size > KeypadProtocol.MAX_JPEG_BYTES) continue
                    val reports = KeypadProtocol.imageReportCount(bytes.size)
                    if (encoded == null || reports < KeypadProtocol.imageReportCount(encoded.size)) {
                        encoded = bytes
                    }
                    // Try 50 once for a multi-report photo. 40 is only a size-limit fallback.
                    if (!preferFewerReports || reports == 1 || quality <= 50) break
                }
                val jpeg = encoded ?: throw IOException("JPEG exceeds supported image limit after quality reduction")
                log("LCD ${index + 1}: JPEG prepared in ${(System.nanoTime() - start) / 1_000} us")
                jpeg
            } finally {
                if (scaled !== bitmap) scaled.recycle()
            }
        }
    }

    suspend fun setKeyJpeg(index: Int, jpeg: ByteArray) {
        uploadJpeg(KeypadProtocol.keyRegion(index), jpeg, "LCD ${index + 1}")
    }

    /** Preserve native rectangle geometry and text resolution. Null means split before writing. */
    suspend fun encodeRegionJpeg(region: KeypadProtocol.Region, bitmap: Bitmap): ByteArray? {
        require(region.width == bitmap.width && region.height == bitmap.height)
        require(region.width in 1..480 && region.height in 1..480 &&
            region.x in 0..(480 - region.width) && region.y in 0..(480 - region.height))
        return withContext(Dispatchers.Default) {
            for (quality in intArrayOf(86, 72, 60)) {
                val stream = ByteArrayOutputStream()
                if (!bitmap.compress(Bitmap.CompressFormat.JPEG, quality, stream)) throw IOException("Android JPEG encoding failed")
                if (stream.size() <= KeypadProtocol.MAX_JPEG_BYTES) return@withContext stream.toByteArray()
            }
            null
        }
    }

    suspend fun setRegionJpeg(region: KeypadProtocol.Region, jpeg: ByteArray) =
        uploadJpeg(region, jpeg, "LCD region")

    private suspend fun uploadJpeg(region: KeypadProtocol.Region, jpeg: ByteArray, label: String) {
        val start = System.nanoTime()
        session.upload(region, jpeg)
        log("$label: ${jpeg.size} JPEG bytes acknowledged in ${(System.nanoTime() - start) / 1_000} us " +
            "(upload including ACKs; visibility not verified)")
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
                session.initializeKeypad(decoder::set, onLog)
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
