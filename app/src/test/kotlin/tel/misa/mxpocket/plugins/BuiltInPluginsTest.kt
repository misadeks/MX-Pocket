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
    @Test fun focusActionsAndCountdownSurvivePageSwitchWithoutStaleTicks() = runTest {
        val actions = mutableListOf<PluginAction>()
        var updates = 0
        val engine = PluginEngine(BuiltInPlugins.create("Focus", "Audio", "Phone", oneNoteTitle = "OneNote"), this,
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
        val states = MutableStateFlow(DeckState(media = MediaDeckState(volume = 6, maximum = 15)))
        val engine = PluginEngine(BuiltInPlugins.create("Focus", "Audio", "Phone"), this,
            { testScheduler.currentTime }, { AudioSnapshot(6, 15) },
            { action, _ -> actions += action }, {}, { _, error -> throw AssertionError(error) },
            states)
        fun tap(index: Int) {
            engine.key(PluginKeyEvent(index, true))
            engine.key(PluginKeyEvent(index, false))
        }
        engine.setEnabled(true)
        engine.next(1)
        assertEquals("6 / 15", engine.render().keys[1].footer)
        assertEquals("Media keys", engine.render().keys[7].label)
        for (index in 0..8) tap(index)
        assertEquals(listOf(
            DeckCommand.QUIETER, DeckCommand.MUTE, DeckCommand.LOUDER, DeckCommand.PREVIOUS,
            DeckCommand.PLAY, DeckCommand.NEXT,
        ), actions.filterIsInstance<PluginAction.Deck>().map { it.command })
        actions.clear()
        states.value = DeckState(media = MediaDeckState(access = true, playing = true, muted = true,
            supported = setOf(DeckCommand.BACK_10, DeckCommand.FORWARD_10)))
        runCurrent()
        val frame = engine.render()
        assertEquals(PlayerGlyph.PAUSE, frame.keys[4].glyph)
        assertEquals(PlayerGlyph.UNMUTE, frame.keys[1].glyph)
        assertFalse(frame.keys[4].enabled)
        listOf(1,4,6,7,8).forEach(::tap)
        assertEquals(listOf(DeckCommand.UNMUTE, DeckCommand.BACK_10, DeckCommand.FORWARD_10),
            actions.filterIsInstance<PluginAction.Deck>().map { it.command })
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

    @Test fun oneNotePageMapsToolbarToolsWithoutActiveToolOrMoreOptions() = runTest {
        val actions = mutableListOf<PluginAction>()
        val toolbar = listOf(
            "Red color, thickness 10.00 percent, pen options" to "Red color, thickness 10.00 percent, pen options",
            "Yellow color, thickness 20.00 percent, highlighter options" to "Yellow color, thickness 20.00 percent, highlighter options",
            "Stroke Eraser" to "Stroke Eraser",
            "More options" to "More options",
        )
        val engine = PluginEngine(BuiltInPlugins.create("Focus", "Audio", "Phone", oneNoteTitle = "OneNote"), this,
            { testScheduler.currentTime }, { AudioSnapshot(0, 15) },
            { action, _ -> actions += action }, {}, { _, error -> throw AssertionError(error) },
            MutableStateFlow(DeckState()),
            readActiveTool = { toolbar[2].second }, readToolBar = { toolbar })
        fun tap(index: Int) {
            engine.key(PluginKeyEvent(index, true))
            engine.key(PluginKeyEvent(index, false))
        }
        engine.setEnabled(true)
        engine.select("onenote")
        runCurrent()
        val tiles = engine.render().keys
        assertEquals(9, tiles.size)
        assertEquals("", tiles[0].label) // top-left slot is OneNote's current-tool preview: blank
        assertFalse(tiles[0].enabled)
        assertEquals("Yellow highl", tiles[1].label) // toolbar minus preview and More options
        assertEquals("Stroke Eraser", tiles[2].label)
        assertEquals(0xffa56eff.toInt(), tiles[2].background) // active tool keeps its slot, highlighted
        assertEquals(0xff7719aa.toInt(), tiles[1].background)
        assertEquals("", tiles[3].label)
        // Nine toolbar tools must not overflow the grid past the blank top-left slot.
        val full = (toolbar + List(6) { "Pen ${it + 5} color, thickness 1$it.00 percent, pen options"
            .let { "$it x" to "$it x" } })
        val engine2 = PluginEngine(BuiltInPlugins.create("Focus", "Audio", "Phone", "OneNote"), this,
            { testScheduler.currentTime }, { AudioSnapshot(0, 15) },
            { _, _ -> }, {}, { _, error -> throw AssertionError(error) },
            MutableStateFlow(DeckState()), readActiveTool = { null }, readToolBar = { full })
        engine2.setEnabled(true)
        engine2.select("onenote")
        runCurrent()
        assertEquals(9, engine2.render().keys.size)
        assertEquals("", engine2.render().keys[0].label)
        engine2.close()
        tap(1)
        assertEquals(listOf(PluginAction.AccessibilityClick(toolbar[1].first)), actions)
        actions.clear()
        tap(0) // blank top-left key does nothing
        assertTrue(actions.isEmpty())
        engine.close()
    }
}
