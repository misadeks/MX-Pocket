package tel.misa.mxpocket.plugins.clipstudio

import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runTest
import org.junit.Assert.*
import org.junit.Test
import tel.misa.mxpocket.plugins.AccessibilityCommandProbe
import tel.misa.mxpocket.plugins.AudioSnapshot
import tel.misa.mxpocket.plugins.PluginAction
import tel.misa.mxpocket.plugins.PluginCapability
import tel.misa.mxpocket.plugins.PluginDescriptor
import tel.misa.mxpocket.plugins.PluginEngine
import tel.misa.mxpocket.plugins.PluginHost
import tel.misa.mxpocket.plugins.PluginKeyEvent
import tel.misa.mxpocket.plugins.PocketPlugin
import tel.misa.mxpocket.plugins.frame

@OptIn(ExperimentalCoroutinesApi::class)
class ClipStudioPluginTest {
    private class Probe(id: String) : PocketPlugin {
        override val descriptor = PluginDescriptor(id, id)
        override fun render(nowMillis: Long) = frame(List(9) { "Probe" }, descriptor.id, 0xff000000.toInt())
        override fun onKey(event: PluginKeyEvent, host: PluginHost) {}
    }

    private class Fixture(scope: TestScope, plugins: List<PocketPlugin>) {
        val actions = mutableListOf<PluginAction>()
        val errors = mutableListOf<Pair<String, Throwable>>()
        val engine = PluginEngine(plugins, scope, { scope.testScheduler.currentTime }, { AudioSnapshot(0, 15) },
            { action, _ -> actions += action }, {}, { id, error -> errors += id to error })
        fun tap(index: Int) {
            engine.key(PluginKeyEvent(index, true))
            engine.key(PluginKeyEvent(index, false))
        }
    }

    private fun fixture(scope: TestScope) = Fixture(scope,
        listOf(Probe("focus"), Probe("phone"), ClipStudioPlugin("Clip Studio")))

    @Test fun toolPageListsFourToolsAndSelectsOneIntoItsSubmenu() = runTest {
        val fixture = fixture(this)
        fixture.engine.setEnabled(true)
        fixture.engine.select("clipstudio")
        val tools = fixture.engine.render().keys
        assertEquals(9, tools.size)
        assertEquals(listOf("Pencil", "Lasso", "Bucket", "Eraser", "Settings", "Pencil", "Focus", "Phone", "Repaint"),
            tools.map { it.label })
        assertEquals(List(9) { "CLIP STUDIO" }, tools.map { it.footer })
        fixture.tap(3) // Eraser
        assertEquals(listOf(PluginAction.AccessibilityCommand("clipstudio.tool.eraser")), fixture.actions)
        val settings = fixture.engine.render().keys
        assertEquals(listOf("‹ Tools", "Size −", "Size +", "Op −", "Op +", "Hardness −", "Hardness +", "S20 O100 E0", "Eraser"),
            settings.map { it.label })
        assertEquals(List(9) { "Eraser" }, settings.map { it.footer })
        fixture.engine.close()
    }

    @Test fun settingsStepsDispatchCommandsUpdateReadoutAndClamp() = runTest {
        val fixture = fixture(this)
        fixture.engine.setEnabled(true)
        fixture.engine.select("clipstudio")
        fixture.tap(0) // Pencil -> submenu
        fixture.actions.clear()
        fixture.tap(2) // Size +
        fixture.tap(4) // Op + (already 100 -> clamped, no command)
        fixture.tap(3) // Op -
        fixture.tap(6) // Stabilizer +
        assertEquals(listOf(
            PluginAction.AccessibilityCommand("clipstudio.size.step:+2"),
            PluginAction.AccessibilityCommand("clipstudio.opacity.step:-5"),
            PluginAction.AccessibilityCommand("clipstudio.extra.step:+5"),
        ), fixture.actions)
        assertEquals("S22 O95 E5", fixture.engine.render().keys[7].label)
        repeat(200) { fixture.tap(2) } // Size up to the 200 clamp
        val sizeActions = fixture.actions.count { it == PluginAction.AccessibilityCommand("clipstudio.size.step:+2") }
        fixture.tap(2) // At the limit: no further command
        assertEquals(sizeActions, fixture.actions.count { it == PluginAction.AccessibilityCommand("clipstudio.size.step:+2") })
        assertEquals("S200 O95 E5", fixture.engine.render().keys[7].label)
        fixture.tap(0) // Back to the tool page
        assertEquals("CLIP STUDIO", fixture.engine.render().keys[0].footer)
        fixture.engine.close()
    }

    @Test fun navigationRepaintAndSyntheticEventsBehave() = runTest {
        val fixture = fixture(this)
        fixture.engine.setEnabled(true)
        fixture.engine.select("clipstudio")
        fixture.tap(8)
        assertEquals(listOf(PluginAction.Repaint), fixture.actions)
        fixture.tap(6)
        assertEquals("focus", fixture.engine.selectedId)
        fixture.engine.select("clipstudio")
        fixture.tap(7)
        assertEquals("phone", fixture.engine.selectedId)
        fixture.engine.select("clipstudio")
        fixture.actions.clear()
        fixture.engine.key(PluginKeyEvent(1, true))
        fixture.engine.key(PluginKeyEvent(1, false, synthetic = true))
        fixture.engine.key(PluginKeyEvent(1, false))
        assertEquals(listOf(PluginAction.AccessibilityCommand("clipstudio.tool.lasso")), fixture.actions)
        fixture.engine.close()
    }

    @Test fun stateSurvivesPageSwitchAndReentersOnTheToolPage() = runTest {
        val fixture = fixture(this)
        fixture.engine.setEnabled(true)
        fixture.engine.select("clipstudio")
        fixture.tap(1) // Lasso -> submenu
        fixture.tap(2) // Size +
        fixture.engine.select("focus")
        fixture.engine.select("clipstudio")
        assertEquals("CLIP STUDIO", fixture.engine.render().keys[0].footer) // tool page again
        assertEquals("Lasso", fixture.engine.render().keys[5].label) // selection kept
        fixture.tap(4) // Settings of the retained tool
        val settings = fixture.engine.render().keys
        assertEquals("S22 O100 E0", settings[7].label)
        assertEquals("Expand −", settings[5].label)
        fixture.engine.close()
    }

    @Test fun accessibilityCommandsRequireTheDeclaredCapability() = runTest {
        val probe = AccessibilityCommandProbe("probe.noaccess")
        val fixture = Fixture(this, listOf(probe, Probe("other")))
        fixture.engine.setEnabled(true)
        fixture.engine.key(PluginKeyEvent(0, true))
        assertTrue(fixture.actions.isEmpty())
        assertEquals("probe.noaccess", fixture.errors.single().first)
        assertEquals("Plugin error", fixture.engine.render().keys.first().label)
        fixture.engine.close()
    }

    @Test fun builtInRotationIncludesClipStudioLast() = runTest {
        val fixture = Fixture(this, tel.misa.mxpocket.plugins.BuiltInPlugins.create("Focus", "Audio", "Phone", "Clip Studio"))
        assertEquals(listOf("focus", "audio", "phone", "clipstudio"), fixture.engine.descriptors.map { it.id })
        fixture.engine.setEnabled(true)
        repeat(3) { fixture.engine.next(1) }
        assertEquals("clipstudio", fixture.engine.selectedId)
        assertTrue(fixture.engine.descriptors.single { it.id == "clipstudio" }.capabilities
            .contains(PluginCapability.ACCESSIBILITY))
        fixture.engine.close()
    }
}
