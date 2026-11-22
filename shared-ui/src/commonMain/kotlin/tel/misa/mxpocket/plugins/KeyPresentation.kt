package tel.misa.mxpocket.plugins

import tel.misa.mxpocket.FocusTimer
import tel.misa.mxpocket.deck.DeckKey
import tel.misa.mxpocket.deck.DeckActions
import tel.misa.mxpocket.decks.DeckCommand
import tel.misa.mxpocket.decks.navigationCommands
import tel.misa.mxpocket.i18n.KeypadCopy as K
import tel.misa.mxpocket.shared.ui.PocketStyle

sealed interface KeyGlyph
enum class PlayerGlyph : KeyGlyph {
    VOLUME_DOWN, MUTE, UNMUTE, VOLUME_UP, PREVIOUS, PLAY, PAUSE, PLAY_PAUSE, NEXT, BACK_10, TRACK, FORWARD_10
}

private val KeyGlyph?.isTenSecondSeek get() = this == PlayerGlyph.BACK_10 || this == PlayerGlyph.FORWARD_10

data class VolumeLevel(val current: Int, val maximum: Int, val muted: Boolean) {
    val fraction: Float? get() = if (maximum <= 0) null else if (muted) 0f else current.coerceIn(0, maximum).toFloat() / maximum
    val description: String get() = if (maximum > 0) "$current / $maximum" else K.VolumeUnknown.text()
}

data class KeyTile(val label: String, val footer: String, val background: Int,
    val iconComponent: String? = null, val inputToken: String? = null,
    val icon: String = "", val enabled: Boolean = true, val glyph: KeyGlyph? = null,
    val readOnly: Boolean = false, val showLabel: Boolean = true, val showFooter: Boolean = true,
    val primary: Boolean = false, val volumeLevel: VolumeLevel? = null, val quietFace: Boolean = false,
    val playerFace: Boolean = false, val levelFraction: Float? = null, val selection: KeySelection? = null,
    val detail: String = "") {
    val backgroundOnly get() = !enabled && glyph.isTenSecondSeek
}

data class PluginFrame(val keys: List<KeyTile>, val page: Int = 0) {
    init { require(keys.size == 9) { "A plugin frame must contain exactly nine keys" } }
}

fun frame(labels: List<String>, footer: String, background: Int): PluginFrame =
    PluginFrame(labels.map { KeyTile(it, footer, background) })

fun FocusTimer.present(now: Long): PluginFrame {
    val labels = listOf((if (running) K.Pause else K.Start).text(), label(now), K.Reset.text(), K.Minutes.text("-5"),
        K.Minutes.text(durationMillis / 60_000), K.Minutes.text("+5"), K.Minutes.text(15), K.Minutes.text(25), K.Minutes.text(50))
    val glyphs = listOf(if (running) PlayerGlyph.PAUSE else PlayerGlyph.PLAY, null, DeckGlyph.RESET,
        DeckGlyph.MINUS, null, DeckGlyph.PLUS, DeckGlyph.TIMER, DeckGlyph.TIMER, DeckGlyph.TIMER)
    return PluginFrame(labels.mapIndexed { i, text ->
        val readout = i == 1 || i == 4
        KeyTile(text, when (i) { 1 -> K.Remaining; 4 -> K.Duration; else -> K.Focus }.text(),
            (if (readout) 0xff233448 else 0xff194375).toInt(), glyph = glyphs[i], readOnly = readout,
            showFooter = readout)
    })
}

private fun DeckKey.launcherIdentity() = when (action?.id) {
    DeckActions.PROFILE -> PocketStyle.accent(action.params[DeckActions.PROFILE_ID].orEmpty())
    "phone.open" -> if (action.params["app"] == "ONENOTE") PocketStyle.accent("onenote") else null
    else -> null
}

fun DeckKey.bindingGlyph(): KeyGlyph? = launcherIdentity()?.keyGlyph ?: when (action?.id) {
    DeckActions.PAGE -> DeckGlyph.FOLDER
    DeckActions.BACK -> NavigationGlyph.BACK
    DeckActions.ROOT -> NavigationGlyph.HOME
    "workflow.run" -> DeckGlyph.FLOW
    else -> action?.id?.let(DeckCommand::fromId)?.legend()
}

fun DeckCommand.legend(): KeyGlyph? = when (this) {
    DeckCommand.TEAMS_OPEN -> IntegrationApp.TEAMS
    DeckCommand.STUDIO, DeckCommand.SHUTTER, DeckCommand.PHOTO -> DeckGlyph.CAMERA
    DeckCommand.VIDEO, DeckCommand.START_VIDEO -> DeckGlyph.VIDEO
    DeckCommand.STOP_VIDEO -> DeckGlyph.STOP
    DeckCommand.EDIT, DeckCommand.CAMERA_ACCESS, DeckCommand.MEDIA_ACCESS, DeckCommand.TALKBACK_SETTINGS,
    DeckCommand.CAPTION_SETTINGS, DeckCommand.NIGHT_LIGHT_SETTINGS -> DeckGlyph.SETTINGS
    DeckCommand.ACCESSIBILITY_SHORTCUT -> NavigationGlyph.ACCESS_INFO
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
    DeckCommand.DIRECTION_UP -> NavigationGlyph.DIRECTION_UP
    DeckCommand.DIRECTION_DOWN -> NavigationGlyph.DIRECTION_DOWN
    DeckCommand.DIRECTION_LEFT -> NavigationGlyph.DIRECTION_LEFT
    DeckCommand.DIRECTION_RIGHT -> NavigationGlyph.DIRECTION_RIGHT
    DeckCommand.NOTIFICATIONS -> DeckGlyph.FOLDER
    DeckCommand.ALL_APPS -> DeckGlyph.GRID
    DeckCommand.LONG_PRESS -> NavigationGlyph.SELECT
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
                0 -> face.copy(label = K.StartPause.text(), glyph = PlayerGlyph.PLAY_PAUSE)
                1, 4 -> face.copy(label = face.footer, footer = "")
                else -> face
            }
        }
        "navigation" -> navigationCommands.getOrNull(slot)
        "studio" -> studioSlots.getOrNull(slot)
        "launcher" -> when (slot) {
            7 -> DeckCommand.EDIT
            8 -> return KeyTile(K.Favorites.text(), "", color, glyph = DeckGlyph.FOLDER)
            else -> null // The favorite assigned to a slot is only known on the device.
        }
        "teams" -> tel.misa.mxpocket.decks.teamsSlots.getOrNull(slot)
        "audio" -> {
            if (slot == 7) return KeyTile(K.Track.text(), "", 0xff303438.toInt(), glyph = PlayerGlyph.TRACK,
                readOnly = true, playerFace = true, showFooter = false)
            audioSlots.getOrNull(slot)
        }
        else -> null
    } else DeckCommand.fromId(call.id)
    command ?: return null
    if (command.deck == "navigation") return command.navigationFace(null).copy(enabled = true, readOnly = false,
        footer = "", showFooter = false)
    val transport = command.legend() is PlayerGlyph
    return KeyTile(command.displayLabel, "", when {
        command == DeckCommand.TEAMS_OPEN -> PocketStyle.accent(command.deck).fill
        transport -> 0xff303438.toInt()
        else -> color
    }, glyph = command.legend(),
        playerFace = transport, showLabel = !transport, showFooter = false)
}

val audioSlots = listOf(DeckCommand.QUIETER, DeckCommand.MUTE, DeckCommand.LOUDER, DeckCommand.PREVIOUS,
    DeckCommand.PLAY, DeckCommand.NEXT, DeckCommand.BACK_10, null, DeckCommand.FORWARD_10)

val studioSlots = listOf(DeckCommand.SHUTTER, DeckCommand.START_VIDEO, DeckCommand.STOP_VIDEO, null, null, null,
    DeckCommand.VIDEO, DeckCommand.STUDIO, DeckCommand.CAMERA_ACCESS)

/** Identical override order for the LCD, phone mirror and offline desktop host. */
fun DeckKey.present(native: KeyTile?, fallbackLabel: String, footer: String, available: Boolean): KeyTile {
    val title = label.ifBlank { native?.label ?: fallbackLabel }
    // Some hosts supply only a label for an unsupported legacy Audio slot.
    val glyph = native?.glyph ?: bindingGlyph() ?: if (!available && action?.params?.get("pluginId") == "audio")
        bindingPreview()?.glyph?.takeIf { it.isTenSecondSeek } else null
    val unavailableNote = !available && action?.id == "plugin.key" && action.params["pluginId"] == "onenote" &&
        native?.enabled != false && (native?.quietFace != true || label.isNotBlank())
    return (native ?: KeyTile(title, footer, color)).copy(label = title, footer = footer,
        glyph = if (unavailableNote) OneNoteLegend(OneNoteMark.UNAVAILABLE) else glyph,
        selection = native?.selection?.takeIf { available }, detail = native?.detail.orEmpty().takeIf { available }.orEmpty(),
        quietFace = native?.quietFace == true || action == null && label.isBlank(),
        showLabel = label.isNotBlank() || (action != null || native != null) && native?.showLabel != false,
        // Hide repeated deck names, not an explicit status update for this physical key.
        showFooter = !(native?.quietFace == true && action?.params?.get("pluginId") == "onenote" && label.isBlank()) &&
            (native?.showFooter != false || !native.playerFace && footer.isNotBlank() && footer != native.footer),
        background = when {
            !available && !glyph.isTenSecondSeek -> 0xff494c51.toInt()
            color != DeckKey().color -> color
            else -> launcherIdentity()?.fill ?: native?.background ?: color
        },
        icon = icon.ifBlank { native?.icon.orEmpty() }, enabled = available)
}
