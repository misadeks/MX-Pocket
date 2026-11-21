package tel.misa.mxpocket.plugins

import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.*
import org.junit.Test
import tel.misa.mxpocket.decks.*

@OptIn(ExperimentalCoroutinesApi::class)
class DeckPluginTest {
    @Test fun favoritePagesUseComponentIdentityAndRereadSelection() = runTest {
        val apps = (1..12).map { Favorite("sample/.App$it", "App $it") }
        val states = MutableStateFlow(DeckState(favorites = apps))
        val actions = mutableListOf<PluginAction>()
        val engine = PluginEngine(BuiltInPlugins.create("Focus", "Media", "Phone"), this, { 0L },
            { AudioSnapshot(0, 0) }, { action, _ -> actions.add(action) }, {}, { _, e -> throw e }, states)
        engine.select("launcher")
        engine.setEnabled(true)
        fun tap(i: Int) { engine.key(PluginKeyEvent(i, true)); engine.key(PluginKeyEvent(i, false)) }
        assertEquals(apps[0].component, engine.render().keys[0].iconComponent)
        tap(8)
        val tile = engine.render().keys.first()
        assertEquals("App 8", tile.label)
        assertNotEquals(states.value.tile(DeckCommand.OPEN, apps[0].component).inputToken, tile.inputToken)
        tap(0)
        assertEquals(PluginAction.Deck(DeckCommand.OPEN, apps[7].component), actions.single())
        states.value = DeckState()
        runCurrent()
        tap(0)
        assertEquals(1, actions.size)
        assertEquals("No favorites", engine.render().keys.first().label)
        tap(7)
        assertEquals(PluginAction.Deck(DeckCommand.EDIT), actions.last())
        engine.close()
    }

    @Test fun studioHasSeparateOpenAndInputKeysAndNeverInfersRecordingFromAPress() = runTest {
        val states = MutableStateFlow(DeckState())
        val actions = mutableListOf<PluginAction>()
        val engine = PluginEngine(BuiltInPlugins.create("Focus", "Media", "Phone"), this, { 0L },
            { AudioSnapshot(0, 0) }, { action, _ -> actions.add(action) }, {}, { _, e -> throw e }, states)
        engine.select("studio"); engine.setEnabled(true)
        val idle = engine.render().keys[1]
        assertEquals("Record", idle.label)
        engine.key(PluginKeyEvent(1, true))
        engine.key(PluginKeyEvent(1, true))
        assertEquals(idle, engine.render().keys[1])
        assertEquals("Stop", engine.render().keys[2].label)
        assertEquals("Open Camera", engine.render().keys[7].label)
        assertFalse(states.value.tile(DeckCommand.RECORD).enabled) // Old saved capture action.
        engine.select("navigation"); engine.key(PluginKeyEvent(1, false))
        assertEquals(listOf(PluginAction.Deck(DeckCommand.START_VIDEO)), actions)
        engine.close()
    }
}
