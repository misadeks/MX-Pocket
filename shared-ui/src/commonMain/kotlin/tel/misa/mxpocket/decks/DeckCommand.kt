package tel.misa.mxpocket.decks

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
    FORWARD("navigation.focus-next", "Next focus", "navigation"),
    REVERSE("navigation.focus-previous", "Previous focus", "navigation"),
    ACTIVATE("navigation.activate", "Select", "navigation"), NAV_ACCESS("navigation.access", "Navigation access", "navigation"),
    TEAMS_OPEN("teams.open", "Open Teams", "teams"), TEAMS_ACCESS("teams.access", "Teams setup", "teams"),
    TEAMS_MIC("teams.mic", "Meeting mic", "teams"), TEAMS_HAND("teams.hand", "Raise hand", "teams"),
    TEAMS_CAPTIONS("teams.captions", "Captions", "teams"), TEAMS_CHAT("teams.chat", "Meeting chat", "teams"),
    TEAMS_QUIETER("teams.volume-down", "Call volume -", "teams"), TEAMS_LOUDER("teams.volume-up", "Call volume +", "teams"),
    TEAMS_STATUS("teams.status", "Call status", "teams");

    val nativeControl get() = this == SHUTTER || this == START_VIDEO || this == STOP_VIDEO
    val legacyCapture get() = deck == "studio" && !nativeControl && this != STUDIO && this != VIDEO && this != CAMERA_ACCESS
    val workflowSafe get() = deck == "audio" && this != MEDIA_ACCESS && this != VOLUME_LEVEL
    val lockedMediaKey get() = when (this) {
        PLAY, PREVIOUS, NEXT, QUIETER, LOUDER, MUTE, UNMUTE -> true
        else -> false
    }

    companion object {
        private val byId = entries.associateBy { it.id }
        fun fromId(id: String) = byId[id]
    }
}

/** Stable plugin indices, independent of the physical order on the factory Navigation page. */
val navigationCommands = listOf(DeckCommand.BACK, DeckCommand.HOME, DeckCommand.RECENTS, DeckCommand.UP,
    DeckCommand.ACTIVATE, DeckCommand.DOWN, DeckCommand.REVERSE, DeckCommand.NAV_ACCESS, DeckCommand.FORWARD)
