package tel.misa.mxpocket.decks

import tel.misa.mxpocket.plugins.KeyTile
import tel.misa.mxpocket.plugins.navigationFace
import tel.misa.mxpocket.plugins.legend
import tel.misa.mxpocket.i18n.KeypadCopy as K

internal const val AUDIO_BACKGROUND = 0xff303438

data class DeckResult(val success: Boolean, val message: String)
internal fun ok(message: String) = DeckResult(true, message)
internal fun unavailable(message: String) = DeckResult(false, message)

data class Favorite(val component: String, val label: String)
data class MediaDeckState(
    val access: Boolean = false, val status: String = "Media keys", val title: String = "",
    val playing: Boolean? = null, val supported: Set<DeckCommand> = emptySet(),
    val volume: Int = 0, val maximum: Int = 0,
    val artwork: android.graphics.Bitmap? = null, val fixedVolume: Boolean = false,
    val awaitingCover: Boolean = false, val muted: Boolean = false,
) {
    // The observer supplies finite playback statuses, distinct from the player's arbitrary track title.
    val displayStatus get() = when (status) {
        "Media keys" -> K.MediaKeys
        "No session" -> K.NoSession
        "Playing" -> K.Playing
        "Paused" -> K.Paused
        "Stopped" -> K.Stopped
        "Loading" -> K.Loading
        "Player error" -> K.PlayerError
        "State unknown" -> K.StateUnknown
        "Access unavailable" -> K.AccessUnavailable
        else -> null
    }?.text() ?: status
}
data class DeckState(
    val favorites: List<Favorite> = emptyList(), val media: MediaDeckState = MediaDeckState(),
    val cameraControls: Boolean = false, val navigationEnabled: Boolean = false,
    val teams: TeamsState = TeamsState(), val shortcutAvailable: Boolean = false,
) {
    val cameraStatus get() = when {
        !cameraControls -> K.ControlsOff
        !navigationEnabled -> K.AccessOff
        else -> K.PhysicalKey
    }.text()
    /** Same logical tile for built-in and custom profile keys; no reads from Android during paint. */
    fun tile(command: DeckCommand, component: String? = null): KeyTile {
        if (command.deck == "navigation") return command.navigationFace(navigationEnabled, shortcutAvailable,
            allAppsSupported = android.os.Build.VERSION.SDK_INT >= 31)
        if (command.deck == TEAMS) return teams.tile(command)
        val favorite = if (command == DeckCommand.OPEN) favorites.find { it.component == component } else null
        val label = when (command) {
            DeckCommand.OPEN -> favorite?.label ?: K.MissingApp.text()
            DeckCommand.PLAY -> when (media.playing) { true -> K.Pause.text(); false -> K.Play.text(); null -> command.displayLabel }
            DeckCommand.VOLUME_LEVEL -> "${media.volume} / ${media.maximum}"
            else -> command.displayLabel
        }
        val footer = when (command) {
            DeckCommand.LOUDER, DeckCommand.QUIETER, DeckCommand.MUTE, DeckCommand.UNMUTE -> "${media.volume} / ${media.maximum}"
            DeckCommand.PLAY -> if (media.access && command !in media.supported) media.displayStatus else media.title.ifBlank { media.displayStatus }
            DeckCommand.PREVIOUS, DeckCommand.NEXT -> if (media.access && command !in media.supported) K.Unavailable.text() else media.displayStatus
            DeckCommand.MEDIA_ACCESS -> (if (media.access) K.ManageAccess else K.OptionalAccess).text()
            DeckCommand.VOLUME_LEVEL -> K.Volume.text()
            DeckCommand.STUDIO -> K.PhotoMode.text()
            DeckCommand.VIDEO -> K.VideoMode.text()
            DeckCommand.CAMERA_ACCESS -> K.OnPhone.text()
            else -> when (command.deck) {
                "studio" -> if (command.legacyCapture) K.Unavailable.text() else cameraStatus
                "audio" -> media.displayStatus
                else -> K.Favorites.text()
            }
        }
        val color = when (command.deck) {
            "launcher" -> 0xff384674
            "studio" -> 0xff49345f
            "audio" -> AUDIO_BACKGROUND
            else -> 0xff254c69
        }.toInt()
        val available = when (command) {
            DeckCommand.PLAY, DeckCommand.PREVIOUS, DeckCommand.NEXT -> !media.access || command in media.supported
            DeckCommand.BACK_10, DeckCommand.FORWARD_10 -> media.access && command in media.supported
            DeckCommand.LOUDER, DeckCommand.QUIETER, DeckCommand.MUTE, DeckCommand.UNMUTE -> !media.fixedVolume
            else -> !command.legacyCapture
        }
        return KeyTile(label, footer, color, favorite?.component, command.id + (component ?: ""), enabled = available,
            readOnly = command == DeckCommand.VOLUME_LEVEL, glyph = command.legend())
    }

}

internal fun DeckState.pluginDetails(id: String): String {
    val details = when (id) {
        "launcher" -> K.LauncherDetails.text(favorites.size)
        "studio" -> K.StudioDetails.text(cameraStatus)
        "audio" -> if (media.access) K.MediaAccessOn.text(media.displayStatus) else K.MediaAccessOff.text()
        "navigation" -> (if (navigationEnabled) K.NavEnabled else K.NavBasic).text()
        "teams" -> K.TeamsDetails.text()
        "game.xo" -> K.GameDetails.text()
        "focus" -> K.FocusDetails.text()
        "phone" -> K.PhoneDetails.text()
        else -> return ""
    }
    val commands = DeckCommand.entries.filter { it.deck == id && !it.legacyCapture }.joinToString { it.displayLabel }
    return listOf(details, commands).filter(String::isNotBlank).joinToString("\n")
}
