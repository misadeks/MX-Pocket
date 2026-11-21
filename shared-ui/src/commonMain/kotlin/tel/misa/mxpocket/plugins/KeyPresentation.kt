package tel.misa.mxpocket.plugins

import tel.misa.mxpocket.FocusTimer
import tel.misa.mxpocket.deck.DeckKey

data class KeyTile(val label: String, val footer: String, val background: Int,
    val iconComponent: String? = null, val inputToken: String? = null,
    val icon: String = "", val enabled: Boolean = true)

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
        background = if (!available) 0xff494c51.toInt() else if (color == DeckKey().color) native?.background ?: color else color,
        icon = icon.ifBlank { native?.icon.orEmpty() }, enabled = available)
}
