package tel.misa.mxpocket.plugins

import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.*
import org.junit.Test
import kotlinx.coroutines.flow.MutableStateFlow
import tel.misa.mxpocket.decks.*

@OptIn(ExperimentalCoroutinesApi::class)
class BuiltInPluginsTest {
    @Test fun builtInKeysCarryArtworkIconsExceptTheCountdownReadout() {
        val focus = FocusPlugin("Focus").render(0).keys
        assertEquals(listOf("▶️", "", "🔄", "➖", "⏱️", "➕", "🕒", "🕔", "🕙"), focus.map { it.icon })
        assertEquals("Start", focus[0].label)
        assertEquals("25:00", focus[1].label)
        val phone = PhonePlugin("Phone").render(0).keys
        assertTrue(phone.all { it.icon.isNotBlank() })
        assertEquals("📷", phone[0].icon)
    }

    @Test fun deckCommandIconsFollowPlaybackAndRecordingState() {
        assertEquals("▶️", DeckState().tile(DeckCommand.PLAY).icon)
        assertEquals("⏸️", DeckState(media = MediaDeckState(playing = true)).tile(DeckCommand.PLAY).icon)
        assertEquals("🔴", DeckState(studio = StudioState(recording = true)).tile(DeckCommand.RECORD).icon)
        // Favorites keep the installed app's icon instead of an emoji.
        assertEquals("", DeckState(favorites = listOf(Favorite("a/.B", "B"))).tile(DeckCommand.OPEN, "a/.B").icon)
        DeckCommand.entries.filter { it != DeckCommand.OPEN }.forEach { assertTrue(it.name, it.emoji.isNotBlank()) }
    }

    @Test fun focusActionsAndCountdownSurvivePageSwitchWithoutStaleTicks() = runTest {
        val actions = mutableListOf<PluginAction>()
        var updates = 0
        val engine = PluginEngine(BuiltInPlugins.create("Focus", "Audio", "Phone"), this,
            { testScheduler.currentTime }, { AudioSnapshot(5, 15) },
            { action, _ -> actions += action }, { updates++ }, { _, error -> throw AssertionError(error) })
        fun tap(index: Int) {
            engine.key(PluginKeyEvent(index, true))
            engine.key(PluginKeyEvent(index, false))
        }
        engine.setEnabled(true)
        tap(6) // 15 minutes
        tap(0)
        runCurrent()
        advanceTimeBy(1000)
        runCurrent()
        assertEquals("14:59", engine.render().keys[1].label)
        engine.select("phone")
        val awayUpdates = updates
        advanceTimeBy(15 * 60_000L)
        runCurrent()
        assertEquals(awayUpdates, updates)
        assertFalse(actions.contains(PluginAction.CompletionTone))
        engine.select("focus")
        runCurrent()
        assertEquals(listOf(PluginAction.CompletionTone), actions)
        assertEquals("00:00", engine.render().keys[1].label)
        tap(2)
        assertEquals("15:00", engine.render().keys[1].label)
        engine.close()
    }

    @Test fun audioAndPhoneKeepExistingControlsAndNavigation() = runTest {
        val actions = mutableListOf<PluginAction>()
        val engine = PluginEngine(BuiltInPlugins.create("Focus", "Audio", "Phone"), this,
            { testScheduler.currentTime }, { AudioSnapshot(6, 15) },
            { action, _ -> actions += action }, {}, { _, error -> throw AssertionError(error) },
            MutableStateFlow(DeckState(media = MediaDeckState(volume = 6, maximum = 15))))
        fun tap(index: Int) {
            engine.key(PluginKeyEvent(index, true))
            engine.key(PluginKeyEvent(index, false))
        }
        engine.setEnabled(true)
        engine.next(1)
        assertEquals("6 / 15", engine.render().keys[7].label)
        for (index in listOf(0, 1, 2, 3, 5, 6, 8)) tap(index)
        assertEquals(listOf(
            DeckCommand.QUIETER, DeckCommand.PLAY, DeckCommand.LOUDER, DeckCommand.PREVIOUS,
            DeckCommand.NEXT, DeckCommand.MUTE, DeckCommand.UNMUTE,
        ), actions.filterIsInstance<PluginAction.Deck>().map { it.command })
        actions.clear()
        engine.next(1)
        for (index in listOf(0, 1, 2, 3, 5, 8)) tap(index)
        assertEquals(listOf(PhoneApp.CAMERA, PhoneApp.SETTINGS, PhoneApp.DIALER, PhoneApp.WEB, PhoneApp.ALARMS),
            actions.filterIsInstance<PluginAction.OpenApp>().map { it.app })
        assertEquals(PluginAction.Repaint, actions.last())
        tap(6)
        assertEquals("focus", engine.selectedId)
        engine.next(2)
        assertEquals("phone", engine.selectedId)
        tap(7)
        assertEquals("audio", engine.selectedId)
        engine.setEnabled(false)
        engine.close()
    }
}
