package tel.misa.mxpocket

import android.graphics.Bitmap
import android.util.LruCache
import tel.misa.mxpocket.driver.KeypadSink
import tel.misa.mxpocket.driver.MxKeypad

/** Physical sink for KeyImages bitmaps, which are never modified after drawing. */
internal class RenderedKeypad(
    private val encode: suspend (Int, Bitmap) -> ByteArray,
    private val upload: suspend (Int, ByteArray) -> Unit,
    private val disconnect: () -> Unit,
) : KeypadSink {
    constructor(keypad: MxKeypad) : this(keypad::encodeKeyJpeg, keypad::setKeyJpeg, keypad::close)

    private val jpegs = object : LruCache<Bitmap, ByteArray>(2 * 1024 * 1024) {
        // Strong identity keys can outlive the renderer's LRU; budget their pixels as well.
        override fun sizeOf(key: Bitmap, value: ByteArray) = key.allocationByteCount + value.size
    }

    override suspend fun setKeyImage(index: Int, bitmap: Bitmap) {
        val jpeg = jpegs.get(bitmap) ?: encode(index, bitmap).also { jpegs.put(bitmap, it) }
        upload(index, jpeg)
    }

    override fun close() {
        jpegs.evictAll()
        disconnect()
    }
}
