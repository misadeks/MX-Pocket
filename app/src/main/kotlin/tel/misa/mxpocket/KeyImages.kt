package tel.misa.mxpocket

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Typeface
import android.graphics.Path
import android.graphics.Rect
import android.graphics.RectF
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
    var artworkRevision = 0L
        private set

    fun useArtwork(image: Bitmap?): Boolean {
        if (image === artwork) return false
        artwork = image
        artworkRevision++
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
        val art = artwork
        if (art != null) {
            // A whole-grid cover stays edge to edge so it reads as one picture across the keys.
            canvas.drawColor(tile.background)
            val region = KeypadProtocol.keyRegion(index)
            val src = Rect(region.x, region.y, region.x + region.width, region.y + region.height)
            canvas.drawBitmap(art, src, Rect(0, 0, bitmap.width, bitmap.height), Paint(Paint.FILTER_BITMAP_FLAG))
            if (!tile.enabled) canvas.drawColor(0x55000000)
        } else {
            // Keypad artwork: a rounded tile in the page color on the panel's near-black background.
            canvas.drawColor(PANEL)
            val shape = RectF(1f, 1f, 117f, 117f)
            canvas.drawRoundRect(shape, RADIUS, RADIUS, Paint(Paint.ANTI_ALIAS_FLAG).apply { color = tile.background })
            backdrop?.let {
                canvas.save()
                canvas.clipPath(Path().apply { addRoundRect(shape, RADIUS, RADIUS, Path.Direction.CW) })
                canvas.drawBitmap(it, 0f, 0f, null)
                canvas.drawColor(0xab000000.toInt())
                canvas.restore()
            }
        }
        val dark = art != null || backdrop != null || Color.luminance(tile.background) < .179f
        val ink = if (dark) LIGHT_INK else Color.BLACK
        val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = ink
            textAlign = Paint.Align.CENTER
        }
        fun line(text: String, y: Float, size: Float) {
            paint.color = ink
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
        if (appImage != null) canvas.drawBitmap(appImage, null, Rect(39, 10, 79, 50), Paint(Paint.FILTER_BITMAP_FLAG))
        else if (tile.icon.isNotBlank()) line(tile.icon, 48f, 34f)
        // Key positions are not numbered on the LCD. Without an icon the label is centred and larger;
        // numeric readouts such as a countdown use bold monospace digits.
        val iconShown = appImage != null || tile.icon.isNotBlank()
        val numeric = tile.label.isNotEmpty() && tile.label.all { it.isDigit() || it in " :/." }
        paint.typeface = if (numeric) Typeface.create(Typeface.MONOSPACE, Typeface.BOLD) else Typeface.DEFAULT_BOLD
        when {
            iconShown -> line(tile.label, 78f, 17f)
            numeric -> line(tile.label, 68f, 28f)
            else -> line(tile.label, 66f, 20f)
        }
        paint.typeface = Typeface.DEFAULT_BOLD
        paint.letterSpacing = .08f
        line(tile.footer.uppercase(), 104f, 11f)
        paint.letterSpacing = 0f
        images.put(key, bitmap)
        return bitmap
    }

    private companion object {
        const val RADIUS = 14f
        const val PANEL = 0xff0c0e13.toInt()
        const val LIGHT_INK = 0xffeef2ff.toInt()
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
