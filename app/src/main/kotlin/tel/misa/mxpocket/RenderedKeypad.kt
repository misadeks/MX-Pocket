package tel.misa.mxpocket

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Rect
import android.util.LruCache
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import tel.misa.mxpocket.driver.KeypadProtocol
import tel.misa.mxpocket.driver.KeypadSink
import tel.misa.mxpocket.driver.MxKeypad

internal enum class GridWrite { ACKNOWLEDGED, TOO_LARGE, SUPERSEDED, CLEARED }

internal interface GridOutput : KeypadSink {
    suspend fun writeGrid(art: Bitmap?, tiles: List<Bitmap>, clearOldGaps: Boolean, current: () -> Boolean): GridWrite
}

/** Physical sink for KeyImages bitmaps, which are never modified after drawing. */
internal class RenderedKeypad(
    private val encode: suspend (Int, Bitmap, Boolean) -> ByteArray,
    private val upload: suspend (Int, ByteArray) -> Unit,
    private val disconnect: () -> Unit,
    private val encodeGrid: suspend (Bitmap) -> ByteArray? = { null },
    private val uploadGrid: suspend (ByteArray) -> Unit = { error("Grid encoder has no uploader") },
) : GridOutput {
    constructor(keypad: MxKeypad) : this(keypad::encodeKeyJpeg, keypad::setKeyJpeg, keypad::close,
        { keypad.encodeRegionJpeg(KeypadProtocol.keyGrid, it) }, { keypad.setRegionJpeg(KeypadProtocol.keyGrid, it) })

    override suspend fun writeGrid(art: Bitmap?, tiles: List<Bitmap>, clearOldGaps: Boolean, current: () -> Boolean): GridWrite {
        val epoch = generation
        var erased = false
        val bytes = withContext(Dispatchers.Default) {
            val grid = KeypadProtocol.keyGrid
            val buffer = Bitmap.createBitmap(grid.width, grid.height, Bitmap.Config.ARGB_8888)
            try {
                val canvas = Canvas(buffer)
                canvas.drawColor(Color.BLACK)
                if (art != null) canvas.drawBitmap(art, Rect(grid.x, grid.y, grid.x + grid.width, grid.y + grid.height),
                    Rect(0, 0, grid.width, grid.height), null)
                require(tiles.size == 9)
                tiles.forEachIndexed { i, tile ->
                    val r = KeypadProtocol.keyRegion(i)
                    val x = r.x - grid.x
                    val y = r.y - grid.y
                    canvas.drawBitmap(tile, null, Rect(x, y, x + r.width, y + r.height), null)
                }
                encodeGrid(buffer) ?: if (clearOldGaps) {
                    // Individual key writes cannot clear the gaps left by a previous cover.
                    erased = true
                    buffer.eraseColor(Color.BLACK)
                    checkNotNull(encodeGrid(buffer)) { "Blank LCD grid exceeds image limit" }
                } else null
            } finally { buffer.recycle() }
        }
        if (epoch != generation || !current()) return GridWrite.SUPERSEDED
        if (bytes == null) return GridWrite.TOO_LARGE
        uploadGrid(bytes)
        return if (erased) GridWrite.CLEARED else GridWrite.ACKNOWLEDGED
    }

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

    override suspend fun setKeyImage(index: Int, bitmap: Bitmap) {
        setKeyImage(index, bitmap, coverArt = false)
    }

    suspend fun setKeyImage(index: Int, bitmap: Bitmap, coverArt: Boolean, current: () -> Boolean = { true }): Boolean {
        val key = JpegKey(bitmap, coverArt)
        val epoch = generation
        val jpeg = jpegs.get(key) ?: encode(index, bitmap, coverArt).also { if (epoch == generation) jpegs.put(key, it) }
        if (epoch != generation || !current()) return false
        upload(index, jpeg)
        return true
    }

    override fun close() {
        clearImages()
        disconnect()
    }
}
