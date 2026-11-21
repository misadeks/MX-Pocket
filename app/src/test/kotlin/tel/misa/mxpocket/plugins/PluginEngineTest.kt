package tel.misa.mxpocket.plugins

import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withContext
import org.junit.Assert.*
import org.junit.Test
import tel.misa.mxpocket.plugins.examples.CounterPlugin

@OptIn(ExperimentalCoroutinesApi::class)
class PluginEngineTest {
    private class Probe(id: String, capabilities: Set<PluginCapability> = emptySet()) : PocketPlugin {
        override val descriptor = PluginDescriptor(id, id, capabilities)
        lateinit var host: PluginHost
        val events = mutableListOf<PluginKeyEvent>()
        var stopped = 0
        var action: PluginAction? = null
        override fun onActivate(host: PluginHost) { this.host = host }
        override fun render(nowMillis: Long) = frame(List(9) { "Probe" }, descriptor.id, 0xff000000.toInt())
        override fun onKey(event: PluginKeyEvent, host: PluginHost) {
            events += event
            if (event.pressed) action?.let(host::dispatch)
        }
        override fun onDeactivate() { stopped++ }
    }

    private class Fixture(scope: TestScope, plugins: List<PocketPlugin>) {
        val actions = mutableListOf<PluginAction>()
        val errors = mutableListOf<Pair<String, Throwable>>()
        var updates = 0
        val engine = PluginEngine(plugins, scope, { scope.testScheduler.currentTime },
            { AudioSnapshot(4, 15) }, { action, _ -> actions += action },
            { updates++ }, { id, error -> errors += id to error })
    }

    @Test fun validatesRegistrationAndNineKeyContract() = runTest {
        assertThrows(IllegalArgumentException::class.java) { PluginDescriptor("Bad ID", "Test") }
        assertThrows(IllegalArgumentException::class.java) { PluginFrame(emptyList()) }
        assertThrows(IllegalArgumentException::class.java) { Fixture(this, listOf(Probe("one"), Probe("one"))) }
        val fixture = Fixture(this, listOf(Probe("one")))
        assertThrows(IllegalArgumentException::class.java) { fixture.engine.select("unknown") }
        fixture.engine.close()
    }

    @Test fun switchReleasesHeldKeysCancelsTasksAndSuppressesOldHostEffects() = runTest {
        val one = Probe("one", setOf(PluginCapability.AUDIO))
        val two = Probe("two")
        val fixture = Fixture(this, listOf(one, two))
        fixture.engine.setEnabled(true)
        fixture.engine.key(PluginKeyEvent(0, true))
        fixture.engine.key(PluginKeyEvent(0, true))
        var cleaned = false
        one.host.scope.launch {
            try { delay(1000) } finally { cleaned = true }
        }
        one.host.scope.launch {
            withContext(NonCancellable) {
                delay(2000)
                one.host.dispatch(PluginAction.Volume(VolumeChange.RAISE))
                one.host.invalidate()
            }
        }
        runCurrent()
        fixture.engine.select("two")
        runCurrent()
        assertTrue(cleaned)
        assertEquals(listOf(PluginKeyEvent(0, true), PluginKeyEvent(0, false, true)), one.events)
        assertEquals(1, one.stopped)
        fixture.engine.key(PluginKeyEvent(0, false))
        assertTrue(two.events.isEmpty())
        val afterSwitch = fixture.updates
        advanceTimeBy(2100)
        runCurrent()
        assertEquals(afterSwitch, fixture.updates)
        assertTrue(fixture.actions.isEmpty())
        fixture.engine.close()
    }

    @Test fun disconnectSuppressesInputAndAllowsCleanReactivation() = runTest {
        val probe = Probe("one")
        val fixture = Fixture(this, listOf(probe))
        fixture.engine.setEnabled(true)
        val old = probe.host
        fixture.engine.key(PluginKeyEvent(8, true))
        fixture.engine.setEnabled(false)
        fixture.engine.key(PluginKeyEvent(1, true))
        assertEquals(2, probe.events.size)
        val paused = fixture.updates
        old.invalidate()
        assertEquals(paused, fixture.updates)
        fixture.engine.setEnabled(true)
        fixture.engine.key(PluginKeyEvent(8, true))
        assertEquals(3, probe.events.size)
        fixture.engine.close()
    }

    @Test fun capabilityAndAsyncFailuresAreReportedAndPluginStops() = runTest {
        val probe = Probe("one")
        val fixture = Fixture(this, listOf(probe))
        fixture.engine.setEnabled(true)
        probe.action = PluginAction.OpenApp(PhoneApp.CAMERA)
        fixture.engine.key(PluginKeyEvent(0, true))
        assertTrue(fixture.actions.isEmpty())
        assertEquals("one", fixture.errors.single().first)
        assertEquals("Plugin error", fixture.engine.render().keys.first().label)
        fixture.engine.select("one")
        probe.host.scope.launch { error("Example task failure") }
        runCurrent()
        assertEquals(2, fixture.errors.size)
        assertTrue(probe.stopped >= 2)
        fixture.engine.close()
    }

    @Test fun counterExampleRendersInputAndUpdatesOnlyDuringActivation() = runTest {
        val example = CounterPlugin()
        val fixture = Fixture(this, listOf(example, Probe("other")))
        fixture.engine.setEnabled(true)
        fixture.engine.key(PluginKeyEvent(2, true))
        assertEquals("1", fixture.engine.render().keys[2].label)
        val heldColor = fixture.engine.render().keys[2].background
        fixture.engine.key(PluginKeyEvent(2, false))
        assertNotEquals(heldColor, fixture.engine.render().keys[2].background)
        fixture.engine.chord(KeyChord(0, 8))
        assertTrue(fixture.engine.render().keys.all { it.label == "0" })
        runCurrent()
        advanceTimeBy(1100)
        runCurrent()
        assertEquals("1s", fixture.engine.render().keys[2].footer)
        fixture.engine.select("other")
        advanceTimeBy(3000)
        fixture.engine.select("example.counter")
        assertEquals("1s", fixture.engine.render().keys[2].footer)
        fixture.engine.close()
    }
}
