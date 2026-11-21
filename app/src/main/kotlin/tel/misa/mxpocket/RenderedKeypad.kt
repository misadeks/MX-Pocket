package tel.misa.mxpocket

import android.graphics.Bitmap
import android.util.LruCache
import tel.misa.mxpocket.driver.KeypadSink
import tel.misa.mxpocket.driver.MxKeypad

/** Physical sink for KeyImages bitmaps, which are never modified after drawing. */
internal class RenderedKeypad(
    private val encode: suspend (Int, Bitmap, Boolean) -> ByteArray,
    private val upload: suspend (Int, ByteArray) -> Unit,
    private val disconnect: () -> Unit,
) : KeypadSink {
    constructor(keypad: MxKeypad) : this(keypad::encodeKeyJpeg, keypad::setKeyJpeg, keypad::close)

    private data class JpegKey(val bitmap: Bitmap, val coverArt: Boolean)
    private val jpegs = object : LruCache<JpegKey, ByteArray>(2 * 1024 * 1024) {
        // Strong identity keys can outlive the renderer's LRU; budget their pixels as well.
        override fun sizeOf(key: JpegKey, value: ByteArray) = key.bitmap.allocationByteCount + value.size
    }

    private var generation = 0L
    fun clearImages() {
        generation++
        jpegs.evictAll()
    }

    override suspend fun setKeyImage(index: Int, bitmap: Bitmap) = setKeyImage(index, bitmap, coverArt = false)

    suspend fun setKeyImage(index: Int, bitmap: Bitmap, coverArt: Boolean) {
        val key = JpegKey(bitmap, coverArt)
        val epoch = generation
        val jpeg = jpegs.get(key) ?: encode(index, bitmap, coverArt).also { if (epoch == generation) jpegs.put(key, it) }
        if (epoch != generation) return
        upload(index, jpeg)
    }

    override fun close() {
        clearImages()
        disconnect()
    }
}
