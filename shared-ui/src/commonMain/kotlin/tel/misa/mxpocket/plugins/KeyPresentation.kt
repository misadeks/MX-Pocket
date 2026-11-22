package tel.misa.mxpocket.plugins

import tel.misa.mxpocket.FocusTimer
import tel.misa.mxpocket.deck.DeckKey
import tel.misa.mxpocket.deck.DeckActions
import tel.misa.mxpocket.decks.DeckCommand
import tel.misa.mxpocket.decks.navigationCommands

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
    val primary: Boolean = false, val volumeLevel: VolumeLevel? = null, val quietFace: Boolean = false,
    val playerFace: Boolean = false, val levelFraction: Float? = null)

data class PluginFrame(val keys: List<KeyTile>, val page: Int = 0) {
    init { require(keys.size == 9) { "A plugin frame must contain exactly nine keys" } }
}

fun frame(labels: List<String>, footer: String, background: Int): PluginFrame =
    PluginFrame(labels.map { KeyTile(it, footer, background) })

fun FocusTimer.present(now: Long): PluginFrame {
    val labels = listOf(if (running) "Pause" else "Start", label(now), "Reset", "-5 min", "${durationMillis / 60_000} min",
        "+5 min", "15 min", "25 min", "50 min")
    val glyphs = listOf(if (running) PlayerGlyph.PAUSE else PlayerGlyph.PLAY, null, DeckGlyph.RESET,
        DeckGlyph.MINUS, null, DeckGlyph.PLUS, DeckGlyph.TIMER, DeckGlyph.TIMER, DeckGlyph.TIMER)
    return PluginFrame(labels.mapIndexed { i, text ->
        val readout = i == 1 || i == 4
        KeyTile(text, when (i) { 1 -> "Remaining"; 4 -> "Duration"; else -> "Focus" },
            (if (readout) 0xff233448 else 0xff194375).toInt(), glyph = glyphs[i], readOnly = readout,
            showFooter = readout)
    })
}

fun phoneFrame(): PluginFrame {
    val glyphs = listOf(DeckGlyph.CAMERA, DeckGlyph.SETTINGS, DeckGlyph.PHONE, DeckGlyph.WEB, null,
        DeckGlyph.TIMER, DeckGlyph.TIMER, PlayerGlyph.TRACK, DeckGlyph.REFRESH)
    return PluginFrame(listOf("Camera", "Settings", "Dialer", "Web", "Phone", "Alarm", "Focus", "Audio", "Repaint")
        .mapIndexed { i, text -> KeyTile(text, "Phone", 0xff49345f.toInt(), glyph = glyphs[i],
            readOnly = i == 4, showFooter = false) })
}

fun DeckKey.bindingGlyph(): KeyGlyph? = when (action?.id) {
    DeckActions.PAGE -> DeckGlyph.FOLDER
    DeckActions.PROFILE -> when (action.params[DeckActions.PROFILE_ID]) {
        "audio" -> PlayerGlyph.TRACK
        "focus" -> DeckGlyph.TIMER
        "phone" -> DeckGlyph.PHONE
        "studio" -> DeckGlyph.CAMERA
        "navigation" -> DeckGlyph.COMPASS
        "launcher" -> DeckGlyph.GRID
        "game.xo" -> DeckGlyph.GAME
        else -> DeckGlyph.PROFILE
    }
    DeckActions.BACK -> NavigationGlyph.BACK
    DeckActions.ROOT -> NavigationGlyph.HOME
    "workflow.run" -> DeckGlyph.FLOW
    else -> action?.id?.let(DeckCommand::fromId)?.legend()
}

fun DeckCommand.legend(): KeyGlyph? = when (this) {
    DeckCommand.STUDIO, DeckCommand.SHUTTER, DeckCommand.PHOTO -> DeckGlyph.CAMERA
    DeckCommand.VIDEO, DeckCommand.START_VIDEO -> DeckGlyph.VIDEO
    DeckCommand.STOP_VIDEO -> DeckGlyph.STOP
    DeckCommand.EDIT, DeckCommand.CAMERA_ACCESS, DeckCommand.MEDIA_ACCESS -> DeckGlyph.SETTINGS
    DeckCommand.PLAY -> PlayerGlyph.PLAY_PAUSE
    DeckCommand.QUIETER -> PlayerGlyph.VOLUME_DOWN
    DeckCommand.LOUDER -> PlayerGlyph.VOLUME_UP
    DeckCommand.MUTE -> PlayerGlyph.MUTE
    DeckCommand.UNMUTE -> PlayerGlyph.UNMUTE
    DeckCommand.PREVIOUS -> PlayerGlyph.PREVIOUS
    DeckCommand.NEXT -> PlayerGlyph.NEXT
    DeckCommand.BACK_10 -> PlayerGlyph.BACK_10
    DeckCommand.FORWARD_10 -> PlayerGlyph.FORWARD_10
    DeckCommand.BACK -> NavigationGlyph.BACK
    DeckCommand.HOME -> NavigationGlyph.HOME
    DeckCommand.RECENTS -> NavigationGlyph.RECENTS
    DeckCommand.UP -> NavigationGlyph.UP
    DeckCommand.DOWN -> NavigationGlyph.DOWN
    DeckCommand.REVERSE -> NavigationGlyph.PREVIOUS_FOCUS
    DeckCommand.FORWARD -> NavigationGlyph.NEXT_FOCUS
    DeckCommand.ACTIVATE -> NavigationGlyph.SELECT
    DeckCommand.NAV_ACCESS -> NavigationGlyph.ACCESS_INFO
    else -> null
}

/** Inactive editor pages identify bindings, not a fabricated player, timer or access state. */
fun DeckKey.bindingPreview(): KeyTile? {
    val call = action ?: return null
    val slot = call.params["index"]?.toIntOrNull()
    val command = if (call.id == "plugin.key" && slot != null) when (call.params["pluginId"]) {
        "phone" -> return phoneFrame().keys.getOrNull(slot)
        "focus" -> {
            val face = FocusTimer().present(0).keys.getOrNull(slot) ?: return null
            return when (slot) {
                0 -> face.copy(label = "Start / pause", glyph = PlayerGlyph.PLAY_PAUSE)
                1, 4 -> face.copy(label = face.footer, footer = "")
                else -> face
            }
        }
        "navigation" -> navigationCommands.getOrNull(slot)
        "audio" -> {
            if (slot == 7) return KeyTile("Track", "", 0xff303438.toInt(), glyph = PlayerGlyph.TRACK,
                readOnly = true, playerFace = true, showFooter = false)
            audioSlots.getOrNull(slot)
        }
        else -> null
    } else DeckCommand.fromId(call.id)
    command ?: return null
    if (command.deck == "navigation") return command.navigationFace(null).copy(enabled = true, readOnly = false,
        footer = "", showFooter = false)
    val transport = command.legend() is PlayerGlyph
    return KeyTile(command.label, "", if (transport) 0xff303438.toInt() else color, glyph = command.legend(),
        playerFace = transport, showLabel = !transport, showFooter = false)
}

val audioSlots = listOf(DeckCommand.QUIETER, DeckCommand.MUTE, DeckCommand.LOUDER, DeckCommand.PREVIOUS,
    DeckCommand.PLAY, DeckCommand.NEXT, DeckCommand.BACK_10, null, DeckCommand.FORWARD_10)

/** Identical override order for the LCD, phone mirror and offline desktop host. */
fun DeckKey.present(native: KeyTile?, fallbackLabel: String, footer: String, available: Boolean): KeyTile {
    val title = label.ifBlank { native?.label ?: fallbackLabel }
    return (native ?: KeyTile(title, footer, color)).copy(label = title, footer = footer,
        glyph = native?.glyph ?: bindingGlyph(), quietFace = native?.quietFace == true || action == null && label.isBlank(),
        showLabel = label.isNotBlank() || (action != null || native != null) && native?.showLabel != false,
        // Hide repeated deck names, not an explicit status update for this physical key.
        showFooter = native?.showFooter != false || !native.playerFace && footer.isNotBlank() && footer != native.footer,
        background = if (!available) 0xff494c51.toInt() else if (color == DeckKey().color) native?.background ?: color else color,
        icon = icon.ifBlank { native?.icon.orEmpty() }, enabled = available)
}
