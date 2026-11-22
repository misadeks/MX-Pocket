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

    @Test fun oneNotePageMapsAllNineToolsInToolbarOrder() = runTest {
        val actions = mutableListOf<PluginAction>()
        val descriptions = listOf(
            "Green color, thickness 10.00 percent, pen options",
            "Red color, thickness 10.00 percent, pen options",
            "Light Green color, thickness 14.00 percent, pen options",
            "Dark Blue color, thickness 20.00 percent, pen options",
            "Yellow color, thickness 20.00 percent, highlighter options",
            "Aqua color, thickness 20.00 percent, highlighter options",
            "Stroke Eraser", "Shape select", "Lasso select",
        )
        var toolbar = (listOf("More options") + descriptions).map { it to it }
        val engine = PluginEngine(BuiltInPlugins.create("Focus", "Audio", "Phone"), this,
            { testScheduler.currentTime }, { AudioSnapshot(0, 15) },
            { action, _ -> actions += action }, {}, { _, error -> throw AssertionError(error) },
            MutableStateFlow(DeckState()),
            readActiveTool = { descriptions[6] }, readToolBar = { toolbar })
        fun tap(index: Int) {
            engine.key(PluginKeyEvent(index, true))
            engine.key(PluginKeyEvent(index, false))
        }
        engine.setEnabled(true)
        engine.select("onenote")
        runCurrent()
        val tiles = engine.render().keys
        assertEquals(listOf("Green pen", "Red pen", "Light Green pen", "Dark Blue pen",
            "Yellow highl", "Aqua highl", "Stroke Eraser", "Shape select", "Lasso select"),
            tiles.map { it.label })
        assertTrue(tiles.all { it.enabled })
        assertEquals(0xffa56eff.toInt(), tiles[6].background)
        assertEquals(0xff7719aa.toInt(), tiles[0].background)
        (0..8).forEach(::tap)
        assertEquals(descriptions.map { PluginAction.AccessibilityClick(it) }, actions)

        toolbar = emptyList()
        testScheduler.advanceTimeBy(250)
        runCurrent()
        assertEquals(9, engine.render().keys.size)
        assertTrue(engine.render().keys.none { it.enabled })
        actions.clear()
        (0..8).forEach(::tap)
        assertTrue(actions.isEmpty())
        engine.close()
    }
    @Test fun oneNoteSettingsNavigateColorsAndAdjustThickness() = runTest {
        val actions = mutableListOf<PluginAction>()
        val colors = listOf("Yellow", "Orange", "Pink", "Red", "Indigo", "Purple", "Plum")
        val pen = "Green color, thickness 10.00 percent, pen options"
        var settings: OneNoteSettings? = null
        val engine = PluginEngine(BuiltInPlugins.create("Focus", "Audio", "Phone"), this,
            { testScheduler.currentTime }, { AudioSnapshot(0, 15) },
            { action, _ -> actions += action; if (action is PluginAction.AccessibilityClick)
                settings = OneNoteSettings(colors, "Green", 10f, 0f, 100f) },
            {}, { _, error -> throw AssertionError(error) }, MutableStateFlow(DeckState()),
            readActiveTool = { pen }, readToolBar = { listOf(pen to pen) },
            readOneNoteSettings = { settings })
        fun tap(index: Int) { engine.key(PluginKeyEvent(index, true)); engine.key(PluginKeyEvent(index, false)) }
        engine.select("onenote"); engine.setEnabled(true); runCurrent()
        tap(0); testScheduler.advanceTimeBy(301); runCurrent()
        assertEquals("Thickness", engine.render().keys[1].label)
        assertEquals(0.1f, engine.render().keys[1].levelFraction)
        tap(0); tap(2)
        assertEquals(listOf(PluginAction.OneNoteSetting(thicknessDelta = -1),
            PluginAction.OneNoteSetting(thicknessDelta = 1)), actions.takeLast(2))
        tap(4)
        assertEquals("Yellow", engine.render().keys[0].label)
        assertEquals("1 / 2", engine.render().keys[7].label)
        tap(8)
        assertEquals("Plum", engine.render().keys[0].label)
        assertEquals("2 / 2", engine.render().keys[7].label)
        tap(7)
        assertEquals("Color", engine.render().keys[4].label)
        tap(4); tap(8); tap(0)
        assertEquals(PluginAction.OneNoteSetting(color = "Plum"), actions.last())
        engine.close()
    }

}
