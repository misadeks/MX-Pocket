package tel.misa.mxpocket.plugins

import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.*
import androidx.compose.ui.graphics.vector.PathParser

/** Original 24-unit LCD legends. Physical and simulated keys draw these same paths. */
enum class DeckGlyph : KeyGlyph { FOLDER, PROFILE, TIMER, RESET, MINUS, PLUS, CAMERA, VIDEO, STOP, SETTINGS, PHONE, DECLINE, WEB, REFRESH, FLOW, COMPASS, GRID, GAME, CALLER }

/** Original conversation and spiral-notebook outlines, used when the host has no installed icon. */
enum class IntegrationApp(internal val outline: String) : KeyGlyph {
    TEAMS("M3,4H18V13H10L6,17V13H3Z M12,16H16L20,20V16H22V8H21"),
    ONENOTE("M6,3H20V21H6Z M3,7H8 M3,12H8 M3,17H8 M11,8H16 M11,12H16"),
}

private data class Mark(val data: String, val stroke: Float = 0f) {
    val path by lazy { PathParser().parsePathString(data).toPath() }
}

private val speaker = Mark("M1,9H6L12,4V20L6,15H1Z")
private val skip = Mark("M4,4L17,12L4,20Z M17,4H20V20H17Z")
private val seek = listOf(Mark("M12,6 A8,7 0 1,0 19.72,11.19", 2f), Mark("M11,3L16,6L11,9Z"))
private fun outline(path: String) = listOf(Mark(path, 2.2f))
private val noteSymbols = mapOf(
    OneNoteMark.PEN to "M5,16L6,11L15,2L19,6L10,15Z M6.8,10.8L10.4,14.4",
    OneNoteMark.HIGHLIGHTER to "M5,13L13.5,3Q14.5,2 15.5,3L20.5,7Q21.5,8 20.5,9L12,18Z M6,14L3.5,18.5L7.8,18L9.5,16.5",
    OneNoteMark.ERASER to "M4,15L14,5L20,11L12,20H9Z M8,11L15,18 M3,21H6 M16,21H21",
    OneNoteMark.SHAPE to "M4,8V4H8 M16,4H20V8 M20,16V20H16 M8,20H4V16 M8,15.5L12,8L16,15.5Z",
    OneNoteMark.LASSO to "M12,16.5C7,19 2,16.5 3.3,11C4.8,5.2 16,3 20,7.5C23.5,12 18.4,17.1 13.4,16C10.5,15.4 10.6,13.2 12.2,13.2Q14.1,13.3 14.7,16.8Q15.5,20.3 20.5,21",
    OneNoteMark.TOOL to "M4,8H9L11,5H20V18H4Z M8,13H16",
    OneNoteMark.UNAVAILABLE to "M12,3.5A8.5,8.5 0 1,1 12,20.5A8.5,8.5 0 1,1 12,3.5Z M6,18L18,6",
    OneNoteMark.SIZE to "M2,18L7,5L12,18 M4,14H10 M14,18L17.5,10L21,18 M16,15.5H20",
    OneNoteMark.BOLD to "M6,4H13C20,4 20,11 13,11H7 M7,11H14C21,11 21,20 14,20H6V4Z",
    OneNoteMark.ITALIC to "M10,4H19 M5,20H14 M15,4L9,20",
    OneNoteMark.UNDERLINE to "M6,4V11A6,6 0 0,0 18,11V4 M4,21H20",
    OneNoteMark.STRIKE to "M17.5,6Q11,1 6.5,6Q4,9 10,11 M14,13Q21,16 16.5,19.5Q11,23 6,18 M3,12H21",
    OneNoteMark.FONT_COLOR to "M5,16L12,3L19,16 M8,12H16 M4,21H20",
    OneNoteMark.TEXT_HIGHLIGHT to "M3,14L7,5L11,14 M4.5,11H9.5 M15,4.5V14 M15,10Q16,7.6 18,7.6Q21,7.6 21,11Q21,14 18.5,14Q16.5,14 15,12.8 M3,19H21",
    OneNoteMark.BULLETS to "M4,6H4.1 M4,12H4.1 M4,18H4.1 M9,6H21 M9,12H21 M9,18H21",
    OneNoteMark.NUMBERING to "M3,5L5,4V9 M3,13Q7,11 6,15L3,18H7 M11,6H21 M11,16H21",
    OneNoteMark.INDENT to "M3,4H21 M3,20H21 M3,9L6,12L3,15 M10,9H21 M10,15H21",
    OneNoteMark.OUTDENT to "M3,4H21 M3,20H21 M6,9L3,12L6,15 M10,9H21 M10,15H21",
    OneNoteMark.ALIGN_LEFT to "M3,5H21 M3,10H15 M3,15H21 M3,20H14",
    OneNoteMark.ALIGN_CENTER to "M3,5H21 M6,10H18 M3,15H21 M7,20H17",
    OneNoteMark.ALIGN_RIGHT to "M3,5H21 M9,10H21 M3,15H21 M10,20H21",
    OneNoteMark.STYLES to "M5,3H15L19,7V21H5Z M14,4V8H18 M8,12H16 M8,16H13",
    OneNoteMark.DRAW to "M4,17L5,12L15,2L19,6L9,16Z M3,21H21",
    OneNoteMark.PREVIOUS to "M15.5,4L7.5,12L15.5,20",
    OneNoteMark.NEXT to "M8.5,4L16.5,12L8.5,20",
    OneNoteMark.CANCEL to "M5,5L19,19 M19,5L5,19",
).mapValues { Mark(it.value, 2.2f) }
private val symbols: Map<KeyGlyph, List<Mark>> = mapOf(
    IntegrationApp.TEAMS to outline(IntegrationApp.TEAMS.outline),
    IntegrationApp.ONENOTE to outline(IntegrationApp.ONENOTE.outline),
    PlayerGlyph.VOLUME_DOWN to listOf(speaker, Mark("M16,12H23", 2f)),
    PlayerGlyph.VOLUME_UP to listOf(speaker, Mark("M16,12H23 M19.5,8.5V15.5", 2f)),
    PlayerGlyph.MUTE to listOf(speaker, Mark("M16,9L22,15 M22,9L16,15", 2f)),
    PlayerGlyph.UNMUTE to listOf(speaker, Mark("M15.3,8A4.5,5 0 0,1 15.3,16 M18.85,4.57A9.5,10 0 0,1 18.85,19.43", 2f)),
    PlayerGlyph.PLAY to listOf(Mark("M5,4L21,12L5,20Z")),
    PlayerGlyph.PAUSE to listOf(Mark("M5,4H10V20H5Z M14,4H19V20H14Z")),
    PlayerGlyph.PLAY_PAUSE to listOf(Mark("M2,4L13,12L2,20Z M15,5H18V19H15Z M20,5H23V19H20Z")),
    PlayerGlyph.NEXT to listOf(skip), PlayerGlyph.PREVIOUS to listOf(skip),
    PlayerGlyph.BACK_10 to seek, PlayerGlyph.FORWARD_10 to seek,
    PlayerGlyph.TRACK to listOf(Mark("M4,10H7V20H4Z M10.5,4H13.5V20H10.5Z M17,7H20V20H17Z")),
    NavigationGlyph.BACK to outline("M9.5,3.5L3,10L9.5,16.5 M4,10H15Q21,10 21,16V20"),
    NavigationGlyph.HOME to outline("M2,11.5L12,3L22,11.5 M5,10V21H10V15H14V21H19V10"),
    NavigationGlyph.UP to outline("M5,10.5L12,3.5L19,10.5 M12,4.5V17 M6,21H18"),
    NavigationGlyph.DOWN to outline("M5,13.5L12,20.5L19,13.5 M12,19.5V7 M6,3H18"),
    NavigationGlyph.NEXT_FOCUS to outline("M3,12H15 M10,6L16,12L10,18 M21,4V20"),
    NavigationGlyph.PREVIOUS_FOCUS to outline("M21,12H9 M14,6L8,12L14,18 M3,4V20"),
    NavigationGlyph.SELECT to outline("M12,3A9,9 0 1,1 12,21A9,9 0 1,1 12,3Z M8,12L11,15L16.5,9"),
    NavigationGlyph.RECENTS to outline("M5,8H15Q17,8 17,10V18Q17,20 15,20H5Q3,20 3,18V10Q3,8 5,8Z M7,4H19Q21,4 21,6V15"),
    NavigationGlyph.ACCESS_INFO to outline("M12,2.5A9.5,9.5 0 1,1 12,21.5A9.5,9.5 0 1,1 12,2.5Z M12,11.5V17 M12,7.4V7.6"),
    DeckGlyph.FOLDER to outline("M3,7V5H9L12,8H21V19H3V7Z"),
    DeckGlyph.PROFILE to outline("M3,8L12,3L21,8L12,13Z M3,13L12,18L21,13 M5,17L12,21L19,17"),
    DeckGlyph.TIMER to outline("M12,5A8,8 0 1,1 12,21A8,8 0 1,1 12,5Z M10,2H14 M12,9V13L15,15"),
    DeckGlyph.RESET to outline("M4,5V10H9 M4,10A8,8 0 1,1 5,17"),
    DeckGlyph.MINUS to outline("M5,12H19"), DeckGlyph.PLUS to outline("M5,12H19 M12,5V19"),
    DeckGlyph.CAMERA to outline("M3,8H7L9,5H15L17,8H21V19H3Z M12,10A3.5,3.5 0 1,1 12,17A3.5,3.5 0 1,1 12,10Z"),
    DeckGlyph.VIDEO to outline("M3,6H15V18H3Z M15,10L21,7V17L15,14"),
    DeckGlyph.STOP to outline("M5,5H19V19H5Z"),
    DeckGlyph.SETTINGS to outline("M3,6H11 M16,6H21 M3,12H6 M11,12H21 M3,18H14 M19,18H21 M13,4V8 M8,10V14 M16,16V20"),
    DeckGlyph.PHONE to outline("M5,3L9,4L10,8L8,10Q10,14 14,16L16,14L20,15L21,19Q20,22 16,21Q6,18 3,8Q2,5 5,3Z"),
    DeckGlyph.DECLINE to outline("M2,14Q2,8 12,8Q22,8 22,14L21,16L17,16L16,12Q12,11 8,12L7,16H3Z"),
    DeckGlyph.CALLER to outline("M12,3A4,4 0 1,1 12,11A4,4 0 1,1 12,3Z M4,21V19Q4,14 12,14Q20,14 20,19V21"),
    DeckGlyph.WEB to outline("M12,3A9,9 0 1,1 12,21A9,9 0 1,1 12,3Z M3,12H21 M12,3Q5,12 12,21Q19,12 12,3Z"),
    DeckGlyph.REFRESH to outline("M20,4V10H14 M20,10A8,8 0 1,0 19,17"),
    DeckGlyph.FLOW to outline("M4,5H10V10H4Z M14,14H20V19H14Z M7,10V16.5H13"),
    DeckGlyph.COMPASS to outline("M3,11L21,3L13,21L11,13Z"),
    DeckGlyph.GRID to outline("M3,3H10V10H3Z M14,3H21V10H14Z M3,14H10V21H3Z M14,14H21V21H14Z"),
    DeckGlyph.GAME to outline("M3,6L10,13 M10,6L3,13 M13,15A4,4 0 1,1 21,15A4,4 0 1,1 13,15Z"),
)

internal fun Canvas.symbol(glyph: KeyGlyph, y: Float, diameter: Float, ink: Color) {
    val seeking = glyph == PlayerGlyph.BACK_10 || glyph == PlayerGlyph.FORWARD_10
    val size = if (seeking) diameter * 16 / 18 else diameter
    save()
    translate(59f - size / 2, y - size / 2)
    scale(size / 24, size / 24)
    save()
    if (glyph == PlayerGlyph.PREVIOUS || glyph == PlayerGlyph.BACK_10) {
        translate(24f, 0f); scale(-1f, 1f)
    }
    val p = Paint().apply { color = ink; strokeCap = StrokeCap.Round; strokeJoin = StrokeJoin.Round }
    if (glyph is OneNoteLegend) {
        noteSymbol(glyph, ink, p)
    } else for (mark in symbols.getValue(glyph)) {
        p.style = if (mark.stroke > 0) PaintingStyle.Stroke else PaintingStyle.Fill
        p.strokeWidth = mark.stroke
        drawPath(mark.path, p)
    }

    restore()
    if (seeking) {
        p.style = PaintingStyle.Stroke; p.strokeWidth = 1.25f
        drawLine(Offset(9f, 11f), Offset(10.3f, 10f), p)
        drawLine(Offset(10.3f, 10f), Offset(10.3f, 15.7f), p)
        drawRoundRect(12.6f, 10f, 16f, 15.7f, 1.7f, 1.7f, p)
    }
    restore()
}

private val penLine = Mark("M4,21Q9,17 13,20T21,18.5")
private val markerLine = Mark("M2,20Q7,17 12,19T22,18")

private fun Canvas.noteSymbol(glyph: OneNoteLegend, outline: Color, p: Paint) {
    val width = (glyph.widthPercent ?: 0f) / 100f
    val swatch = glyph.mark == OneNoteMark.SWATCH
    val pen = glyph.mark == OneNoteMark.PEN
    val marker = glyph.mark == OneNoteMark.HIGHLIGHTER
    val ink = glyph.ink?.let { Color(it) }
    if (swatch || (pen || marker) && ink == null) {
        val left = 2f; val right = 22f
        val top = if (swatch) 5f else 18f
        val bottom = if (swatch) 19f else 23f
        p.style = PaintingStyle.Fill; p.color = ink ?: Color.White
        drawRect(left, top, right, bottom, p)
        if (ink == null) {
            save(); clipRect(left, top, right, bottom)
            p.color = Color.Black; p.style = PaintingStyle.Stroke; p.strokeWidth = 2.5f
            for (x in -12..24 step 5) drawLine(Offset(x.toFloat(), bottom), Offset(x + bottom - top, top), p)
            restore()
        }
        p.color = outline; p.style = PaintingStyle.Stroke; p.strokeWidth = 1.1f
        drawRect(left, top, right, bottom, p)
    } else if ((pen || marker) && ink != null) {
        val path = if (pen) penLine.path else markerLine.path
        val weight = if (pen) 1.4f + width * 2.3f else 3.8f + width * 2.8f
        p.style = PaintingStyle.Stroke; p.strokeCap = if (pen) StrokeCap.Round else StrokeCap.Butt
        // Isolate dark ink on dark keys and pale ink on light keys.
        if ((outline == Color.White) == (ink.luminance() < .3f)) {
            p.color = outline; p.strokeWidth = weight + 1.4f; drawPath(path, p)
        }
        p.color = if (marker) ink.copy(alpha = .78f) else ink
        p.strokeWidth = weight; drawPath(path, p); p.strokeCap = StrokeCap.Round
    }
    noteSymbols[glyph.mark]?.let {
        p.style = PaintingStyle.Stroke; p.color = outline; p.strokeWidth = it.stroke
        drawPath(it.path, p)
    }
}
