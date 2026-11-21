package tel.misa.mxpocket.decks

import android.app.NotificationManager
import android.content.ComponentName
import android.content.Context
import android.media.AudioManager
import android.media.MediaMetadata
import android.media.session.MediaController
import android.media.session.MediaSessionManager
import android.media.session.PlaybackState as P
import android.service.notification.NotificationListenerService
import android.view.KeyEvent
import tel.misa.mxpocket.isUnlockedAndInteractive

/** A component token for getActiveSessions. No posted/removed callbacks, notification queries or exports. */
class MediaSessionAccess : NotificationListenerService()

internal class MediaDeck(private val app: Context) {
    private val audio = app.getSystemService(AudioManager::class.java)
    private val manager = app.getSystemService(MediaSessionManager::class.java)
    private val token = ComponentName(app, MediaSessionAccess::class.java)
    private val access get() = app.getSystemService(NotificationManager::class.java)
        .isNotificationListenerAccessGranted(token)
    private fun controller(): MediaController? {
        val sessions = manager.getActiveSessions(token)
        return sessions.firstOrNull { it.playbackState?.state == P.STATE_PLAYING } ?: sessions.firstOrNull()
    }
    fun snapshot(): MediaDeckState {
        val basic = MediaDeckState(volume = audio.getStreamVolume(AudioManager.STREAM_MUSIC),
            maximum = audio.getStreamMaxVolume(AudioManager.STREAM_MUSIC))
        if (!access) return basic
        if (!app.isUnlockedAndInteractive()) return basic.copy(access = true, status = "Locked")
        return try {
            val controller = controller() ?: return basic.copy(access = true, status = "No session")
            val state = controller.playbackState
            val metadata = controller.metadata
            val title = (metadata?.getString(MediaMetadata.METADATA_KEY_DISPLAY_TITLE)
                ?: metadata?.getString(MediaMetadata.METADATA_KEY_TITLE)).orEmpty().take(80)
                .replace(Regex("[\\p{C}\\s]+"), " ").trim()
            basic.copy(access = true, status = status(state?.state), title = title, playing = playing(state?.state),
                supported = supported(state))
        } catch (_: SecurityException) { basic.copy(status = "Access unavailable") }
    }
    fun execute(command: DeckCommand): DeckResult {
        val direction = when (command) {
            DeckCommand.LOUDER -> AudioManager.ADJUST_RAISE
            DeckCommand.QUIETER -> AudioManager.ADJUST_LOWER
            DeckCommand.MUTE -> AudioManager.ADJUST_MUTE
            DeckCommand.UNMUTE -> AudioManager.ADJUST_UNMUTE
            else -> null
        }
        return try {
            if (direction != null) {
                if (audio.isVolumeFixed) return unavailable("This device uses fixed volume.")
                audio.adjustStreamVolume(AudioManager.STREAM_MUSIC, direction, 0)
                return ok("Music volume adjusted.")
            }
            if (access) {
                val controller = controller() ?: return unavailable("Start a media player first.")
                val state = controller.playbackState
                if (command !in supported(state)) return unavailable("The active player does not offer this control.")
                when (command) {
                    DeckCommand.PLAY -> if (playing(state?.state) == true) controller.transportControls.pause()
                        else controller.transportControls.play()
                    DeckCommand.PREVIOUS -> controller.transportControls.skipToPrevious()
                    DeckCommand.NEXT -> controller.transportControls.skipToNext()
                    else -> return unavailable("Unsupported transport control.")
                }
                ok("Requested ${command.label} from the active player.")
            } else {
                val key = when (command) {
                    DeckCommand.PLAY -> KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE
                    DeckCommand.PREVIOUS -> KeyEvent.KEYCODE_MEDIA_PREVIOUS
                    DeckCommand.NEXT -> KeyEvent.KEYCODE_MEDIA_NEXT
                    else -> return unavailable("Unsupported transport control.")
                }
                audio.dispatchMediaKeyEvent(KeyEvent(KeyEvent.ACTION_DOWN, key))
                audio.dispatchMediaKeyEvent(KeyEvent(KeyEvent.ACTION_UP, key))
                ok("Media key sent. An active player is required.")
            }
        } catch (_: SecurityException) { unavailable("Android restricted media access or the volume change.") }
    }
    companion object {
        private fun playing(state: Int?) = when (state) {
            P.STATE_PLAYING, P.STATE_FAST_FORWARDING, P.STATE_REWINDING -> true
            P.STATE_STOPPED, P.STATE_PAUSED -> false
            else -> null
        }
        private fun status(state: Int?) = when (state) {
            P.STATE_PLAYING -> "Playing"
            P.STATE_PAUSED -> "Paused"
            P.STATE_STOPPED -> "Stopped"
            P.STATE_BUFFERING, P.STATE_CONNECTING -> "Loading"
            P.STATE_ERROR -> "Player error"
            else -> "State unknown"
        }
        internal fun supported(state: P?): Set<DeckCommand> {
            val actions = state?.actions ?: return emptySet()
            val toggle = if (playing(state.state) == true) P.ACTION_PAUSE else P.ACTION_PLAY
            return buildSet {
                if (actions and (toggle or P.ACTION_PLAY_PAUSE) != 0L) add(DeckCommand.PLAY)
                if (actions and P.ACTION_SKIP_TO_PREVIOUS != 0L) add(DeckCommand.PREVIOUS)
                if (actions and P.ACTION_SKIP_TO_NEXT != 0L) add(DeckCommand.NEXT)
            }
        }
    }
}
