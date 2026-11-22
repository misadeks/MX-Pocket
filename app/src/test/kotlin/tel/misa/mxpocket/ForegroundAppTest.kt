package tel.misa.mxpocket

import android.app.AppOpsManager
import android.app.KeyguardManager
import android.app.usage.UsageEvents.Event
import android.app.usage.UsageStatsManager
import android.content.Intent
import android.graphics.Bitmap
import android.hardware.usb.UsbDevice
import android.hardware.usb.UsbManager
import android.media.AudioManager
import android.os.Looper
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import org.junit.Assert.*
import org.junit.Before
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
import tel.misa.mxpocket.decks.IN_CALL
import tel.misa.mxpocket.decks.TEAMS
import tel.misa.mxpocket.decks.TEAMS_PACKAGE
import java.time.Duration
import kotlin.coroutines.CoroutineContext

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

    @Before fun grantReceiverPermission() {
        shadowOf(app).grantPermissions("${app.packageName}.DYNAMIC_RECEIVER_NOT_EXPORTED_PERMISSION")
    }

    // Android usage timestamps are wall time. Advance their test clock with the paused looper,
    // not the host JVM's wall time: idleFor(4 s) can take less than a millisecond to execute.
    private fun usageTime() = 1_700_000_000_000L + android.os.SystemClock.elapsedRealtime()

    private fun event(pkg: String, type: Int, time: Long = usageTime(), activity: String = "Main") {
        shadowOf(usage).addEvent(EventBuilder.buildEvent().setPackage(pkg).setClass(activity)
            .setEventType(type).setTimeStamp(time).build())
    }

    private fun grant(enabled: Boolean) {
        shadowOf(app.getSystemService(AppOpsManager::class.java)).setMode(AppOpsManager.OPSTR_GET_USAGE_STATS,
            app.applicationInfo.uid, app.packageName, if (enabled) AppOpsManager.MODE_ALLOWED else AppOpsManager.MODE_IGNORED)
    }

    @Test fun openingTeamsSelectsWorkWithoutAMappingOrAnActionAndRespectsOverrides() {
        val session = PocketSession(app, usageDispatcher = Dispatchers.Main.immediate, usageTimeMillis = ::usageTime)
        val screen = object : PocketSession.Screen {
            override fun render() {}
            override fun openApp(app: tel.misa.mxpocket.plugins.PhoneApp): Boolean = error("Unexpected key dispatch")
        }
        val deck = session.deck
        fun tick() = main.idleFor(Duration.ofSeconds(4))
        fun openTeams() { event(TEAMS_PACKAGE, Event.MOVE_TO_FOREGROUND); tick() }
        fun leaveTeams() { event(TEAMS_PACKAGE, Event.MOVE_TO_BACKGROUND); tick() }
        fun expectFocus() = assertEquals("focus", deck.state.value.activeProfileId)
        fun expectWork() {
            assertEquals(TEAMS, deck.state.value.activeProfileId)
            assertEquals("root", deck.state.value.activePage.id)
            assertFalse(session.deckState.value.teams.joined)
            assertNull(session.workflows.status.value)
        }
        deck.selectProfile(TEAMS).getOrThrow()
        deck.openPage(IN_CALL).getOrThrow()
        deck.selectProfile("focus").getOrThrow()
        session.attach(screen)
        try {
            grant(true); openTeams(); expectFocus()
            deck.setAutomatic(true).getOrThrow(); openTeams(); expectFocus()
            grant(false); session.setDetectForegroundApps(true); openTeams(); expectFocus()
            grant(true); tick(); openTeams(); expectWork()
            session.selectProfile("phone"); tick()
            assertEquals("phone", deck.state.value.activeProfileId)
            leaveTeams(); openTeams(); expectWork()
            deck.openPage(IN_CALL).getOrThrow(); tick()
            assertEquals(IN_CALL, deck.state.value.activePage.id)
            leaveTeams(); expectFocus()
            session.setPluginEnabled(TEAMS, false); openTeams(); expectFocus()
            session.setPluginEnabled(TEAMS, true); tick()
            deck.setProfileAutomatic(TEAMS, false).getOrThrow(); openTeams(); expectFocus()
            deck.setProfileAutomatic(TEAMS, true).getOrThrow(); tick()
            deck.setPinned("phone").getOrThrow(); openTeams()
            assertEquals("phone", deck.state.value.activeProfileId)
            deck.setPinned(null).getOrThrow(); tick()
            grant(false); openTeams(); expectFocus()
            assertNull(session.signals.value[FOREGROUND_APP])
            assertFalse(deck.exportJson().contains(TEAMS_PACKAGE))
        } finally { session.detach(screen) }
    }

    @Test fun twoMappingsChangeOnlyTheMenuAndLoseTheirSignalOnPinLockAndRevocation() {
        lateinit var key: (KeyEvent) -> Unit
        val session = PocketSession(app, usageDispatcher = Dispatchers.Main.immediate, usageTimeMillis = ::usageTime) { _, _, emit, _, _ ->
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
            event("android", Event.KEYGUARD_HIDDEN)
            event(camera, Event.MOVE_TO_FOREGROUND)
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

    @Test fun unlockedResumeBeforeFirstPollMatchesButRegrantDoesNotReplayThatInterval() {
        val session = PocketSession(app, usageDispatcher = Dispatchers.Main.immediate, usageTimeMillis = ::usageTime)
        val screen = object : PocketSession.Screen {
            override fun render() {}
            override fun openApp(app: tel.misa.mxpocket.plugins.PhoneApp) = false
        }
        val deck = session.deck
        val lock = shadowOf(app.getSystemService(KeyguardManager::class.java))
        fun advance(ms: Long) = main.idleFor(Duration.ofMillis(ms))
        deck.setRules("studio", listOf(DeckRule(FOREGROUND_APP, camera, APP_RULE_WEIGHT))).getOrThrow()
        deck.setAutomatic(true).getOrThrow()
        session.setDetectForegroundApps(true)
        grant(true)
        lock.setKeyguardLocked(true)
        session.attach(screen)
        try {
            advance(1_100) // A locked poll, then unlock and resume strictly before the next poll.
            event(camera, Event.MOVE_TO_FOREGROUND) // Must not survive the unlock.
            advance(100)
            lock.setKeyguardLocked(false)
            event("android", Event.KEYGUARD_HIDDEN)
            app.sendBroadcast(Intent(Intent.ACTION_USER_PRESENT))
            advance(100)
            event(camera, Event.MOVE_TO_FOREGROUND)
            advance(4_000)
            assertEquals(camera, session.signals.value[FOREGROUND_APP])
            assertEquals("studio", deck.state.value.activeProfileId)

            lock.setKeyguardLocked(true)
            advance(1_000)
            grant(false)
            advance(1_000) // A denied poll while locked invalidates the recovery interval.
            lock.setKeyguardLocked(false)
            event("android", Event.KEYGUARD_HIDDEN)
            app.sendBroadcast(Intent(Intent.ACTION_USER_PRESENT))
            advance(100)
            event(camera, Event.MOVE_TO_FOREGROUND)
            advance(100)
            grant(true)
            advance(4_000)
            assertNull(session.signals.value[FOREGROUND_APP])
            assertEquals("focus", deck.state.value.activeProfileId)
        } finally { session.detach(screen) }
    }

    @Test fun onlyAnUnambiguousFreshResumedActivityCounts() {
        val reader = ForegroundApps(usage, 1)
        val mapped = setOf(player, camera)
        fun read(time: Long) = reader.match(mapped, time)
        event(camera, Event.MOVE_TO_FOREGROUND, 1)
        assertNull(read(2)) // Same millisecond as the new permission interval.
        event(player, Event.MOVE_TO_FOREGROUND, 100)
        event(camera, Event.FOREGROUND_SERVICE_START, 101)
        assertNull(read(105)) // Camera may still be resumed alongside Player.
        event(camera, Event.MOVE_TO_BACKGROUND, 106)
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

    @Test fun aLatePollCannotRestoreThePreviousMatchAfterALockOrGrantCycle() {
        val pending = ArrayDeque<Runnable>()
        val pausedReads = object : CoroutineDispatcher() {
            override fun dispatch(context: CoroutineContext, block: Runnable) { pending.addLast(block) }
        }
        val session = PocketSession(app, usageDispatcher = pausedReads, usageTimeMillis = ::usageTime)
        val screen = object : PocketSession.Screen {
            override fun render() {}
            override fun openApp(app: tel.misa.mxpocket.plugins.PhoneApp) = false
        }
        val lock = shadowOf(app.getSystemService(KeyguardManager::class.java))
        session.deck.setRules("studio", listOf(DeckRule(FOREGROUND_APP, camera, APP_RULE_WEIGHT))).getOrThrow()
        session.deck.setAutomatic(true).getOrThrow()
        session.setDetectForegroundApps(true)
        grant(true)
        session.attach(screen)
        fun finishPoll() { pending.removeFirst().run(); main.idle() }
        fun advance(ms: Long) = main.idleFor(Duration.ofMillis(ms))
        try {
            for (boundary in listOf("screen", "grant")) {
                main.idle()
                advance(10)
                event(camera, Event.MOVE_TO_FOREGROUND)
                advance(10)
                finishPoll()
                assertEquals(camera, session.signals.value[FOREGROUND_APP])
                advance(1_000) // Next read is already dispatched with the old reader.
                if (boundary == "screen") {
                    lock.setKeyguardLocked(true)
                    app.sendBroadcast(Intent(Intent.ACTION_SCREEN_OFF))
                    main.idle()
                    lock.setKeyguardLocked(false)
                    app.sendBroadcast(Intent(Intent.ACTION_USER_PRESENT))
                } else {
                    grant(false)
                    main.idle()
                    grant(true)
                }
                main.idle()
                assertNull(session.signals.value[FOREGROUND_APP])
                finishPoll()
                assertNull(session.signals.value[FOREGROUND_APP])
                advance(1_000)
            }
        } finally { session.detach(screen) }
    }
}
