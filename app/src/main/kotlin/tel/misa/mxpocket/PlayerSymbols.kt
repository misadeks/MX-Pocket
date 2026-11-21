package tel.misa.mxpocket

import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Path
import android.graphics.Typeface
import tel.misa.mxpocket.plugins.KeyGlyph
import tel.misa.mxpocket.plugins.NavigationGlyph
import tel.misa.mxpocket.plugins.PlayerGlyph

internal fun Canvas.drawKeyGlyph(glyph: KeyGlyph, x: Float, y: Float, size: Float, ink: Int) = when (glyph) {
    is PlayerGlyph -> playerSymbol(glyph, x, y, size, ink)
    is NavigationGlyph -> navigationSymbol(glyph, x, y, size, ink)
}

/** Original 24-unit paths. Draw at the final LCD resolution, without a font or density fallback. */
private fun Canvas.playerSymbol(glyph: PlayerGlyph, x: Float, y: Float, size: Float, ink: Int) {
    save()
    translate(x - size / 2, y - size / 2)
    scale(size / 24, size / 24)
    val p = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = ink; strokeCap = Paint.Cap.ROUND; strokeWidth = 2f }
    fun shape(vararg points: Float) {
        drawPath(Path().apply {
            moveTo(points[0], points[1])
            for (i in 2 until points.size step 2) lineTo(points[i], points[i+1])
            close()
        }, p)
    }
    fun bar(l: Float, t: Float, r: Float, b: Float) = drawRoundRect(l, t, r, b, .6f, .6f, p)
    when (glyph) {
        PlayerGlyph.PLAY -> shape(7f, 3f, 21f, 12f, 7f, 21f)
        PlayerGlyph.PAUSE -> { bar(5f, 4f, 10f, 20f); bar(14f, 4f, 19f, 20f) }
        PlayerGlyph.PLAY_PAUSE -> {
            shape(2f, 4f, 13f, 12f, 2f, 20f); bar(15f, 5f, 18f, 19f); bar(20f, 5f, 23f, 19f)
        }
        PlayerGlyph.PREVIOUS, PlayerGlyph.NEXT -> {
            if (glyph == PlayerGlyph.PREVIOUS) scale(-1f, 1f, 12f, 12f)
            shape(4f, 4f, 17f, 12f, 4f, 20f); bar(17f, 4f, 20f, 20f)
        }
        PlayerGlyph.VOLUME_DOWN, PlayerGlyph.VOLUME_UP, PlayerGlyph.MUTE, PlayerGlyph.UNMUTE -> {
            shape(1f, 9f, 6f, 9f, 12f, 4f, 12f, 20f, 6f, 15f, 1f, 15f)
            when (glyph) {
                PlayerGlyph.MUTE -> {
                    drawLine(16f, 9f, 22f, 15f, p); drawLine(22f, 9f, 16f, 15f, p)
                }
                PlayerGlyph.UNMUTE -> {
                    p.style = Paint.Style.STROKE
                    drawArc(8f, 7f, 17f, 17f, -52f, 104f, false, p)
                    drawArc(3f, 2f, 22f, 22f, -48f, 96f, false, p)
                }
                else -> {
                    drawLine(16f, 12f, 23f, 12f, p)
                    if (glyph == PlayerGlyph.VOLUME_UP) drawLine(19.5f, 8.5f, 19.5f, 15.5f, p)
                }
            }
        }
        PlayerGlyph.BACK_10, PlayerGlyph.FORWARD_10 -> {
            save()
            if (glyph == PlayerGlyph.BACK_10) scale(-1f, 1f, 12f, 12f)
            p.style = Paint.Style.STROKE
            drawArc(3f, 4f, 21f, 22f, -90f, -285f, false, p)
            p.style = Paint.Style.FILL
            shape(11f, .5f, 16f, 4f, 11f, 7.5f)
            restore()
            p.typeface = Typeface.DEFAULT_BOLD
            p.textAlign = Paint.Align.CENTER
            p.textSize = 8.5f
            drawText("10", 12f, 16f, p)
        }
        PlayerGlyph.TRACK -> {
            bar(4f, 10f, 7f, 20f); bar(10.5f, 3f, 13.5f, 20f); bar(17f, 7f, 20f, 20f)
        }
    }
    restore()
}
