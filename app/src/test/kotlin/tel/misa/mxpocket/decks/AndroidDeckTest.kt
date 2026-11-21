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
import tel.misa.mxpocket.installTestCamera

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
    @Test fun foregroundKeyOpensOnlyRationaleAndVirtualStudioCannotCapture() {
        val session = app.session
        assertFalse(session.runDeckAction("studio.record").success)
        assertFalse(session.runDeckAction("navigation.activate").success)
        Robolectric.buildActivity(MainActivity::class.java).setup().use { controller ->
            val activity = controller.get()
            session.startPreview(); session.selectProfile("audio")
            assertFalse(session.runDeckAction("media.access").success)
            session.phoneKey(4)
            assertEquals(DeckAccessActivity::class.java.name, shadowOf(activity).nextStartedActivity.component?.className)
            session.selectProfile("studio")
            session.phoneKey(1)
            assertTrue(session.status.contains("physical Studio keypad"))
            assertNull(shadowOf(activity).nextStartedActivity)
            val native = installTestCamera(app)
            for ((index, mode) in listOf(7 to CameraMode.PHOTO, 6 to CameraMode.VIDEO)) {
                session.phoneKey(index)
                val viewfinder = shadowOf(activity).nextStartedActivity
                assertEquals(native, viewfinder.component)
                assertEquals(mode.action, viewfinder.action)
                assertNull(viewfinder.extras)
            }
            shadowOf(app.getSystemService(KeyguardManager::class.java)).setKeyguardLocked(true)
            session.phoneKey(7)
            assertNull(shadowOf(activity).nextStartedActivity)
        }
        Robolectric.buildActivity(CameraControlsActivity::class.java).setup().use {
            assertFalse(CameraControlsAccess(app).enabled)
            assertNull(shadowOf(it.get()).lastRequestedPermission)
            assertNull(shadowOf(it.get()).nextStartedActivity)
        }
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
}
