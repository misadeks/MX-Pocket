package tel.misa.mxpocket

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Typeface
import android.graphics.Rect
import android.util.LruCache
import tel.misa.mxpocket.driver.KeypadProtocol
import tel.misa.mxpocket.plugins.KeyTile
import kotlin.math.ceil
import kotlin.math.roundToInt

/** Entries are immutable after drawing. Views and an in-flight upload may outlive a cache entry. */
internal class KeyImages(private val appIcon: (String) -> Bitmap?) {
    private data class CacheKey(val index: Int, val tile: KeyTile)
    private val images = LruCache<CacheKey, Bitmap>(36)
    private var backdrop: Bitmap? = null
    var artwork: Bitmap? = null
        private set

    fun useArtwork(image: Bitmap?): Boolean {
        if (image === artwork) return false
        artwork = image
        clear()
        return true
    }

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
        val art = artwork
        if (art != null) {
            val region = KeypadProtocol.keyRegion(index)
            val src = Rect(region.x, region.y, region.x + region.width, region.y + region.height)
            canvas.drawBitmap(art, src, Rect(0, 0, bitmap.width, bitmap.height), Paint(Paint.FILTER_BITMAP_FLAG))
            if (!tile.enabled) canvas.drawColor(0x55000000)
        } else backdrop?.let {
            canvas.drawBitmap(it, 0f, 0f, null)
            canvas.drawColor(0xab000000.toInt())
        }
        val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = if (art != null || backdrop != null || Color.luminance(tile.background) < .179f) Color.WHITE else Color.BLACK
            textAlign = Paint.Align.CENTER
        }
        fun line(text: String, y: Float, size: Float) {
            paint.textSize = size
            while (paint.measureText(text) > 108 && paint.textSize > 11) paint.textSize -= 1
            val label = if (paint.measureText(text) <= 108) text else {
                val count = paint.breakText(text, true, 108 - paint.measureText("\u2026"), null)
                text.take(count) + "\u2026"
            }
            if (art != null && label.isNotEmpty()) {
                val half = ceil(paint.measureText(label) / 2).toInt()
                val light = lineLuminance(bitmap, 59 - half, 59 + half,
                    (y + paint.ascent()).roundToInt(), (y + paint.descent()).roundToInt())
                // The opposite-color hairline keeps letters legible across highly textured regions.
                paint.color = if (light > .179) Color.WHITE else Color.BLACK
                paint.alpha = 160
                paint.style = Paint.Style.STROKE
                paint.strokeWidth = 1.5f
                canvas.drawText(label, 59f, y, paint)
                paint.style = Paint.Style.FILL
                paint.color = if (light > .179) Color.BLACK else Color.WHITE
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

    private fun lineLuminance(bitmap: Bitmap, left: Int, right: Int, top: Int, bottom: Int): Double {
        var sum = 0.0
        var count = 0
        for (y in top.coerceAtLeast(0)..bottom.coerceAtMost(bitmap.height - 1) step 3) {
            for (x in left.coerceAtLeast(0)..right.coerceAtMost(bitmap.width - 1) step 3) {
                sum += Color.luminance(bitmap.getPixel(x, y))
                count++
            }
        }
        return sum / count.coerceAtLeast(1)
    }
}
