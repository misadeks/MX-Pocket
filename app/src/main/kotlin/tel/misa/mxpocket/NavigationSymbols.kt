package tel.misa.mxpocket

import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Path
import tel.misa.mxpocket.plugins.NavigationGlyph

/** Original 24-unit key legends. Back turns a corner; focus travels to a stop. */
internal fun Canvas.navigationSymbol(glyph: NavigationGlyph, x: Float, y: Float, size: Float, ink: Int) {
    save()
    translate(x - size / 2, y - size / 2)
    scale(size / 24, size / 24)
    val p = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = ink
        style = Paint.Style.STROKE
        strokeCap = Paint.Cap.ROUND
        strokeJoin = Paint.Join.ROUND
        strokeWidth = 2.2f
    }
    val path = Path().apply {
        when (glyph) {
            NavigationGlyph.BACK -> {
                moveTo(9.5f, 3.5f); lineTo(3f, 10f); lineTo(9.5f, 16.5f)
                moveTo(4f, 10f); lineTo(15f, 10f); quadTo(21f, 10f, 21f, 16f); lineTo(21f, 20f)
            }
            NavigationGlyph.HOME -> {
                moveTo(2f, 11.5f); lineTo(12f, 3f); lineTo(22f, 11.5f)
                moveTo(5f, 10f); lineTo(5f, 21f); lineTo(10f, 21f); lineTo(10f, 15f)
                lineTo(14f, 15f); lineTo(14f, 21f); lineTo(19f, 21f); lineTo(19f, 10f)
            }
            NavigationGlyph.UP, NavigationGlyph.DOWN -> {
                if (glyph == NavigationGlyph.DOWN) scale(1f, -1f, 12f, 12f)
                moveTo(5f, 10.5f); lineTo(12f, 3.5f); lineTo(19f, 10.5f)
                moveTo(12f, 4.5f); lineTo(12f, 17f)
                moveTo(6f, 21f); lineTo(18f, 21f)
            }
            NavigationGlyph.PREVIOUS_FOCUS, NavigationGlyph.NEXT_FOCUS -> {
                if (glyph == NavigationGlyph.PREVIOUS_FOCUS) scale(-1f, 1f, 12f, 12f)
                moveTo(3f, 12f); lineTo(15f, 12f)
                moveTo(10f, 6f); lineTo(16f, 12f); lineTo(10f, 18f)
                moveTo(21f, 4f); lineTo(21f, 20f)
            }
            NavigationGlyph.SELECT -> {
                addCircle(12f, 12f, 9f, Path.Direction.CW)
                moveTo(8f, 12f); lineTo(11f, 15f); lineTo(16.5f, 9f)
            }
            NavigationGlyph.RECENTS -> {
                addRoundRect(3f, 8f, 17f, 20f, 1.8f, 1.8f, Path.Direction.CW)
                moveTo(7f, 4f); lineTo(19f, 4f); quadTo(21f, 4f, 21f, 6f); lineTo(21f, 15f)
            }
            NavigationGlyph.ACCESS_INFO -> {
                addCircle(12f, 12f, 9.5f, Path.Direction.CW)
                moveTo(12f, 11.5f); lineTo(12f, 17f)
                drawCircle(12f, 7.5f, 1.4f, Paint(Paint.ANTI_ALIAS_FLAG).apply { color = ink })
            }
        }
    }
    drawPath(path, p)
    restore()
}
