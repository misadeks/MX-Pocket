package tel.misa.mxpocket.plugins

import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.*
import androidx.compose.ui.graphics.vector.PathParser

/** Original 24-unit LCD legends. Physical and simulated keys draw these same paths. */
enum class DeckGlyph : KeyGlyph { FOLDER, PROFILE, TIMER, RESET, MINUS, PLUS, CAMERA, VIDEO, STOP, SETTINGS, PHONE, WEB, REFRESH, FLOW, COMPASS, GRID, GAME }

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
    for (mark in symbols.getValue(glyph)) {
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
