package tel.misa.mxpocket.decks

import tel.misa.mxpocket.plugins.KeyTile

/** Persist IDs, never enum ordinals. A favorite is an Android flattened ComponentName, not an intent URI. */
enum class DeckCommand(val id: String, val label: String, val deck: String) {
    OPEN("launcher.open", "Open favorite", "launcher"), EDIT("launcher.edit", "Edit favorites", "launcher"),
    PLAY("media.play-pause", "Play/Pause", "audio"), PREVIOUS("media.previous", "Previous", "audio"),
    NEXT("media.next", "Next", "audio"), LOUDER("media.volume-up", "Volume +", "audio"),
    QUIETER("media.volume-down", "Volume -", "audio"), MUTE("media.mute", "Mute", "audio"),
    UNMUTE("media.unmute", "Unmute", "audio"), MEDIA_ACCESS("media.access", "Media access", "audio"),
    STUDIO("studio.open", "Open Studio", "studio"), PHOTO("studio.photo", "Shutter", "studio"),
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

    val workflowSafe get() = deck == "audio" && this != MEDIA_ACCESS
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
)
data class StudioState(
    val ready: Boolean = false, val recording: Boolean = false, val elapsedSeconds: Long = 0,
    val markers: Int = 0, val zoom: Float = 1f, val torch: Boolean = false,
    val hasTorch: Boolean = false, val timerSeconds: Int = 0, val countdown: Int = 0,
    val status: String = "Open Studio",
) {
    val elapsed get() = "%d:%02d".format(java.util.Locale.ROOT, elapsedSeconds / 60, elapsedSeconds % 60)
}
data class DeckState(
    val favorites: List<Favorite> = emptyList(), val media: MediaDeckState = MediaDeckState(),
    val studio: StudioState = StudioState(), val navigationEnabled: Boolean = false,
) {
    /** Same logical tile for built-in and custom profile keys; no reads from Android during paint. */
    fun tile(command: DeckCommand, component: String? = null): KeyTile {
        val favorite = if (command == DeckCommand.OPEN) favorites.find { it.component == component } else null
        val label = when (command) {
            DeckCommand.OPEN -> favorite?.label ?: "Missing app"
            DeckCommand.PLAY -> when (media.playing) { true -> "Pause"; false -> "Play"; null -> command.label }
            DeckCommand.RECORD -> if (studio.recording) "Recording" else command.label
            DeckCommand.STOP -> if (studio.recording) "Stop ${studio.elapsed}" else command.label
            DeckCommand.PHOTO -> if (studio.countdown > 0) "In ${studio.countdown}s" else command.label
            DeckCommand.TORCH -> if (studio.torch) "Torch on" else command.label
            else -> command.label
        }
        val footer = when (command) {
            DeckCommand.LOUDER, DeckCommand.QUIETER, DeckCommand.MUTE, DeckCommand.UNMUTE -> "${media.volume} / ${media.maximum}"
            DeckCommand.PLAY -> media.title.ifBlank { media.status }
            DeckCommand.MEDIA_ACCESS -> if (media.access) "Manage access" else "Optional access"
            DeckCommand.RECORD, DeckCommand.STOP -> if (studio.recording) "REC ${studio.elapsed}" else studio.status
            DeckCommand.MARKER -> "${studio.markers} marks"
            DeckCommand.TIMER -> if (studio.timerSeconds == 0) "Off" else "${studio.timerSeconds}s"
            DeckCommand.ZOOM_IN, DeckCommand.ZOOM_OUT -> "%.1fx".format(java.util.Locale.ROOT, studio.zoom)
            DeckCommand.TORCH -> if (!studio.hasTorch) "Unavailable" else if (studio.torch) "On" else "Off"
            else -> when (command.deck) {
                "navigation" -> if (navigationEnabled) "System" else "In MX Pocket"
                "studio" -> studio.status
                "audio" -> media.status
                else -> "Favorites"
            }
        }
        val color = when (command.deck) {
            "launcher" -> 0xff384674
            "studio" -> if (studio.recording) 0xff952d3f else 0xff49345f
            "audio" -> if (media.playing == true) 0xff12634e else 0xff274740
            else -> 0xff254c69
        }.toInt()
        return KeyTile(label, footer, color, favorite?.component, command.id + (component ?: ""))
    }
}
