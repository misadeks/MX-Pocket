package tel.misa.mxpocket.decks

import tel.misa.mxpocket.plugins.KeyTile
import tel.misa.mxpocket.plugins.navigationFace
import tel.misa.mxpocket.plugins.legend

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
)
data class DeckState(
    val favorites: List<Favorite> = emptyList(), val media: MediaDeckState = MediaDeckState(),
    val cameraControls: Boolean = false, val navigationEnabled: Boolean = false,
) {
    val cameraStatus get() = when {
        !cameraControls -> "Controls off"
        !navigationEnabled -> "Access off"
        else -> "Physical key"
    }
    /** Same logical tile for built-in and custom profile keys; no reads from Android during paint. */
    fun tile(command: DeckCommand, component: String? = null): KeyTile {
        if (command.deck == "navigation") return command.navigationFace(navigationEnabled)
        val favorite = if (command == DeckCommand.OPEN) favorites.find { it.component == component } else null
        val label = when (command) {
            DeckCommand.OPEN -> favorite?.label ?: "Missing app"
            DeckCommand.PLAY -> when (media.playing) { true -> "Pause"; false -> "Play"; null -> command.label }
            DeckCommand.VOLUME_LEVEL -> "${media.volume} / ${media.maximum}"
            else -> command.label
        }
        val footer = when (command) {
            DeckCommand.LOUDER, DeckCommand.QUIETER, DeckCommand.MUTE, DeckCommand.UNMUTE -> "${media.volume} / ${media.maximum}"
            DeckCommand.PLAY -> if (media.access && command !in media.supported) media.status else media.title.ifBlank { media.status }
            DeckCommand.PREVIOUS, DeckCommand.NEXT -> if (media.access && command !in media.supported) "Unavailable" else media.status
            DeckCommand.MEDIA_ACCESS -> if (media.access) "Manage access" else "Optional access"
            DeckCommand.VOLUME_LEVEL -> "Volume"
            DeckCommand.STUDIO -> "Photo mode"
            DeckCommand.VIDEO -> "Video mode"
            DeckCommand.CAMERA_ACCESS -> "On phone"
            else -> when (command.deck) {
                "studio" -> if (command.legacyCapture) "Unavailable" else cameraStatus
                "audio" -> media.status
                else -> "Favorites"
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
