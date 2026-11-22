package tel.misa.mxpocket.decks

import tel.misa.mxpocket.i18n.KeypadCopy as K

import android.app.NotificationManager
import android.content.BroadcastReceiver
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.database.ContentObserver
import android.graphics.Bitmap
import android.media.AudioManager
import android.media.MediaMetadata
import android.media.session.MediaController
import android.media.session.MediaSession
import android.media.session.MediaSessionManager
import android.media.session.PlaybackState as P
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.provider.Settings
import android.service.notification.NotificationListenerService
import android.view.KeyEvent
import androidx.core.content.ContextCompat
import tel.misa.mxpocket.isUnlockedAndInteractive
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.runInterruptible

/** A component token for getActiveSessions. No posted/removed callbacks, notification queries or exports. */
class MediaSessionAccess : NotificationListenerService() {
    override fun onListenerConnected() { changes.update { it + 1 } }
    override fun onListenerDisconnected() { changes.update { it + 1 } }
    companion object { internal val changes = MutableStateFlow(0L) }
}

internal fun Context.hasMediaSessionAccess(): Boolean = getSystemService(NotificationManager::class.java)
    .isNotificationListenerAccessGranted(ComponentName(this, MediaSessionAccess::class.java))

internal class MediaDeck(private val app: Context, private val canShowTrack: () -> Boolean) {
    private val audio = app.getSystemService(AudioManager::class.java)
    private val manager = app.getSystemService(MediaSessionManager::class.java)
    private var activeWatch: Watch? = null
    fun refreshDisplay() { activeWatch?.refresh() }
    private val token = ComponentName(app, MediaSessionAccess::class.java)
    private val access get() = app.hasMediaSessionAccess()
    private fun basic() = MediaDeckState(volume = audio.getStreamVolume(AudioManager.STREAM_MUSIC),
        maximum = audio.getStreamMaxVolume(AudioManager.STREAM_MUSIC), fixedVolume = audio.isVolumeFixed,
        muted = audio.isStreamMute(AudioManager.STREAM_MUSIC))

    /** Main-thread scoped to an enabled Audio deck with a visible screen or a hardware session. */
    suspend fun observe(publish: (MediaDeckState) -> Unit): Unit = coroutineScope {
        val watch = Watch(this, publish)
        val power = object : BroadcastReceiver() {
            override fun onReceive(context: Context, intent: Intent) = watch.refresh()
        }
        val volume = object : ContentObserver(Handler(Looper.getMainLooper())) {
            override fun onChange(selfChange: Boolean) { watch.refresh() }
        }
        ContextCompat.registerReceiver(app, power, IntentFilter().apply {
            addAction(Intent.ACTION_SCREEN_OFF); addAction(Intent.ACTION_SCREEN_ON); addAction(Intent.ACTION_USER_PRESENT)
        }, ContextCompat.RECEIVER_NOT_EXPORTED)
        try {
            app.contentResolver.registerContentObserver(Settings.System.CONTENT_URI, true, volume)
            try {
                activeWatch = watch
                launch { MediaSessionAccess.changes.collect { watch.reconnect() } }
                // Recover missed settings/grant notifications and incomplete metadata without polling faster.
                while (true) { delay(2000); watch.refresh(probeMissing = true) }
            } finally {
                if (activeWatch === watch) activeWatch = null
                app.contentResolver.unregisterContentObserver(volume)
            }
        } finally {
            watch.close()
            app.unregisterReceiver(power)
        }
    }

    private inner class Watch(val scope: CoroutineScope, val publish: (MediaDeckState) -> Unit) {
        private val main = Handler(Looper.getMainLooper())
        private var listener: MediaSessionManager.OnActiveSessionsChangedListener? = null
        private inner class Player(val controller: MediaController) : MediaController.Callback() {
            var playback: P? = null
            val current get() = players[controller.sessionToken] === this
            override fun onPlaybackStateChanged(state: P?) {
                if (!current) return
                playback = state
                refresh()
            }
            override fun onMetadataChanged(metadata: MediaMetadata?) {
                if (!current || this !== metadataOwner) return
                refresh(updated = this, metadata = metadata)
            }
            override fun onAudioInfoChanged(info: MediaController.PlaybackInfo) {
                if (current) refresh()
            }
            override fun onSessionDestroyed() {
                if (!current) return
                replace(players.values.filterNot { it === this }.map { it.controller })
                refresh()
            }
        }
        private val players = linkedMapOf<MediaSession.Token, Player>()
        private var metadataOwner: Player? = null
        private var track = emptyList<String>()
        private var title = ""
        private var source: SessionArtwork? = null
        private var target: SessionArtwork? = null
        private var displayed: SessionArtwork? = null
        private var artwork: Bitmap? = null
        private var job: Job? = null
        private var transition: Job? = null
        private var retryAt = 0L
        private var backoff = INITIAL_BACKOFF
        private var closed = false

        private fun replace(ordered: List<MediaController>) {
            val tokens = ordered.mapTo(mutableSetOf()) { it.sessionToken }
            for (id in players.keys - tokens) {
                players.remove(id)?.let { it.controller.unregisterCallback(it) }
            }
            for (c in ordered) {
                // Keep registrations across wrapper changes, but respect the latest priority order.
                val id = c.sessionToken
                players[id] = players.remove(id) ?: Player(c).also {
                    c.registerCallback(it, main)
                    it.playback = c.playbackState
                }
            }
        }

        private fun subscribe() {
            val change = object : MediaSessionManager.OnActiveSessionsChangedListener {
                override fun onActiveSessionsChanged(controllers: List<MediaController>?) {
                    if (this === listener) refresh(controllers.orEmpty())
                }
            }
            manager.addOnActiveSessionsChangedListener(change, token, main)
            listener = change
            replace(manager.getActiveSessions(token))
        }

        fun refresh(roster: List<MediaController>? = null, probeMissing: Boolean = false) =
            refresh(roster, probeMissing, null, null)

        private fun refresh(roster: List<MediaController>? = null, probeMissing: Boolean = false,
                            updated: Player?, metadata: MediaMetadata?) {
            if (closed) return
            val basic = basic()
            if (!access) { reset(); publish(basic); return }
            try {
                if (listener == null) subscribe() else if (roster != null) replace(roster)
                val player = players.values.firstOrNull { it.playback?.state == P.STATE_PLAYING } ?: players.values.firstOrNull()
                val state = player?.playback
                val owner = player?.takeIf { canShowTrack() && state?.state in displayStates }
                val fresh = owner != null && owner === updated
                if (owner !== metadataOwner || fresh || (probeMissing && owner != null && source == null)) {
                    val data = if (fresh) metadata else owner?.controller?.metadata
                    val identity = trackFields.map { data?.getString(it).orEmpty() }
                    val next = SessionArtwork.from(data)
                    val ownerChanged = owner !== metadataOwner
                    val trackChanged = identity != track
                    val sourceChanged = if (next == null) source != null else !next.sameSource(source)
                    if (ownerChanged) clearLoad() else if (trackChanged || sourceChanged) cancelRead()
                    val coldLookup = ownerChanged && (next != null || identity.all(String::isEmpty))
                    if (owner != null && (coldLookup || ((trackChanged || sourceChanged) && artwork != null)))
                        beginTransition()
                    track = identity
                    title = (data?.getString(MediaMetadata.METADATA_KEY_DISPLAY_TITLE)
                        ?: data?.getString(MediaMetadata.METADATA_KEY_TITLE)).orEmpty().take(80).replace(whitespace, " ").trim()
                    if (ownerChanged || trackChanged || sourceChanged) source = next
                    metadataOwner = owner
                }
                if (displayed?.readable(app) == false) clearLoad()
                if (source?.readable(app) == false) clearLoad()
                else load(source, fresh)
                publish(basic.copy(access = true, status = if (player == null) "No session" else status(state?.state),
                    title = title, playing = playing(state?.state), supported = supported(state, app.isUnlockedAndInteractive()),
                    artwork = artwork,
                    awaitingCover = transition != null && artwork == null))
            } catch (_: SecurityException) {
                reset()
                publish(basic.copy(status = "Access unavailable"))
            }
        }

        private fun load(next: SessionArtwork?, freshMetadata: Boolean) {
            if (next == null) return
            if (next !== target) {
                job?.cancel(); job = null
                target = next
                retryAt = 0; backoff = INITIAL_BACKOFF
            }
            if (job != null || SystemClock.elapsedRealtime() < retryAt) return
            // A provider can update bytes at the same URI. Replace the current cover only after a successful read.
            if (displayed === next && !(freshMetadata && next.isUri) && retryAt == 0L) return
            job = scope.launch {
                val image = runInterruptible(artworkIO) { next.load(app) }
                if (closed || next !== target) return@launch
                job = null
                if (image == null) {
                    retryAt = SystemClock.elapsedRealtime() + backoff
                    backoff = (backoff * 2).coerceAtMost(60_000L)
                } else if (canShowTrack() && access && next.readable(app)) {
                    transition?.cancel(); transition = null
                    displayed = next
                    artwork = image
                    retryAt = 0; backoff = INITIAL_BACKOFF
                }
                refresh()
            }
        }

        private fun cancelRead() {
            job?.cancel(); job = null
            target = null
            retryAt = 0; backoff = INITIAL_BACKOFF
        }

        private fun beginTransition() {
            if (transition != null) return
            // Coalesce empty -> title -> cover callbacks without pushing nine temporary LCD tiles.
            transition = scope.launch {
                delay(COVER_GRACE_MS)
                transition = null
                displayed = null; artwork = null
                refresh()
            }
        }

        private fun clearLoad() {
            cancelRead()
            transition?.cancel(); transition = null
            displayed = null; artwork = null
        }

        private fun reset() {
            listener?.let(manager::removeOnActiveSessionsChangedListener)
            listener = null
            replace(emptyList())
            metadataOwner = null
            track = emptyList()
            title = ""
            source = null
            clearLoad()
        }
        fun reconnect() { reset(); refresh() }
        fun close() { closed = true; reset() }
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
                if (audio.isVolumeFixed) return unavailable(K.FixedVolume.text())
                audio.adjustStreamVolume(AudioManager.STREAM_MUSIC, direction, 0)
                activeWatch?.refresh()
                return ok(K.VolumeAdjusted.text())
            }
            if (access) {
                val sessions = manager.getActiveSessions(token)
                val controller = sessions.firstOrNull { it.playbackState?.state == P.STATE_PLAYING }
                    ?: sessions.firstOrNull() ?: return unavailable(K.StartPlayer.text())
                val state = controller.playbackState
                if (command !in supported(state, app.isUnlockedAndInteractive()))
                    return unavailable(K.NoPlayerControl.text())
                when (command) {
                    DeckCommand.PLAY -> {
                        val pause = playing(state?.state) == true
                        val explicit = if (pause) P.ACTION_PAUSE else P.ACTION_PLAY
                        if ((state?.actions ?: 0L) and explicit != 0L) {
                            if (pause) controller.transportControls.pause() else controller.transportControls.play()
                        } else {
                            val key = KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE
                            val down = controller.dispatchMediaButtonEvent(KeyEvent(KeyEvent.ACTION_DOWN, key))
                            val up = controller.dispatchMediaButtonEvent(KeyEvent(KeyEvent.ACTION_UP, key))
                            if (!down || !up) return unavailable(K.PlayerDeclined.text())
                        }
                    }
                    DeckCommand.PREVIOUS -> controller.transportControls.skipToPrevious()
                    DeckCommand.NEXT -> controller.transportControls.skipToNext()
                    DeckCommand.BACK_10, DeckCommand.FORWARD_10 -> {
                        val duration = controller.metadata?.takeIf { it.containsKey(MediaMetadata.METADATA_KEY_DURATION) }
                            ?.getLong(MediaMetadata.METADATA_KEY_DURATION)
                        controller.transportControls.seekTo(seekTarget(checkNotNull(state), SystemClock.elapsedRealtime(),
                            if (command == DeckCommand.BACK_10) -10_000L else 10_000L, duration))
                    }
                    else -> return unavailable(K.UnsupportedTransport.text())
                }
                ok(K.PlayerRequested.text(command.displayLabel))
            } else {
                val key = when (command) {
                    DeckCommand.PLAY -> KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE
                    DeckCommand.PREVIOUS -> KeyEvent.KEYCODE_MEDIA_PREVIOUS
                    DeckCommand.NEXT -> KeyEvent.KEYCODE_MEDIA_NEXT
                    else -> return unavailable(K.UnsupportedTransport.text())
                }
                audio.dispatchMediaKeyEvent(KeyEvent(KeyEvent.ACTION_DOWN, key))
                audio.dispatchMediaKeyEvent(KeyEvent(KeyEvent.ACTION_UP, key))
                ok(K.MediaKeySent.text())
            }
        } catch (_: SecurityException) { unavailable(K.MediaRestricted.text()) }
    }
    companion object {
        private const val COVER_GRACE_MS = 650L
        private const val INITIAL_BACKOFF = 2000L
        private val trackFields = listOf(MediaMetadata.METADATA_KEY_MEDIA_ID, MediaMetadata.METADATA_KEY_TITLE,
            MediaMetadata.METADATA_KEY_DISPLAY_TITLE, MediaMetadata.METADATA_KEY_ARTIST, MediaMetadata.METADATA_KEY_ALBUM)
        private val artworkIO = Dispatchers.IO.limitedParallelism(1)
        private val displayStates = setOf(P.STATE_PLAYING, P.STATE_PAUSED, P.STATE_FAST_FORWARDING, P.STATE_REWINDING,
            P.STATE_BUFFERING, P.STATE_CONNECTING)
        private val whitespace = Regex("[\\p{C}\\s]+")
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
        internal fun seekTarget(state: P, now: Long, offset: Long, duration: Long?): Long {
            // Android reports position at lastPositionUpdateTime, in elapsedRealtime's clock domain.
            val age = if (playing(state.state) == true && state.lastPositionUpdateTime in 1..now)
                now - state.lastPositionUpdateTime else 0L
            val end = duration?.takeIf { it >= 0 } ?: Long.MAX_VALUE
            val position = (state.position + age * state.playbackSpeed.toDouble()).coerceIn(0.0, end.toDouble())
            return (position + offset).toLong().coerceIn(0, end)
        }

        internal fun supported(state: P?, unlocked: Boolean = true): Set<DeckCommand> {
            val actions = state?.actions ?: return emptySet()
            val toggle = if (playing(state.state) == true) P.ACTION_PAUSE else P.ACTION_PLAY
            return buildSet {
                if (actions and (toggle or P.ACTION_PLAY_PAUSE) != 0L) add(DeckCommand.PLAY)
                if (actions and P.ACTION_SKIP_TO_PREVIOUS != 0L) add(DeckCommand.PREVIOUS)
                if (actions and P.ACTION_SKIP_TO_NEXT != 0L) add(DeckCommand.NEXT)
                if (unlocked && actions and P.ACTION_SEEK_TO != 0L && state.position >= 0 &&
                    state.playbackSpeed.isFinite()) {
                    add(DeckCommand.BACK_10); add(DeckCommand.FORWARD_10)
                }
            }
        }
    }
}
