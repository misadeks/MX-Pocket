package tel.misa.mxpocket.plugins

import tel.misa.mxpocket.FocusTimer
import tel.misa.mxpocket.deck.DeckKey

sealed interface KeyGlyph
enum class PlayerGlyph : KeyGlyph {
    VOLUME_DOWN, MUTE, UNMUTE, VOLUME_UP, PREVIOUS, PLAY, PAUSE, PLAY_PAUSE, NEXT, BACK_10, TRACK, FORWARD_10
}

data class VolumeLevel(val current: Int, val maximum: Int, val muted: Boolean) {
    val fraction: Float? get() = if (maximum <= 0) null else if (muted) 0f else current.coerceIn(0, maximum).toFloat() / maximum
    val description: String get() = if (maximum > 0) "$current / $maximum" else "Volume unknown"
}

data class KeyTile(val label: String, val footer: String, val background: Int,
    val iconComponent: String? = null, val inputToken: String? = null,
    val icon: String = "", val enabled: Boolean = true, val glyph: KeyGlyph? = null,
    val readOnly: Boolean = false, val showLabel: Boolean = true, val showFooter: Boolean = true,
    val primary: Boolean = false, val volumeLevel: VolumeLevel? = null)

data class PluginFrame(val keys: List<KeyTile>, val page: Int = 0) {
    init { require(keys.size == 9) { "A plugin frame must contain exactly nine keys" } }
}

fun frame(labels: List<String>, footer: String, background: Int): PluginFrame =
    PluginFrame(labels.map { KeyTile(it, footer, background) })

fun FocusTimer.present(now: Long) = frame(
    listOf(if (running) "Pause" else "Start", label(now), "Reset", "-5 min", "${durationMillis / 60_000} min",
        "+5 min", "15 min", "25 min", "50 min"), "FOCUS", 0xff194375.toInt())

fun phoneFrame() = frame(listOf("Camera", "Settings", "Dialer", "Web", "Phone", "Alarm", "Focus", "Audio", "Repaint"),
    "PHONE", 0xff49345f.toInt())

/** Identical override order for the LCD, phone mirror and offline desktop host. */
fun DeckKey.present(native: KeyTile?, fallbackLabel: String, footer: String, available: Boolean): KeyTile {
    val title = label.ifBlank { native?.label ?: fallbackLabel }
    return (native ?: KeyTile(title, footer, color)).copy(label = title, footer = footer,
        showLabel = label.isNotBlank() || native?.showLabel != false,
        background = if (!available) 0xff494c51.toInt() else if (color == DeckKey().color) native?.background ?: color else color,
        icon = icon.ifBlank { native?.icon.orEmpty() }, enabled = available)
}
