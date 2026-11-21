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
import tel.misa.mxpocket.plugins.NavigationGlyph
import tel.misa.mxpocket.plugins.PlayerGlyph

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
            if (!tile.enabled) canvas.drawColor(0x55000000)
        } else backdrop?.let {
            canvas.drawBitmap(it, 0f, 0f, null)
            canvas.drawColor(0xab000000.toInt())
        }
        val photo = art != null || backdrop != null
        val whiteInk = photo || Color.luminance(tile.background) < .179f
        val ink = if (whiteInk) Color.WHITE else Color.BLACK
        val subdued = if (whiteInk) 0xffbec4ca.toInt() else 0xff42484e.toInt()
        val paint = Paint(Paint.ANTI_ALIAS_FLAG)
        val glyph = tile.glyph
        val primary = tile.primary
        val caption = tile.showLabel
        val withFooter = tile.showFooter && tile.footer.isNotBlank() && tile.footer != tile.label
        val face = when (glyph) {
            is PlayerGlyph -> 53f
            is NavigationGlyph -> if (withFooter) 29f else 38f
            null -> if (caption) 21f else if (withFooter) 47f else 59f
        }
        val appImage = tile.iconComponent?.takeIf { tile.icon.isBlank() }?.let(appIcon)
        if (glyph is PlayerGlyph && tile.icon.isBlank()) {
            val color = if (photo) contrastInk(bitmap, 37, 31, 81, 75) else ink
            canvas.drawKeyGlyph(glyph, 59f, face, 49.5f, if (tile.enabled) color else mutedInk(color), photo)
        } else if (glyph is NavigationGlyph && tile.icon.isBlank()) {
            val diameter = if (withFooter) 35f else 48f
            val disk = diameter + 8f
            val lightDisk = primary && tile.enabled
            if (photo || primary) {
                paint.color = if (lightDisk) Color.WHITE else 0xdd17191c.toInt()
                canvas.drawCircle(59f, face, disk / 2, paint)
            }
            canvas.drawKeyGlyph(glyph, 59f, face, diameter,
                if (!tile.enabled) 0xff9a9fa4.toInt() else if (lightDisk) Color.BLACK else if (photo || primary) Color.WHITE else ink)
        } else if (appImage != null) {
            canvas.drawBitmap(appImage, null, Rect(41, 3, 77, 39), Paint(Paint.FILTER_BITMAP_FLAG))
        } else {
            caption(canvas, bitmap, tile.icon.ifBlank { "${index+1}" }, face, 20f, 1, false, ink, photo)
        }
        val labelY = when (glyph) {
            is PlayerGlyph -> 94f
            is NavigationGlyph -> if (withFooter) 67f else 87f
            null -> if (withFooter) 62f else 70f
        }
        if (caption) caption(canvas, bitmap, tile.label, labelY, if (glyph is PlayerGlyph) 17f else 19f, 2, true, ink, photo)
        if (withFooter) caption(canvas, bitmap, tile.footer, if (caption) 100f else 94f, 15f, 1, false, subdued, photo)
        tile.volumeLevel?.fraction?.let { fraction ->
            val y = if (caption) 74f else 85f
            val color = if (photo) contrastInk(bitmap, 28,y.toInt()-2,90,y.toInt()+2) else ink
            // A two-tone rail is visible even at zero; fill length, not color, encodes the level.
            paint.color = if (color == Color.BLACK) 0xffeeeeee.toInt() else 0xff1b1d20.toInt()
            canvas.drawRoundRect(27f,y-3f,91f,y+3f,3f,3f,paint)
            paint.style = Paint.Style.STROKE; paint.strokeWidth = 1.5f; paint.color = color
            canvas.drawRoundRect(27f,y-3f,91f,y+3f,3f,3f,paint)
            paint.style = Paint.Style.FILL
            if (fraction > 0f) canvas.drawRoundRect(27f,y-3f,27f+64f*fraction,y+3f,3f,3f,paint)
        }
        images.put(key, bitmap)
        return bitmap
    }

    private fun mutedInk(color: Int) = if (color == Color.BLACK) 0xff56585a.toInt() else 0xff999da0.toInt()

    private fun contrastInk(bitmap: Bitmap, left: Int, top: Int, right: Int, bottom: Int): Int {
        var sum = 0f; var count = 0
        for (y in top..bottom step 4) for (x in left..right step 4) {
            sum += Color.luminance(bitmap.getPixel(x,y)); count++
        }
        return if (sum / count > .22f) Color.BLACK else Color.WHITE
    }

    /** Fixed optical sizes; wrap the title once and ellipsize instead of shrinking it to fit. */
    private fun caption(canvas: Canvas, bitmap: Bitmap, text: String, center: Float, size: Float, lines: Int,
                        bold: Boolean, ink: Int, photo: Boolean) {
        if (text.isBlank()) return
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
    }
}
