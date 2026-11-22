package tel.misa.mxpocket.plugins

import androidx.compose.ui.graphics.*
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntSize

/** Type and symbols are laid out in the LCD's native 118-unit square, never in screen dp. */
data class FaceCaption(val text: String, val center: Float, val size: Float, val lines: Int, val bold: Boolean)

fun Canvas.paintKeyFace(
    index: Int, tile: KeyTile, iconImage: ImageBitmap? = null,
    photoInk: ((Int, Int, Int, Int) -> Color)? = null,
    caption: (FaceCaption, Color) -> Unit,
) {
    val glyph = tile.glyph
    // Audio transports own the center of a key; named actions share Navigation's caption baseline.
    val player = tile.playerFace
    val label = tile.showLabel && tile.label.isNotBlank()
    val footer = tile.showFooter && tile.footer.isNotBlank() && tile.footer != tile.label
    val dark = Color(tile.background).luminance() < .179f
    val ink = if (dark) Color.White else Color.Black
    val subdued = if (dark) Color(0xffbec4ca) else Color(0xff42484e)
    val captionInk = if (!tile.enabled && !tile.readOnly) subdued else ink
    val p = Paint()
    val faceY = when {
        player -> 53f
        glyph != null -> if (footer) 29f else 38f
        else -> if (label) 21f else 47f
    }
    when {
        tile.icon.isNotBlank() -> caption(FaceCaption(tile.icon, faceY, 24f, 1, true), captionInk)
        iconImage != null -> {
            if (glyph is IntegrationApp && !tile.enabled) p.alpha = .65f
            drawImageRect(iconImage, dstOffset = IntOffset(40, 5), dstSize = IntSize(38, 38), paint = p)
            p.alpha = 1f
        }
        glyph != null -> {
            val diameter = if (player) 49.5f else if (footer) 35f else 48f
            val sampled = photoInk?.invoke(37, 31, 81, 75)
            val plate = !player && (sampled != null || tile.primary)
            val lightPlate = tile.primary && tile.enabled
            if (plate) {
                p.color = if (lightPlate) Color.White else Color(0xdd17191c)
                drawCircle(androidx.compose.ui.geometry.Offset(59f, faceY), diameter / 2 + 4f, p)
            }
            val color = when {
                !tile.enabled && !tile.readOnly -> if ((sampled ?: ink) == Color.Black) Color(0xff56585a) else Color(0xff999da0)
                plate -> if (lightPlate) Color.Black else Color.White
                else -> sampled ?: ink
            }
            if (player && sampled != null) {
                save()
                translate(0f, 1f)
                symbol(glyph, faceY, diameter + 1.2f,
                    (if (color == Color.Black) Color.White else Color.Black).copy(alpha = .65f))
                restore()
            }
            symbol(glyph, faceY, diameter, color)
        }
        !tile.readOnly && !tile.quietFace ->
            caption(FaceCaption("${index + 1}", faceY, 20f, 1, false), subdued)
    }
    if (label) {
        val y = when {
            player -> 94f
            glyph != null -> if (footer) 67f else 87f
            tile.readOnly || tile.quietFace -> if (footer) 48f else 59f
            else -> if (footer) 62f else 70f
        }
        caption(FaceCaption(tile.label, y, when {
            player -> 17f
            (tile.readOnly || tile.quietFace) && glyph == null && tile.label.length <= 6 && tile.icon.isBlank() -> 28f
            else -> 19f
        }, 2, true), captionInk)
    }
    if (footer) caption(FaceCaption(tile.footer, if (label) 100f else 94f, 15f, 1, false), subdued)
    if (tile.enabled && (tile.selection == KeySelection.ON || tile.selection == KeySelection.UNKNOWN)) {
        val badgeInk = photoInk?.invoke(91, 6, 111, 23) ?: ink
        val center = androidx.compose.ui.geometry.Offset(101f, 16f)
        p.style = PaintingStyle.Fill
        if (photoInk != null) {
            p.color = if (badgeInk == Color.Black) Color.White else Color(0xff16171a)
            drawCircle(center, 10f, p)
        }
        p.color = badgeInk; p.style = PaintingStyle.Stroke; p.strokeWidth = 2.5f; p.strokeCap = StrokeCap.Round
        if (tile.selection == KeySelection.ON) {
            drawLine(center.copy(x = 95f), center.copy(x = 99.5f, y = 20f), p)
            drawLine(center.copy(x = 99.5f, y = 20f), center.copy(x = 107f, y = 11.5f), p)
        } else {
            drawArc(97f, 9.5f, 105f, 17f, 195f, 250f, false, p)
            drawLine(center.copy(y = 17f), center.copy(y = 18f), p)
            p.style = PaintingStyle.Fill
            drawCircle(center.copy(y = 22f), 1.25f, p)
        }
    }
    (tile.levelFraction ?: tile.volumeLevel?.fraction)?.coerceIn(0f, 1f)?.let { level ->
        val y = if (label) 74f else 85f
        val color = photoInk?.invoke(28, y.toInt()-2, 90, y.toInt()+2) ?: ink
        p.color = if (color == Color.Black) Color(0xffeeeeee) else Color(0xff1b1d20)
        drawRoundRect(27f,y-3,91f,y+3,3f,3f,p)
        p.style = PaintingStyle.Stroke; p.strokeWidth = 1.5f; p.color = color
        drawRoundRect(27f,y-3,91f,y+3,3f,3f,p)
        p.style = PaintingStyle.Fill
        if (level > 0f) drawRoundRect(27f,y-3,27f+64*level,y+3,3f,3f,p)
    }
}
