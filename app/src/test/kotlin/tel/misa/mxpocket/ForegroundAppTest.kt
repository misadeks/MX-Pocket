package tel.misa.mxpocket

import android.app.AppOpsManager
import android.app.KeyguardManager
import android.app.usage.UsageEvents.Event
import android.app.usage.UsageStatsManager
import android.graphics.Bitmap
import android.hardware.usb.UsbDevice
import android.hardware.usb.UsbManager
import android.media.AudioManager
import android.os.Looper
import kotlinx.coroutines.Dispatchers
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.annotation.LooperMode
import org.robolectric.shadows.ShadowUsageStatsManager.EventBuilder
import org.robolectric.util.ReflectionHelpers
import tel.misa.mxpocket.actions.ActionCall
import tel.misa.mxpocket.deck.DeckKey
import tel.misa.mxpocket.deck.DeckRule
import tel.misa.mxpocket.driver.KeyEvent
import java.time.Duration

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28, 35])
@LooperMode(LooperMode.Mode.PAUSED)
@Suppress("DEPRECATION")
class ForegroundAppTest {
    private val app get() = RuntimeEnvironment.getApplication() as PocketApplication
    private val usage get() = app.getSystemService(UsageStatsManager::class.java)
    private val main get() = shadowOf(Looper.getMainLooper())
    private val player = "example.music"
    private val camera = "example.camera"
    private val other = "example.unmapped"

    private fun event(pkg: String, type: Int, time: Long = System.currentTimeMillis(), activity: String = "Main") {
        shadowOf(usage).addEvent(EventBuilder.buildEvent().setPackage(pkg).setClass(activity)
            .setEventType(type).setTimeStamp(time).build())
    }

    private fun grant(enabled: Boolean) {
        shadowOf(app.getSystemService(AppOpsManager::class.java)).setMode(AppOpsManager.OPSTR_GET_USAGE_STATS,
            app.applicationInfo.uid, app.packageName, if (enabled) AppOpsManager.MODE_ALLOWED else AppOpsManager.MODE_IGNORED)
    }

    @Test fun twoMappingsChangeOnlyTheMenuAndLoseTheirSignalOnPinLockAndRevocation() {
        lateinit var key: (KeyEvent) -> Unit
        val session = PocketSession(app, Dispatchers.Main.immediate) { _, _, emit, _, _ ->
            key = emit
            object : KeypadOutput {
                override fun close() {}
                override suspend fun setKeyImage(index: Int, bitmap: Bitmap) {}
            }
        }
        val device = ReflectionHelpers.newInstance(UsbDevice::class.java)
        ReflectionHelpers.setField(device, "mName", "simulated")
        val deck = session.deck
        val custom = deck.createProfile("Camera tools").getOrThrow()
        val page = deck.createPage(custom, "Controls").getOrThrow()
        deck.selectProfile(custom).getOrThrow()
        deck.openPage(page).getOrThrow()
        deck.setKey(custom, page, 0, DeckKey(label = "Root", action = ActionCall("page.root"))).getOrThrow()
        deck.setRules(custom, listOf(DeckRule(FOREGROUND_APP, camera, APP_RULE_WEIGHT))).getOrThrow()
        val audioRules = deck.state.value.profiles.first { it.id == "audio" }.rules
        deck.setRules("audio", audioRules + DeckRule(FOREGROUND_APP, player, APP_RULE_WEIGHT)).getOrThrow()
        deck.selectProfile("focus").getOrThrow()
        deck.setAutomatic(true).getOrThrow()
        val profiles = deck.state.value.profiles
        val audio = shadowOf(app.getSystemService(AudioManager::class.java))
        val lock = shadowOf(app.getSystemService(KeyguardManager::class.java))
        fun tick(seconds: Long = 4) = main.idleFor(Duration.ofSeconds(seconds))
        fun signal() = session.signals.value[FOREGROUND_APP]

        grant(true) // OS access alone is not consent to observe.
        session.connect(session.reserve(device)!!, app.getSystemService(UsbManager::class.java), device,
            {}, { error("A menu switch must never request a shortcut") })
        try {
            event(player, Event.MOVE_TO_FOREGROUND)
            tick()
            assertNull(signal())
            assertEquals("focus", deck.state.value.activeProfileId)
            session.setDetectForegroundApps(true)
            tick(1)
            event(player, Event.MOVE_TO_FOREGROUND)
            tick()
            assertEquals(player, signal())
            assertEquals("audio", deck.state.value.activeProfileId)
            assertTrue(audio.dispatchedMediaKeyEvents.isEmpty())
            audio.setIsMusicActive(true)
            key(KeyEvent(1, true))
            main.idle()

            event(player, Event.MOVE_TO_BACKGROUND)
            event(camera, Event.MOVE_TO_FOREGROUND)
            tick(1)
            assertEquals("audio", deck.state.value.activeProfileId) // Still debouncing.
            tick()
            assertEquals(camera, signal())
            assertEquals(custom, deck.state.value.activeProfileId) // Beats the playing Audio incumbent.
            key(KeyEvent(1, false))
            main.idle()
            assertEquals(page, deck.state.value.activePage.id) // Neither the switch nor the held-key release ran Root.

            deck.setPinned(custom).getOrThrow()
            tick(1)
            assertNull(signal())
            event(player, Event.MOVE_TO_FOREGROUND)
            tick()
            assertEquals(custom, deck.state.value.activeProfileId)
            deck.setPinned(null).getOrThrow()
            tick()
            assertNull(signal())
            assertEquals("audio", deck.state.value.activeProfileId) // Playback alone, not the foreground player.
            event(camera, Event.MOVE_TO_FOREGROUND)
            tick()
            assertEquals(custom, deck.state.value.activeProfileId)
            lock.setKeyguardLocked(true)
            tick()
            assertNull(signal())
            assertEquals("audio", deck.state.value.activeProfileId)
            lock.setKeyguardLocked(false)
            audio.setIsMusicActive(false)
            tick()
            assertNull(signal())
            assertEquals("focus", deck.state.value.activeProfileId)

            event(camera, Event.MOVE_TO_FOREGROUND)
            tick()
            assertEquals(custom, deck.state.value.activeProfileId)
            grant(false)
            tick()
            assertNull(signal())
            assertEquals("focus", deck.state.value.activeProfileId)
            grant(true)
            tick()
            assertNull(signal()) // Don't resurrect pre-revocation events.
            event(other, Event.MOVE_TO_FOREGROUND)
            tick()
            assertNull(signal())
            assertFalse(deck.exportJson().contains(other))
            assertFalse(session.logText().contains(other))
            assertEquals(profiles, deck.state.value.profiles)
            assertNull(session.workflows.status.value)
        } finally { session.disconnect() }
        assertNull(signal())
    }

    @Test fun onlyAnUnambiguousFreshResumedActivityCounts() {
        val reader = ForegroundApps(usage, 1)
        val mapped = setOf(player, camera)
        fun read(time: Long) = reader.match(mapped, time)
        event(player, Event.MOVE_TO_FOREGROUND, 100)
        event(camera, Event.FOREGROUND_SERVICE_START, 101)
        assertEquals(player, read(110))
        event(camera, Event.MOVE_TO_FOREGROUND, 120)
        assertNull(read(130))
        event(player, Event.MOVE_TO_BACKGROUND, 140)
        assertEquals(camera, read(150))
        event(camera, Event.MOVE_TO_FOREGROUND, 160, "Second")
        event(camera, Event.ACTIVITY_STOPPED, 170)
        assertEquals(camera, read(180))
        event("android", Event.KEYGUARD_SHOWN, 190)
        assertNull(read(200))
        event(camera, Event.MOVE_TO_FOREGROUND, 210)
        assertEquals(camera, read(220))
        assertNull(read(220 + ForegroundApps.FRESH_MS))
        assertNull(read(221 + ForegroundApps.FRESH_MS))
    }
}
