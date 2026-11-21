package tel.misa.mxpocket.driver

import android.graphics.Bitmap
import java.io.IOException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/** In-memory keypad. No USB access, protocol acknowledgements or phone actions. */
class SimulatedKeypad(private val onEvent: (KeyEvent) -> Unit = {}) : KeypadSink {
    private val buttons = ButtonEvents(onEvent)
    private val images = arrayOfNulls<Bitmap>(9)

    override suspend fun setKeyImage(index: Int, bitmap: Bitmap) {
        val region = KeypadProtocol.keyRegion(index)
        withContext(Dispatchers.Default) {
            synchronized(this@SimulatedKeypad) {
                if (buttons.closed) throw IOException("Simulated keypad disconnected")
                val scaled = Bitmap.createScaledBitmap(bitmap, region.width, region.height, true)
                try {
                    // createScaledBitmap may return its input. Never retain a caller-owned bitmap.
                    val owned = scaled.copy(Bitmap.Config.ARGB_8888, false)
                        ?: throw IOException("Cannot copy simulated key image")
                    images[index]?.recycle()
                    images[index] = owned
                } finally {
                    if (scaled !== bitmap) scaled.recycle()
                }
            }
        }
    }

    /** Independent caller-owned pixels, or null if not painted on this connection. */
    @Synchronized
    fun renderedImage(index: Int): Bitmap? {
        require(index in images.indices)
        return images[index]?.let {
            it.copy(Bitmap.Config.ARGB_8888, true) ?: throw IOException("Cannot copy rendered key image")
        }
    }

    /** Uses physical control IDs (1..9, 0x01a1/0x01a2). Late events on a closed handle are ignored. */
    @Synchronized
    fun button(controlId: Int, pressed: Boolean) {
        require(controlId in 1..9 || controlId in 0x01a1..0x01a2) { "Unknown keypad control" }
        buttons.deliver(KeyEvent(controlId, pressed))
    }

    fun disconnect() = close()

    /** A new connection starts blank. Queued calls on this handle cannot affect its replacement. */
    fun reconnect(onEvent: (KeyEvent) -> Unit = this.onEvent): SimulatedKeypad {
        close()
        return SimulatedKeypad(onEvent)
    }

    @Synchronized
    override fun close() {
        images.forEach { it?.recycle() }
        images.fill(null)
        buttons.close()
    }
}
