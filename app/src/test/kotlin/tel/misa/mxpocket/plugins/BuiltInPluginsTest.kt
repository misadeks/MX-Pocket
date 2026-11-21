package tel.misa.mxpocket.plugins

import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.*
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class BuiltInPluginsTest {
    @Test fun focusActionsAndCountdownSurvivePageSwitchWithoutStaleTicks() = runTest {
        val actions = mutableListOf<PluginAction>()
        var updates = 0
        val engine = PluginEngine(BuiltInPlugins.create("Focus", "Audio", "Phone", "Clip Studio"), this,
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
        val engine = PluginEngine(BuiltInPlugins.create("Focus", "Audio", "Phone", "Clip Studio"), this,
            { testScheduler.currentTime }, { AudioSnapshot(6, 15) },
            { action, _ -> actions += action }, {}, { _, error -> throw AssertionError(error) })
        fun tap(index: Int) {
            engine.key(PluginKeyEvent(index, true))
            engine.key(PluginKeyEvent(index, false))
        }
        engine.setEnabled(true)
        engine.next(1)
        assertEquals("6 / 15", engine.render().keys[7].label)
        for (index in listOf(0, 1, 2, 3, 5, 6, 8)) tap(index)
        assertEquals(listOf(
            PluginAction.Volume(VolumeChange.LOWER), PluginAction.Media(MediaCommand.PLAY_PAUSE),
            PluginAction.Volume(VolumeChange.RAISE), PluginAction.Media(MediaCommand.PREVIOUS),
            PluginAction.Media(MediaCommand.NEXT), PluginAction.Volume(VolumeChange.MUTE),
            PluginAction.Volume(VolumeChange.UNMUTE),
        ), actions)
        actions.clear()
        engine.next(1)
        for (index in listOf(0, 1, 2, 3, 5, 8)) tap(index)
        assertEquals(listOf(PhoneApp.CAMERA, PhoneApp.SETTINGS, PhoneApp.DIALER, PhoneApp.WEB, PhoneApp.ALARMS),
            actions.filterIsInstance<PluginAction.OpenApp>().map { it.app })
        assertEquals(PluginAction.Repaint, actions.last())
        tap(6)
        assertEquals("focus", engine.selectedId)
        engine.next(-1)
        assertEquals("clipstudio", engine.selectedId)
        engine.next(-1)
        assertEquals("phone", engine.selectedId)
        tap(7)
        assertEquals("audio", engine.selectedId)
        engine.setEnabled(false)
        engine.close()
    }
}
