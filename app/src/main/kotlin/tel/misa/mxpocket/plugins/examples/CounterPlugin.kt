package tel.misa.mxpocket.plugins.examples

import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import tel.misa.mxpocket.plugins.KeyChord
import tel.misa.mxpocket.plugins.KeyTile
import tel.misa.mxpocket.plugins.PluginDescriptor
import tel.misa.mxpocket.plugins.PluginFrame
import tel.misa.mxpocket.plugins.PluginHost
import tel.misa.mxpocket.plugins.PluginKeyEvent
import tel.misa.mxpocket.plugins.PocketPlugin

/** Register CounterPlugin() in BuiltInPlugins.create() to enable this working authoring example. */
class CounterPlugin : PocketPlugin {
    private val reset = KeyChord(0, 8)
    override val descriptor = PluginDescriptor("example.counter", "Counter", chords = setOf(reset))
    private val counts = IntArray(9)
    private val pressed = BooleanArray(9)
    private var elapsed = 0

    override fun onActivate(host: PluginHost) {
        host.scope.launch {
            while (true) {
                delay(1000)
                elapsed++
                host.invalidate()
            }
        }
    }

    override fun render(nowMillis: Long) = PluginFrame((0..8).map { index ->
        KeyTile("${counts[index]}", "${elapsed}s",
            if (pressed[index]) 0xff7c4b19.toInt() else 0xff234d70.toInt())
    })

    override fun onKey(event: PluginKeyEvent, host: PluginHost) {
        pressed[event.index] = event.pressed
        if (event.pressed && !event.synthetic) counts[event.index]++
        host.invalidate()
    }

    override fun onChord(chord: KeyChord, host: PluginHost) {
        if (chord == reset) counts.fill(0)
    }

    override fun onDeactivate() {
        pressed.fill(false)
        // The host cancels the ticking coroutine before cleanup. No USB resources are plugin-owned.
    }
}
