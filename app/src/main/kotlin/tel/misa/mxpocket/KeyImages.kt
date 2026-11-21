package tel.misa.mxpocket

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Typeface
import android.util.LruCache
import tel.misa.mxpocket.plugins.KeyTile

/** Entries are immutable after drawing. Views and an in-flight upload may outlive a cache entry. */
internal class KeyImages(private val appIcon: (String) -> Bitmap?) {
    private data class CacheKey(val index: Int, val tile: KeyTile)
    private val images = LruCache<CacheKey, Bitmap>(36)
    private var backdrop: Bitmap? = null

    fun setBackdrop(image: Bitmap) {
        backdrop = image
        images.evictAll()
    }

    fun clear() = images.evictAll()

    fun image(index: Int, tile: KeyTile): Bitmap {
        val key = CacheKey(index, tile)
        images.get(key)?.let { return it }
        val bitmap = Bitmap.createBitmap(118, 118, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bitmap)
        canvas.drawColor(tile.background)
        backdrop?.let {
            canvas.drawBitmap(it, 0f, 0f, null)
            canvas.drawColor(0xab000000.toInt())
        }
        val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = if (backdrop != null || Color.luminance(tile.background) < .179f) Color.WHITE else Color.BLACK
            textAlign = Paint.Align.CENTER
        }
        fun line(text: String, y: Float, size: Float) {
            paint.textSize = size
            while (paint.measureText(text) > 108 && paint.textSize > 11) paint.textSize -= 1
            val label = if (paint.measureText(text) <= 108) text else {
                val count = paint.breakText(text, true, 108 - paint.measureText("\u2026"), null)
                text.take(count) + "\u2026"
            }
            canvas.drawText(label, 59f, y, paint)
        }
        val appImage = tile.iconComponent?.takeIf { tile.icon.isBlank() }?.let(appIcon)
        if (appImage != null) canvas.drawBitmap(appImage,null,android.graphics.Rect(38,4,80,46),
            Paint(Paint.FILTER_BITMAP_FLAG))
        else line(tile.icon.ifBlank { "${index + 1}" }, 30f, 23f)
        paint.typeface = Typeface.DEFAULT_BOLD
        line(tile.label, 69f, 19f)
        paint.typeface = Typeface.DEFAULT
        line(tile.footer, 102f, 13f)
        images.put(key, bitmap)
        return bitmap
    }
}
