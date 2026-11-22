package tel.misa.mxpocket.plugins

import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.*
import org.junit.Test
import kotlinx.coroutines.flow.MutableStateFlow
import tel.misa.mxpocket.decks.*
import tel.misa.mxpocket.i18n.AppLocale
import tel.misa.mxpocket.i18n.AppLanguage

@OptIn(ExperimentalCoroutinesApi::class)
class BuiltInPluginsTest {
    @Test fun localeChangesOnlyBuiltInPresentationNotMediaMetadataFavoritesOrTokens() {
        val language = AppLocale.current
        try {
            val favorite = Favorite("test.pkg/.Player", "Play")
            val state = DeckState(favorites = listOf(favorite), media = MediaDeckState(
                playing = false, title = "Playing", status = "Unrecognized player status",
            ))
            val descriptor = PluginDescriptor("phone", "Phone")
            AppLocale.update(AppLanguage.ENGLISH, emptyList())
            val english = state.tile(DeckCommand.PLAY)
            AppLocale.update(AppLanguage.SERBIAN_LATIN, emptyList())
            val serbian = state.tile(DeckCommand.PLAY)
            assertEquals("Pusti", serbian.label)
            assertEquals("Playing", serbian.footer)
            assertEquals(english.inputToken, serbian.inputToken)
            assertEquals("Play", state.tile(DeckCommand.OPEN, favorite.component).label)
            assertEquals("Unrecognized player status", state.media.displayStatus)
            assertEquals("Telefon", descriptor.displayTitle())
            assertEquals("Phone", descriptor.copy(id = "custom.plugin").displayTitle())
        } finally {
            AppLocale.update(language, emptyList())
        }
    }

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
        assertTrue(engine.render().keys[4].readOnly)
        for (index in listOf(0, 1, 2, 3, 4, 5, 8)) tap(index)
        assertEquals(listOf(PluginAction.OpenApp(PhoneApp.CAMERA), PluginAction.OpenApp(PhoneApp.SETTINGS),
            PluginAction.OpenApp(PhoneApp.DIALER), PluginAction.OpenApp(PhoneApp.WEB),
            PluginAction.OpenApp(PhoneApp.ALARMS), PluginAction.Repaint), actions)
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
        var foreground = true
        val engine = PluginEngine(BuiltInPlugins.create("Focus", "Audio", "Phone"), this,
            { testScheduler.currentTime }, { AudioSnapshot(0, 15) },
            { action, _ -> actions += action }, {}, { _, error -> throw AssertionError(error) },
            MutableStateFlow(DeckState()),
            readActiveTool = { descriptions[6] }, readToolBar = { toolbar }, readOneNoteForeground = { foreground })
        fun tap(index: Int) {
            engine.key(PluginKeyEvent(index, true))
            engine.key(PluginKeyEvent(index, false))
        }
        engine.setEnabled(true)
        engine.select("onenote")
        runCurrent()
        val tiles = engine.render().keys
        assertEquals(listOf("Green pen", "Red pen", "Light Green pen", "Dark Blue pen",
            "Yellow highlighter", "Aqua highlighter", "Stroke Eraser", "Shape select", "Lasso select"),
            tiles.map { it.label })
        assertTrue(tiles.all { it.enabled })
        assertEquals(KeySelection.ON, tiles[6].selection)
        assertEquals(KeySelection.OFF, tiles[0].selection)
        assertEquals(OneNoteLegend(OneNoteMark.HIGHLIGHTER, 0xffffff00.toInt(), 20f), tiles[4].glyph)
        (0..8).forEach(::tap)
        assertEquals(descriptions.map { PluginAction.AccessibilityClick(it) }, actions)

        toolbar = toolbar.reversed()
        testScheduler.advanceTimeBy(251); runCurrent()
        assertEquals(tiles.reversed(), engine.render().keys)
        tap(4)
        assertEquals(PluginAction.AccessibilityClick(descriptions[4]), actions.last())
        tap(8)
        assertEquals(PluginAction.AccessibilityClick(descriptions[0]), actions.last())
        assertEquals(KeySelection.OFF, engine.render().keys[8].selection)
        toolbar = emptyList()
        testScheduler.advanceTimeBy(250)
        runCurrent()
        assertTrue(engine.render().keys.none { it.enabled })
        tap(4)
        assertEquals(11, actions.size)
        foreground = false
        testScheduler.advanceTimeBy(250); runCurrent()
        val inactive = engine.render().keys
        assertEquals(tiles.reversed().mapIndexed { i, tile -> if (i == 4) "Open OneNote" else tile.label }, inactive.map { it.label })
        assertTrue(inactive[4].enabled)
        assertTrue(inactive.filterIndexed { i, _ -> i != 4 }.none { it.enabled })
        assertTrue(inactive.all { it.selection == null })
        assertEquals(0xff999da0.toInt(), (inactive[5].glyph as OneNoteLegend).ink)
        actions.clear()
        (0..8).forEach(::tap)
        assertEquals(listOf(PluginAction.OpenApp(PhoneApp.ONENOTE)), actions)
        toolbar = descriptions.map { it to it }
        testScheduler.advanceTimeBy(251); runCurrent()
        assertEquals(tiles, engine.render().keys)
        engine.close()
    }
    @Test fun partialToolDescriptionsAndAmbiguousSelectionsNeverInventInkOrState() {
        val faces = OneNoteFaces(null)
        val violet = "Unmapped violet color, thickness 200 percent, highlighter options"
        val pen = "thickness 2,5 percent, pen options"
        val unfamiliar = "Werkzeug options"
        val keys = faces.draw(listOf(violet, pen, unfamiliar, pen).map { it to it }, pen).keys
        assertEquals(OneNoteLegend(OneNoteMark.HIGHLIGHTER), keys[0].glyph)
        assertEquals(OneNoteLegend(OneNoteMark.PEN, widthPercent = 2.5f), keys[1].glyph)
        assertEquals(OneNoteLegend(OneNoteMark.TOOL), keys[2].glyph)
        assertTrue(keys.take(4).all { it.enabled && it.selection == KeySelection.UNKNOWN })
        assertTrue(keys.drop(4).all { !it.enabled && it.selection == null && it.glyph == null })
        val palette = faces.palette(OneNoteSettings(listOf("Blue", "Blue", "Alien"), "Blue", 0f, 0f, 1f,
            colorValues = listOf(0xff0000ff.toInt(), 0xff000080.toInt(), null)), 0)
        assertNull((palette.keys[2].glyph as OneNoteLegend).ink)
        assertNull((palette.keys[7].glyph as OneNoteLegend).ink)
        assertTrue(palette.keys.none { it.selection == KeySelection.ON })
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
        assertEquals(0xff008000.toInt(), (engine.render().keys[7].glyph as OneNoteLegend).ink)
        tap(8)
        assertEquals("Plum", engine.render().keys[0].label)
        assertEquals("2 / 2", engine.render().keys[7].label)
        tap(7)
        assertEquals("Color", engine.render().keys[4].label)
        tap(4); tap(8); tap(0)
        assertEquals(PluginAction.OneNoteSetting(color = "Plum"), actions.last())
        engine.close()
    }

    @Test fun oneNoteTextToolbarPagesAccentsColorsAndDraw() = runTest {
        val actions = mutableListOf<PluginAction>()
        val labels = listOf("Font size", "Bold", "Italic", "Underline", "Strikethrough", "Font color", "Highlight", "Bullets",
            "Numbering", "Indent −", "Indent +", "Align left", "Align center", "Align right", "Styles")
        var toolbar = labels.mapIndexed { i, label -> "text:${if (i == 1) "selected:" else ""}tool$i" to label }
        var settings: OneNoteSettings? = null
        val engine = PluginEngine(BuiltInPlugins.create("Focus", "Audio", "Phone"), this,
            { testScheduler.currentTime }, { AudioSnapshot(0, 15) },
            { action, _ -> actions += action }, {}, { _, error -> throw AssertionError(error) },
            MutableStateFlow(DeckState()), readToolBar = { toolbar }, readOneNoteSettings = { settings })
        fun tap(index: Int) { engine.key(PluginKeyEvent(index, true)); engine.key(PluginKeyEvent(index, false)) }
        engine.select("onenote"); engine.setEnabled(true); runCurrent()
        assertEquals(labels.take(8) + "Next", engine.render().keys.map { it.label })
        assertEquals(KeySelection.ON, engine.render().keys[1].selection)
        tap(0); assertEquals(PluginAction.AccessibilityClick("text:tool0"), actions.last())
        tap(8); assertEquals(labels.drop(8) + listOf("Draw", "Previous"), engine.render().keys.map { it.label })
        tap(7); assertEquals(PluginAction.AccessibilityClick("text:draw"), actions.last())
        settings = OneNoteSettings(listOf("Red", "Blue"), "Red", 0f, 0f, 1f, textColor = true)
        testScheduler.advanceTimeBy(251); runCurrent()
        assertEquals("Red", engine.render().keys[0].label)
        assertEquals(0xffff0000.toInt(), (engine.render().keys[7].glyph as OneNoteLegend).ink)
        tap(0); assertEquals(PluginAction.OneNoteSetting(color = "Red"), actions.last())
        tap(7); assertEquals(PluginAction.OneNoteSetting(close = true), actions.last())
        settings = null
        toolbar = listOf("Stroke Eraser" to "Stroke Eraser")
        testScheduler.advanceTimeBy(251); runCurrent()
        assertEquals("Stroke Eraser", engine.render().keys[0].label)
        engine.close()
    }
    @Test fun oneNoteStylesShowsVisibleChoicesAndNavigation() = runTest {
        val actions = mutableListOf<PluginAction>()
        val toolbar = listOf("style:Heading1" to "Heading1", "style:Heading2" to "Heading2",
            "" to "", "" to "", "" to "", "" to "", "style:previous" to "Previous",
            "style:cancel" to "Cancel", "style:next" to "Next")
        val engine = PluginEngine(BuiltInPlugins.create("Focus", "Audio", "Phone"), this,
            { testScheduler.currentTime }, { AudioSnapshot(0, 15) },
            { action, _ -> actions += action }, {}, { _, error -> throw AssertionError(error) },
            MutableStateFlow(DeckState()), readToolBar = { toolbar })
        fun tap(index: Int) { engine.key(PluginKeyEvent(index, true)); engine.key(PluginKeyEvent(index, false)) }
        engine.select("onenote"); engine.setEnabled(true); runCurrent()
        assertEquals("Heading1", engine.render().keys[0].label)
        assertFalse(engine.render().keys[2].enabled)
        listOf(0, 6, 7, 8).forEach(::tap)
        assertEquals(listOf("style:Heading1", "style:previous", "style:cancel", "style:next")
            .map { PluginAction.AccessibilityClick(it) }, actions)
        engine.close()
    }

    @Test fun oneNoteFontSizesAndDuplicateHighlightColors() = runTest {
        val actions = mutableListOf<PluginAction>()
        var toolbar = listOf("size:11" to "11", "size:12" to "12", "" to "", "" to "", "" to "", "" to "",
            "size:previous" to "Previous", "size:cancel" to "Cancel", "size:next" to "Next")
        var settings: OneNoteSettings? = null
        val engine = PluginEngine(BuiltInPlugins.create("Focus", "Audio", "Phone"), this,
            { testScheduler.currentTime }, { AudioSnapshot(0, 15) },
            { action, _ -> actions += action }, {}, { _, error -> throw AssertionError(error) },
            MutableStateFlow(DeckState()), readToolBar = { toolbar }, readOneNoteSettings = { settings })
        fun tap(index: Int) { engine.key(PluginKeyEvent(index, true)); engine.key(PluginKeyEvent(index, false)) }
        engine.select("onenote"); engine.setEnabled(true); runCurrent()
        assertEquals("Font size", engine.render().keys[0].footer)
        listOf(1, 6, 7, 8).forEach(::tap)
        assertEquals(listOf("size:12", "size:previous", "size:cancel", "size:next")
            .map { PluginAction.AccessibilityClick(it) }, actions)
        settings = OneNoteSettings(listOf("Blue", "Blue"), "Blue", 0f, 0f, 1f, textColor = true,
            colorIds = listOf("color:0:Blue", "color:1:Blue"),
            colorValues = listOf(0xff0000ff.toInt(), 0xff000080.toInt()), selectedIndex = 1)
        testScheduler.advanceTimeBy(251); runCurrent()
        val keys = engine.render().keys
        assertEquals(0xff0000ff.toInt(), (keys[0].glyph as OneNoteLegend).ink)
        assertEquals(0xff000080.toInt(), (keys[1].glyph as OneNoteLegend).ink)
        assertEquals(keys[1].glyph, keys[7].glyph)
        assertEquals(listOf(KeySelection.OFF, KeySelection.ON), keys.take(2).map { it.selection })
        tap(1); assertEquals(PluginAction.OneNoteSetting(color = "color:1:Blue"), actions.last())
        engine.close()
    }

    @Test fun oneNoteShapePickerPaginatesAndSelectsVisibleShape() = runTest {
        val actions = mutableListOf<PluginAction>()
        val shapes = (1..14).map { "shape:Shape $it" to "Shape $it" }
        val engine = PluginEngine(BuiltInPlugins.create("Focus", "Audio", "Phone"), this,
            { testScheduler.currentTime }, { AudioSnapshot(0, 15) },
            { action, _ -> actions += action }, {}, { _, error -> throw AssertionError(error) },
            MutableStateFlow(DeckState()), readToolBar = { shapes })
        fun tap(i: Int) { engine.key(PluginKeyEvent(i, true)); engine.key(PluginKeyEvent(i, false)) }
        engine.select("onenote"); engine.setEnabled(true); runCurrent()
        assertEquals("Shape 1", engine.render().keys[0].label)
        assertFalse(engine.render().keys[6].enabled)
        tap(8); assertEquals("Shape 7", engine.render().keys[0].label)
        tap(8); assertEquals("Shape 13", engine.render().keys[0].label)
        assertFalse(engine.render().keys[8].enabled)
        tap(1); assertEquals(PluginAction.AccessibilityClick("shape:Shape 14"), actions.last())
        tap(7); assertEquals(PluginAction.AccessibilityClick("shape:cancel"), actions.last())
        engine.close()
    }

    @Test fun oneNoteFontAdjustmentDoesNotDispatchASecondPhysicalAction() = runTest {
        val actions = mutableListOf<PluginAction>()
        val sizeToolbar = tel.misa.mxpocket.decks.fontSizeOptions(listOf("10", "11", "12", "14"), "11")
        val toolbar = sizeToolbar
        val engine = PluginEngine(BuiltInPlugins.create("Focus", "Audio", "Phone"), this,
            { testScheduler.currentTime }, { AudioSnapshot(0, 15) },
            { action, _ -> actions += action }, {}, { _, error -> throw AssertionError(error) }, MutableStateFlow(DeckState()), readToolBar = { toolbar })
        fun tap(i: Int) { engine.key(PluginKeyEvent(i, true)); engine.key(PluginKeyEvent(i, false)) }
        engine.select("onenote"); engine.setEnabled(true); runCurrent()
        assertEquals("11", engine.render().keys[1].label)
        assertTrue(engine.render().keys[1].readOnly)
        tap(2); tap(2)
        testScheduler.advanceTimeBy(1201); runCurrent()
        assertEquals(listOf(PluginAction.AccessibilityClick("size:up:12")), actions)
        engine.close()
    }

}
