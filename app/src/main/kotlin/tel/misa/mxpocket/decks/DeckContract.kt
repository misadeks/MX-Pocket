package tel.misa.mxpocket.decks

import tel.misa.mxpocket.plugins.KeyTile

internal const val AUDIO_BACKGROUND = 0xff303438

/** Persist IDs, never enum ordinals. A favorite is an Android flattened ComponentName, not an intent URI. */
enum class DeckCommand(val id: String, val label: String, val deck: String) {
    OPEN("launcher.open", "Open favorite", "launcher"), EDIT("launcher.edit", "Edit favorites", "launcher"),
    PLAY("media.play-pause", "Play/Pause", "audio"), PREVIOUS("media.previous", "Previous", "audio"),
    NEXT("media.next", "Next", "audio"), LOUDER("media.volume-up", "Volume +", "audio"),
    QUIETER("media.volume-down", "Volume -", "audio"), MUTE("media.mute", "Mute", "audio"),
    UNMUTE("media.unmute", "Unmute", "audio"), MEDIA_ACCESS("media.access", "Media access", "audio"),
    BACK_10("media.seek-back-10", "Seek back 10 seconds", "audio"),
    FORWARD_10("media.seek-forward-10", "Seek forward 10 seconds", "audio"),
    VOLUME_LEVEL("media.volume-level", "Volume level", "audio"),
    STUDIO("studio.open", "Open Camera", "studio"), VIDEO("studio.open-video", "Open Video", "studio"),
    SHUTTER("studio.camera-shutter", "Shutter", "studio"), START_VIDEO("studio.camera-record", "Record", "studio"),
    STOP_VIDEO("studio.camera-stop", "Stop", "studio"), CAMERA_ACCESS("studio.access", "Camera controls", "studio"),
    PHOTO("studio.photo", "Shutter", "studio"),
    RECORD("studio.record", "Record", "studio"), STOP("studio.stop", "Stop", "studio"),
    TIMER("studio.timer", "Photo timer", "studio"), ZOOM_IN("studio.zoom-in", "Zoom +", "studio"),
    ZOOM_OUT("studio.zoom-out", "Zoom -", "studio"), FOCUS("studio.focus", "Focus", "studio"),
    TORCH("studio.torch", "Torch", "studio"), MARKER("studio.marker", "Marker", "studio"),
    BACK("navigation.back", "Back", "navigation"), HOME("navigation.home", "Home", "navigation"),
    RECENTS("navigation.recents", "Recents", "navigation"), UP("navigation.scroll-up", "Scroll up", "navigation"),
    DOWN("navigation.scroll-down", "Scroll down", "navigation"),
    FORWARD("navigation.focus-next", "Next item", "navigation"),
    REVERSE("navigation.focus-previous", "Prior item", "navigation"),
    ACTIVATE("navigation.activate", "Activate", "navigation"), NAV_ACCESS("navigation.access", "Navigation access", "navigation");

    val workflowSafe get() = deck == "audio" && this != MEDIA_ACCESS && this != VOLUME_LEVEL
    val lockedMediaKey get() = when (this) {
        PLAY, PREVIOUS, NEXT, QUIETER, LOUDER, MUTE, UNMUTE -> true
        else -> false
    }
    val nativeControl get() = this == SHUTTER || this == START_VIDEO || this == STOP_VIDEO
    val legacyCapture get() = deck == "studio" && !nativeControl && this != STUDIO && this != VIDEO && this != CAMERA_ACCESS
    companion object {
        private val byId = entries.associateBy { it.id }
        fun fromId(id: String) = byId[id]
    }
}

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
                "navigation" -> if (navigationEnabled) "System" else "In MX Pocket"
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
            readOnly = command == DeckCommand.VOLUME_LEVEL)
    }
}
