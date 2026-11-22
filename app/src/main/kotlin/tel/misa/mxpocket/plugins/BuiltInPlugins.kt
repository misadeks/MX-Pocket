package tel.misa.mxpocket.plugins

import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import tel.misa.mxpocket.FocusTimer

/** Registration order is the page-button order. Add your compiled-in plugin here. */
object BuiltInPlugins {
    fun create(focusTitle: String, audioTitle: String, phoneTitle: String, xoTitle: String = TIC_TAC_TOE_TITLE, oneNoteTitle: String = "OneNote"): List<PocketPlugin> =
        listOf(FocusPlugin(focusTitle), DeckPlugin("audio", audioTitle, PluginCapability.AUDIO), PhonePlugin(phoneTitle),
            DeckPlugin("launcher", "App Launcher", PluginCapability.OPEN_APPS),
            DeckPlugin("studio", "Pocket Studio", PluginCapability.STUDIO),
            DeckPlugin("navigation", "Android Navigation", PluginCapability.SYSTEM_NAVIGATION),
            TicTacToePlugin(xoTitle), OneNotePlugin(oneNoteTitle))
}

class FocusPlugin(title: String) : PocketPlugin {
    override val descriptor = PluginDescriptor("focus", title, setOf(PluginCapability.TONE))
    private val timer = FocusTimer()

    override fun onActivate(host: PluginHost) {
        host.scope.launch {
            while (true) {
                if (timer.tick(host.nowMillis())) host.dispatch(PluginAction.CompletionTone)
                host.invalidate()
                delay(1000)
            }
        }
    }

    override fun render(nowMillis: Long) = timer.present(nowMillis)

    override fun onKey(event: PluginKeyEvent, host: PluginHost) {
        if (!event.pressed || event.synthetic) return
        when (event.index) {
            0 -> timer.toggle(host.nowMillis())
            2 -> timer.reset()
            3 -> timer.adjust(-5)
            5 -> timer.adjust(5)
            6 -> timer.select(15)
            7 -> timer.select(25)
            8 -> timer.select(50)
        }
        host.invalidate()
    }
}

class PhonePlugin(title: String) : PocketPlugin {
    override val descriptor = PluginDescriptor("phone", title,
        setOf(PluginCapability.OPEN_APPS, PluginCapability.NAVIGATION))

    override fun render(nowMillis: Long) = phoneFrame()

    override fun onKey(event: PluginKeyEvent, host: PluginHost) {
        if (!event.pressed || event.synthetic) return
        val action = when (event.index) {
            0 -> PluginAction.OpenApp(PhoneApp.CAMERA)
            1 -> PluginAction.OpenApp(PhoneApp.SETTINGS)
            2 -> PluginAction.OpenApp(PhoneApp.DIALER)
            3 -> PluginAction.OpenApp(PhoneApp.WEB)
            5 -> PluginAction.OpenApp(PhoneApp.ALARMS)
            6 -> PluginAction.SelectPlugin("focus")
            7 -> PluginAction.SelectPlugin("audio")
            8 -> PluginAction.Repaint
            else -> null
        }
        action?.let(host::dispatch)
    }
}

/**
 * OneNote page. Logi Options/Options+ ships no native OneNote profile on PC (custom keystrokes only),
 * so in-app tool keys click OneNote's visible toolbar controls through the opt-in accessibility
 * service; the rest uses the actions Android exposes for OneNote: launch intent, deep link,
 * OneNote on the web and Send-to-OneNote (ACTION_SEND), which creates a quick note.
 */
class OneNotePlugin(title: String) : PocketPlugin {
    override val descriptor = PluginDescriptor("onenote", title,
        setOf(PluginCapability.OPEN_APPS, PluginCapability.NAVIGATION, PluginCapability.ACCESSIBILITY))

    // The ink toolbar's tools as (description, description); empty outside the draw view.
    private var tools: List<Pair<String, String>> = emptyList()
    private var active: String? = null
    private var settingsFor: Pair<String, String>? = null

    override fun onActivate(host: PluginHost) {
        host.scope.launch {
            while (true) {
                tools = host.toolBar
                active = host.activeTool
                host.invalidate()
                delay(250)
            }
        }
    }

    override fun render(nowMillis: Long): PluginFrame {
        val settings = settingsFor
        if (settings != null) return settingsFrame(settings.second)
        val labels = MutableList(9) { "" }
        tools.take(8).forEachIndexed { i, tool -> labels[i] = shortLabel(tool.second) }
        labels[8] = if (tools.size > 8) shortLabel(tools[8].second) else "Repaint"
        return PluginFrame(labels.mapIndexed { index, label ->
            val isActive = tools.getOrNull(index)?.second == active
            KeyTile(label, "ONENOTE", if (isActive) 0xffa56eff.toInt() else 0xff7719aa.toInt(),
                enabled = label.isNotBlank())
        })
    }

    override fun onKey(event: PluginKeyEvent, host: PluginHost) {
        if (!event.pressed || event.synthetic) return
        val settings = settingsFor
        if (settings != null) {
            when (event.index) {
                0 -> settingsFor = null // back to the toolbar
                8 -> host.dispatch(PluginAction.AccessibilityClick(settings.first)) // settings in the app
            }
            return
        }
        val tool = tools.getOrNull(event.index)
        if (tool != null) {
            if (tool.second == active) settingsFor = tool // re-press: show settings on the keypad
            else host.dispatch(PluginAction.AccessibilityClick(tool.first))
            host.scope.launch {
                delay(300) // OneNote updates its accessibility tree shortly after the click.
                active = host.activeTool
                host.invalidate()
            }
        } else if (event.index == 8 && tools.size <= 8) {
            host.dispatch(PluginAction.Repaint)
        }
    }

    private fun settingsFrame(desc: String): PluginFrame {
        val match = Regex("^(.*?) color, thickness ([0-9.]+) percent").find(desc)
        val color = match?.groupValues?.get(1) ?: desc
        val thickness = match?.groupValues?.get(2)?.toFloatOrNull()?.let { "$it%" } ?: "-"
        val labels = mutableListOf("Back", shortLabel(desc), "Color", color, "Thickness", thickness, "", "", "In app")
        return PluginFrame(labels.mapIndexed { index, label ->
            KeyTile(label, "ONENOTE", 0xff7719aa.toInt(), enabled = label.isNotBlank() && index != 6 && index != 7)
        })
    }

    private fun shortLabel(desc: String): String = when {
        "pen options" in desc -> "${desc.substringBefore(" color")} pen"
        "highlighter options" in desc -> "${desc.substringBefore(" color")} highl"
        else -> desc
    }
}
