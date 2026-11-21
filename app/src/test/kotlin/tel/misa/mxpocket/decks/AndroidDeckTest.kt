package tel.misa.mxpocket.decks

import android.app.KeyguardManager
import android.content.ComponentName
import android.content.Intent
import android.content.pm.ActivityInfo
import android.content.pm.ApplicationInfo
import android.content.pm.ResolveInfo
import android.media.session.PlaybackState as P
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import tel.misa.mxpocket.MainActivity
import tel.misa.mxpocket.PocketApplication

@RunWith(org.robolectric.RobolectricTestRunner::class)
@Config(sdk = [28, 35])
class AndroidDeckTest {
    private val app get() = RuntimeEnvironment.getApplication() as PocketApplication
    @Before fun broadcasts() {
        shadowOf(app).grantPermissions("tel.misa.mxpocket.DYNAMIC_RECEIVER_NOT_EXPORTED_PERMISSION")
    }
    @Test fun revalidatesFavoriteAgainstInstalledLaunchableActivityBeforeOpening() {
        val pm = shadowOf(app.packageManager)
        val name = ComponentName("sample.player", "sample.player.Start")
        val main = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER)
        val info = ResolveInfo().apply {
            activityInfo = ActivityInfo().apply {
                packageName = name.packageName; this.name = name.className; enabled = true; exported = true
                applicationInfo = ApplicationInfo().apply { packageName = name.packageName }
            }
            nonLocalizedLabel = "Player"
        }
        pm.addResolveInfoForIntent(main, info)
        pm.addResolveInfoForIntent(Intent(main).setPackage(name.packageName), info)
        val store = FavoriteApps(app)
        val target = name.flattenToString()
        assertTrue(store.discover().any { it.component == target && it.label == "Player" })
        assertNull(store.intentFor(target))
        store.setSelected(target, true)
        assertEquals(name, FavoriteApps(app).intentFor(target)?.component)
        store.setSelected("sample.player/.Hidden", true)
        assertNull(store.intentFor("sample.player/.Hidden"))
        store.setSelected(target, false)
        assertNull(store.intentFor(target))
    }
    @Test fun foregroundKeyOpensOnlyRationaleAndStudioRemainsInertWithoutCameraGrant() {
        val session = app.session
        assertFalse(session.runDeckAction("studio.record").success)
        assertFalse(session.runDeckAction("navigation.activate").success)
        Robolectric.buildActivity(MainActivity::class.java).setup().use { controller ->
            val activity = controller.get()
            session.startPreview(); session.selectProfile("audio")
            assertFalse(session.runDeckAction("media.access").success)
            session.phoneKey(7)
            assertNull(shadowOf(activity).nextStartedActivity)
            session.pluginSettings("audio")!!.second()
            assertEquals(DeckAccessActivity::class.java.name, shadowOf(activity).nextStartedActivity.component?.className)
            session.selectProfile("studio")
            session.phoneKey(1)
            assertFalse(session.deckState.value.studio.recording)
            assertNull(shadowOf(activity).nextStartedActivity)
            shadowOf(app.getSystemService(KeyguardManager::class.java)).setKeyguardLocked(true)
            session.phoneKey(7)
            assertNull(shadowOf(activity).nextStartedActivity)
        }
        Robolectric.buildActivity(StudioActivity::class.java).setup().use {
            assertFalse(it.get().press(DeckCommand.RECORD).success)
            assertFalse(session.deckState.value.studio.ready)
            assertFalse(session.deckState.value.studio.recording)
            assertNull(shadowOf(it.get()).nextStartedActivity)
        }
    }

    @Test fun seekingUsesSelectedPlayersPositionClockSpeedAndKnownDuration() {
        val player = android.media.session.MediaSession(app, "music")
        val other = android.media.session.MediaSession(app, "other")
        try {
            val sessions = shadowOf(app.getSystemService(android.media.session.MediaSessionManager::class.java))
            val controller = shadowOf(player.controller)
            fun state(status: Int, position: Long, speed: Float, actions: Long = P.ACTION_SEEK_TO) =
                P.Builder().setState(status, position, speed, 100L).setActions(actions).build()
            val sample = state(P.STATE_PLAYING, 15_000, 2f)
            assertEquals(9000L, MediaDeck.seekTarget(sample, 2_100, -10_000, null))
            assertEquals(25_000L, MediaDeck.seekTarget(sample, 2_100, 10_000, 25_000))
            assertEquals(25_000L, MediaDeck.seekTarget(sample, 50, 10_000, null))
            assertEquals(25_000L, MediaDeck.seekTarget(state(P.STATE_PAUSED,15_000,2f), 2_100, 10_000, null))
            assertEquals(0L, MediaDeck.seekTarget(state(P.STATE_REWINDING, 11_000,-2f), 2_100, -10_000, null))
            assertTrue(MediaDeck.supported(state(P.STATE_PLAYING,-1,1f)).isEmpty())
            assertTrue(MediaDeck.supported(state(P.STATE_PLAYING,1,1f,P.ACTION_FAST_FORWARD)).isEmpty())
            assertTrue(MediaDeck.supported(sample, unlocked = false).isEmpty())
            val media = MediaDeck(app) { true }
            val token = ComponentName(app, MediaSessionAccess::class.java)
            val notification = shadowOf(app.getSystemService(android.app.NotificationManager::class.java))
            notification.setNotificationListenerAccessGranted(token,true)
            shadowOf(other.controller).setPlaybackState(state(P.STATE_PAUSED,0,0f))
            sessions.addController(other.controller)
            controller.setPlaybackState(state(P.STATE_PLAYING,1500,0f))
            controller.setMetadata(android.media.MediaMetadata.Builder()
                .putLong(android.media.MediaMetadata.METADATA_KEY_DURATION, 5000).build())
            sessions.addController(player.controller)
            val transport = shadowOf(player.controller.transportControls)
            assertTrue(media.execute(DeckCommand.FORWARD_10).success)
            assertEquals(P.ACTION_SEEK_TO, transport.lastPerformedAction)
            assertEquals(5000L, transport.seekToPositionMs)
            assertTrue(media.execute(DeckCommand.BACK_10).success)
            assertEquals(0L, transport.seekToPositionMs)
            controller.setPlaybackState(state(P.STATE_PLAYING,-1,1f))
            assertFalse(media.execute(DeckCommand.FORWARD_10).success)
            assertNotEquals(P.ACTION_SEEK_TO, shadowOf(other.controller.transportControls).lastPerformedAction)
            notification.setNotificationListenerAccessGranted(token,false)
            assertFalse(media.execute(DeckCommand.BACK_10).success)
        } finally { player.release(); other.release() }
    }
    @Test fun playerCapabilitiesFollowAdvertisedActionsAndDoNotInventPauseOrSkip() {
        fun state(now: Int, actions: Long) = P.Builder().setState(now, 0, 1f).setActions(actions).build()
        assertEquals(emptySet<DeckCommand>(), MediaDeck.supported(null))
        assertEquals(setOf(DeckCommand.PLAY), MediaDeck.supported(state(P.STATE_PAUSED, P.ACTION_PLAY)))
        assertEquals(setOf(DeckCommand.NEXT), MediaDeck.supported(state(P.STATE_PLAYING, P.ACTION_PLAY or P.ACTION_SKIP_TO_NEXT)))
        assertEquals(setOf(DeckCommand.PLAY), MediaDeck.supported(state(P.STATE_PLAYING, P.ACTION_PLAY_PAUSE)))
        Robolectric.buildActivity(MainActivity::class.java).setup().use {
            assertFalse(app.session.deckState.value.media.access)
            assertNull(app.session.deckState.value.media.playing)
        }
    }

    @Test fun muteKeyUsesAndroidStreamMuteAndDoesNotInferMuteFromTheVolumeLevel() {
        val audio = app.getSystemService(android.media.AudioManager::class.java)
        val music = android.media.AudioManager.STREAM_MUSIC
        Robolectric.buildActivity(MainActivity::class.java).setup().use {
            val session = app.session
            session.startPreview(); session.selectProfile("audio")
            fun settle() = shadowOf(android.os.Looper.getMainLooper()).idle()
            audio.setStreamVolume(music, 0, 0)
            settle()
            assertFalse(audio.isStreamMute(music))
            assertEquals("Mute", session.frame().keys[1].label)
            assertTrue(session.phoneKey(1)); settle()
            assertTrue(audio.isStreamMute(music))
            assertEquals("Unmute", session.frame().keys[1].label)
            assertTrue(session.phoneKey(1)); settle()
            assertFalse(audio.isStreamMute(music))
            assertEquals("Mute", session.frame().keys[1].label)
            session.disconnect()
        }
    }
}
