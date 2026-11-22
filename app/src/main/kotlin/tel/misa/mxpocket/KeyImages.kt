package tel.misa.mxpocket

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Typeface
import android.graphics.Rect
import android.text.Layout
import android.text.StaticLayout
import android.text.TextPaint
import android.text.TextUtils
import android.util.LruCache
import tel.misa.mxpocket.driver.KeypadProtocol
import tel.misa.mxpocket.plugins.KeyTile
import tel.misa.mxpocket.plugins.IntegrationApp
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.toArgb
import tel.misa.mxpocket.plugins.paintKeyFace

/** Entries are immutable after drawing. Views and an in-flight upload may outlive a cache entry. */
internal class KeyImages(private val integrationIcon: (IntegrationApp) -> Bitmap? = { null }, private val appIcon: (String) -> Bitmap?) {
    private data class CacheKey(val index: Int, val tile: KeyTile)
    private val images = LruCache<CacheKey, Bitmap>(36)
    private var backdrop: Bitmap? = null
    var artwork: Bitmap? = null
        private set
    var artworkRevision = 0L
        private set

    fun useArtwork(image: Bitmap?): Boolean {
        if (image === artwork) return false
        val previous = artwork
        artwork = image
        if (previous != null && image != null && previous.sameAs(image)) return false
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
        canvas.drawColor(tile.background)
        val art = artwork
        if (art != null) {
            val region = KeypadProtocol.keyRegion(index)
            val src = Rect(region.x, region.y, region.x + region.width, region.y + region.height)
            canvas.drawBitmap(art, src, Rect(0, 0, bitmap.width, bitmap.height), Paint(Paint.FILTER_BITMAP_FLAG))
            if (!tile.enabled && !tile.backgroundOnly) canvas.drawColor(0x55000000)
        } else backdrop?.let {
            canvas.drawBitmap(it, 0f, 0f, null)
            canvas.drawColor(0xab000000.toInt())
        }
        val photo = art != null || backdrop != null
        val appImage = if (tile.icon.isNotBlank()) null else tile.iconComponent?.let(appIcon)
            ?: (tile.glyph as? IntegrationApp)?.let(integrationIcon)
        androidx.compose.ui.graphics.Canvas(bitmap.asImageBitmap()).paintKeyFace(index, tile, appImage?.asImageBitmap(),
            if (!photo) null else { l, t, r, b -> androidx.compose.ui.graphics.Color(contrastInk(bitmap, l, t, r, b)) }
        ) { text, ink ->
            caption(canvas, bitmap, text.text, text.center, text.size, text.lines, text.bold, ink.toArgb(), photo)
        }
        images.put(key, bitmap)
        return bitmap
    }

    private fun contrastInk(bitmap: Bitmap, left: Int, top: Int, right: Int, bottom: Int): Int {
        var sum = 0f; var count = 0
        for (y in top..bottom step 4) for (x in left..right step 4) {
            sum += Color.luminance(bitmap.getPixel(x,y)); count++
        }
        return if (sum / count > .22f) Color.BLACK else Color.WHITE
    }

    /** Fixed optical sizes; wrap the title once and ellipsize instead of shrinking it to fit. */
    private fun caption(canvas: Canvas, bitmap: Bitmap, text: String, center: Float, size: Float, lines: Int,
                        bold: Boolean, ink: Int, photo: Boolean): Float {
        val p = TextPaint(Paint.ANTI_ALIAS_FLAG).apply {
            color = ink; textSize = size; typeface = if (bold) Typeface.DEFAULT_BOLD else Typeface.DEFAULT
        }
        val width = 104
        val layout = StaticLayout.Builder.obtain(text, 0, text.length, p, width)
            .setAlignment(Layout.Alignment.ALIGN_CENTER).setIncludePad(false).setMaxLines(lines)
            .setEllipsize(TextUtils.TruncateAt.END).build()
        val top = center - layout.height / 2f
        if (photo) {
            val half = (0 until layout.lineCount).maxOf { layout.getLineWidth(it) } / 2f + 4
            // Flat cover regions need only contrasting ink. Isolate type on mixed-tone detail.
            var min = 1f; var max = 0f
            for (y in top.toInt().coerceAtLeast(0)..(top + layout.height).toInt().coerceAtMost(117) step 3) {
                for (x in (59 - half).toInt().coerceAtLeast(0)..(59 + half).toInt().coerceAtMost(117) step 3) {
                    val lum = Color.luminance(bitmap.getPixel(x, y))
                    min = minOf(min, lum); max = maxOf(max, lum)
                }
            }
            p.color = if (min >= .18f) Color.BLACK else Color.WHITE
            if (min < .18f && max > .18f) canvas.drawRoundRect(59 - half, top - 1, 59 + half, top + layout.height + 1,
                4f, 4f, Paint(Paint.ANTI_ALIAS_FLAG).apply { color = 0xd9000000.toInt() })
        }
        canvas.save()
        canvas.translate((118 - width) / 2f, top)
        layout.draw(canvas)
        canvas.restore()
        return top + layout.getLineBaseline(0)
    }
}
