package tel.misa.mxpocket.decks

import tel.misa.mxpocket.i18n.Copy
import tel.misa.mxpocket.i18n.KeypadCopy as K

/** Persist IDs, never enum ordinals. A favorite is an Android flattened ComponentName, not an intent URI. */
enum class DeckCommand(val id: String, private val copy: Copy, val deck: String) {
    OPEN("launcher.open", K.OpenFavorite, "launcher"), EDIT("launcher.edit", K.EditFavorites, "launcher"),
    PLAY("media.play-pause", K.PlayPause, "audio"), PREVIOUS("media.previous", K.Previous, "audio"),
    NEXT("media.next", K.Next, "audio"), LOUDER("media.volume-up", K.VolumeUp, "audio"),
    QUIETER("media.volume-down", K.VolumeDown, "audio"), MUTE("media.mute", K.Mute, "audio"),
    UNMUTE("media.unmute", K.Unmute, "audio"), MEDIA_ACCESS("media.access", K.MediaAccess, "audio"),
    BACK_10("media.seek-back-10", K.SeekBack, "audio"),
    FORWARD_10("media.seek-forward-10", K.SeekForward, "audio"),
    VOLUME_LEVEL("media.volume-level", K.VolumeLevel, "audio"),
    STUDIO("studio.open", K.OpenCamera, "studio"), VIDEO("studio.open-video", K.OpenVideo, "studio"),
    SHUTTER("studio.camera-shutter", K.Shutter, "studio"), START_VIDEO("studio.camera-record", K.Record, "studio"),
    STOP_VIDEO("studio.camera-stop", K.Stop, "studio"), CAMERA_ACCESS("studio.access", K.CameraControls, "studio"),
    PHOTO("studio.photo", K.Shutter, "studio"),
    RECORD("studio.record", K.Record, "studio"), STOP("studio.stop", K.Stop, "studio"),
    TIMER("studio.timer", K.PhotoTimer, "studio"), ZOOM_IN("studio.zoom-in", K.ZoomIn, "studio"),
    ZOOM_OUT("studio.zoom-out", K.ZoomOut, "studio"), FOCUS("studio.focus", K.CameraFocus, "studio"),
    TORCH("studio.torch", K.Torch, "studio"), MARKER("studio.marker", K.Marker, "studio"),
    BACK("navigation.back", K.Back, "navigation"), HOME("navigation.home", K.Home, "navigation"),
    RECENTS("navigation.recents", K.Recents, "navigation"), UP("navigation.scroll-up", K.ScrollUp, "navigation"),
    DOWN("navigation.scroll-down", K.ScrollDown, "navigation"),
    FORWARD("navigation.focus-next", K.NextFocus, "navigation"),
    REVERSE("navigation.focus-previous", K.PreviousFocus, "navigation"),
    DIRECTION_UP("navigation.direction-up", K.DirectionUp, "navigation"),
    DIRECTION_DOWN("navigation.direction-down", K.DirectionDown, "navigation"),
    DIRECTION_LEFT("navigation.direction-left", K.DirectionLeft, "navigation"),
    DIRECTION_RIGHT("navigation.direction-right", K.DirectionRight, "navigation"),
    ACTIVATE("navigation.activate", K.Select, "navigation"), NAV_ACCESS("navigation.access", K.NavAccess, "navigation"),
    NOTIFICATIONS("navigation.notifications", K.Notifications, "navigation"),
    ALL_APPS("navigation.all-apps", K.AllApps, "navigation"),
    LONG_PRESS("navigation.long-press", K.LongPress, "navigation"),
    TALKBACK_SETTINGS("navigation.talkback-settings", K.TalkBackSettings, "navigation"),
    CAPTION_SETTINGS("navigation.caption-settings", K.CaptionSettings, "navigation"),
    NIGHT_LIGHT_SETTINGS("navigation.night-light-settings", K.NightLightSettings, "navigation"),
    ACCESSIBILITY_SHORTCUT("navigation.accessibility-shortcut", K.SystemShortcut, "navigation"),
    TEAMS_OPEN("teams.open", K.OpenTeams, "teams"), TEAMS_ACCESS("teams.access", K.TeamsSetup, "teams"),
    TEAMS_MIC("teams.mic", K.MeetingMic, "teams"), TEAMS_HAND("teams.hand", K.RaiseHand, "teams"),
    TEAMS_CAPTIONS("teams.captions", K.Captions, "teams"), TEAMS_CHAT("teams.chat", K.MeetingChat, "teams"),
    TEAMS_QUIETER("teams.volume-down", K.CallDown, "teams"), TEAMS_LOUDER("teams.volume-up", K.CallUp, "teams"),
    TEAMS_STATUS("teams.status", K.CallStatus, "teams");

    // The original label is also a legacy Studio binding guard. Never localize it in place.
    val label get() = copy.en
    val displayLabel get() = copy.text()
    val accessibilityPanel get() = this == TALKBACK_SETTINGS || this == CAPTION_SETTINGS || this == NIGHT_LIGHT_SETTINGS
    val accessibilityKey get() = accessibilityPanel || this == ACCESSIBILITY_SHORTCUT

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
val navigationCommands = listOf(DeckCommand.BACK, DeckCommand.HOME, DeckCommand.RECENTS, DeckCommand.REVERSE,
    DeckCommand.ACTIVATE, DeckCommand.FORWARD, DeckCommand.UP, DeckCommand.DOWN, DeckCommand.NAV_ACCESS)
