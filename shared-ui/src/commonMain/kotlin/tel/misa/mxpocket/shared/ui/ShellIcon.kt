package tel.misa.mxpocket.shared.ui

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.*
import androidx.compose.ui.graphics.drawscope.*
import androidx.compose.ui.graphics.vector.PathParser
import androidx.compose.ui.unit.Dp

/** The workspace artwork is separate from the physical key legends. */
internal enum class ShellIcon(data: String) {
    BOLT("M13,2L4,14H12L11,22L20,10H12Z"),
    CHECK("M5,13L9,17L19,7"),
    CHEVRON_DOWN("M6,9L12,15L18,9"),
    CHEVRON_LEFT("M15,6L9,12L15,18"),
    CHEVRON_RIGHT("M9,6L15,12L9,18"),
    CLOCK("M3,12a9,9 0 1,0 18,0a9,9 0 1,0 -18,0 M12,7V12L15,15"),
    COPY("M8,8H20V20H8Z M16,8V4H4V16H8"),
    DELETE("M4,7H20 M9,7V4H15V7 M6,7L7,21H17L18,7 M10,11V17 M14,11V17"),
    DOCUMENT("M6,2H14L19,7V22H6Z M14,2V7H19 M9,13H16 M9,17H16"),
    DOT("M12,12H12.01"),
    DOWNLOAD("M12,4V15 M7,10L12,15L17,10 M5,20H19"),
    EDIT("M4,20H8L19,9L15,5L4,16Z M13,7L17,11"),
    EYE("M2,12C5,6 19,6 22,12C19,18 5,18 2,12Z M9,12a3,3 0 1,0 6,0a3,3 0 1,0 -6,0"),
    FLOWS("M4,6a2,2 0 1,0 4,0a2,2 0 1,0 -4,0 M4,18a2,2 0 1,0 4,0a2,2 0 1,0 -4,0 M16,12a2,2 0 1,0 4,0a2,2 0 1,0 -4,0 M8,6H10A4,4 0 0 1 14,10V12 M8,18H10A4,4 0 0 0 14,14V12 M14,12H16"),
    HISTORY("M3,12A9,9 0 1 0 6,5.3 M3,4V9H8 M12,7V12L15,14"),
    HOME("M4,11L12,4L20,11 M6,9.5V20H18V9.5 M10,20V14H14V20"),
    IMAGE("M4,4H20V20H4Z M4,16L9,11L14,16 M13,15L16,12L20,16 M15,8.5a1.5,1.5 0 1,0 3,0a1.5,1.5 0 1,0 -3,0"),
    INFO("M3,12a9,9 0 1,0 18,0a9,9 0 1,0 -18,0 M12,11V16 M12,8H12.01"),
    KEYBOARD("M3,6H21V18H3Z M7,10H7.01 M11,10H11.01 M15,10H15.01 M7,14H17"),
    KEYPAD("M4,4h4v4h-4z M10,4h4v4h-4z M16,4h4v4h-4z M4,10h4v4h-4z M10,10h4v4h-4z M16,10h4v4h-4z M4,16h4v4h-4z M10,16h4v4h-4z M16,16h4v4h-4z"),
    LAUNCHER("M4,4h7v7h-7z M13,4h7v7h-7z M4,13h7v7h-7z M13,13h7v7h-7z"),
    LAYERS("M12,3L21,8L12,13L3,8Z M3,13L12,18L21,13"),
    LINK("M10,14L14,10 M8,12L6,14A3,3 0 0 0 10,18L12,16 M12,8L14,6A3,3 0 0 1 18,10L16,12"),
    LIST("M8,6H20 M8,12H20 M8,18H20 M4,6H4.01 M4,12H4.01 M4,18H4.01"),
    MONITOR("M3,4H21V16H3Z M8,20H16 M12,16V20"),
    MUSIC("M9,18V5L20,3V16 M3,18a3,3 0 1,0 6,0a3,3 0 1,0 -6,0 M14,16a3,3 0 1,0 6,0a3,3 0 1,0 -6,0"),
    NAVIGATION("M3,11L21,3L13,21L11,13Z"),
    PHONE("M8,2H16A2,2 0 0 1 18,4V20A2,2 0 0 1 16,22H8A2,2 0 0 1 6,20V4A2,2 0 0 1 8,2Z M11,18H13"),
    PIN("M9,3H15 M10,3V9L7,13H17L14,9V3 M12,13V21"),
    PLAY("M8,5V19L19,12Z"),
    PLUGINS("M9,3V7 M15,3V7 M6,7H18V11A6,6 0 0 1 6,11Z M12,17V21"),
    PLUS("M12,5V19 M5,12H19"),
    REFRESH("M20,12A8,8 0 1 1 17.7,6.3 M20,4V9H15"),
    SETTINGS("M9,12a3,3 0 1,0 6,0a3,3 0 1,0 -6,0 M12,2V5 M12,19V22 M2,12H5 M19,12H22 M4.9,4.9L7,7 M17,17L19.1,19.1 M4.9,19.1L7,17 M17,7L19.1,4.9"),
    STAR("M12,3L14.8,8.7L21,9.6L16.5,14L17.6,20.2L12,17.3L6.4,20.2L7.5,14L3,9.6L9.2,8.7Z"),
    STUDIO("M3,8H7L9,5H15L17,8H21V19H3Z M8.5,13a3.5,3.5 0 1,0 7,0a3.5,3.5 0 1,0 -7,0"),
    SWAP("M7,7H20 M16,3L20,7L16,11 M17,17H4 M8,13L4,17L8,21"),
    UPLOAD("M12,15V4 M7,9L12,4L17,9 M5,20H19"),
    WARNING("M12,3L22,20H2Z M12,9V14 M12,17H12.01");
    val path by lazy { PathParser().parsePathString(data).toPath() }
}

@Composable internal fun ShellSymbol(icon: ShellIcon, color: Color, side: Dp) {
    Canvas(Modifier.size(side)) {
        scale(size.width / 24, size.height / 24, Offset.Zero) {
            drawPath(icon.path, color, style = Stroke(2f, cap = StrokeCap.Round, join = StrokeJoin.Round))
        }
    }
}
