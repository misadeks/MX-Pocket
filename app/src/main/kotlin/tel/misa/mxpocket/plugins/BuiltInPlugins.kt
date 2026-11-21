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

    // OneNote exposes draw tools via content descriptions, not text.
    private val tools = listOf(
        "Pen" to "pen options", "Eraser" to "eraser", "Highlighter" to "highlighter options",
        "Lasso" to "lasso", "Tools" to "more options",
    )
    private var active: String? = null

    override fun onActivate(host: PluginHost) {
        host.scope.launch {
            while (true) {
                active = host.activeTool
                host.invalidate()
                delay(250)
            }
        }
    }

    override fun render(nowMillis: Long): PluginFrame {
        val labels = listOf("Open") + tools.map { it.first } + listOf("Draw", "Quick note", "Repaint")
        return PluginFrame(labels.mapIndexed { index, label ->
            val keyword = tools.getOrNull(index - 1)?.second
            val isActive = keyword != null && active?.contains(keyword, ignoreCase = true) == true
            KeyTile(label, "ONENOTE", if (isActive) 0xffa56eff.toInt() else 0xff7719aa.toInt())
        })
    }

    override fun onKey(event: PluginKeyEvent, host: PluginHost) {
        if (!event.pressed || event.synthetic) return
        val action = when (event.index) {
            0 -> PluginAction.OpenApp(PhoneApp.ONENOTE)
            // Re-pressing the active tool long-presses it in the service, opening its settings;
            // the service also opens the Draw tab first when the toolbar is hidden in home view.
            1, 2, 3, 4, 5 -> PluginAction.AccessibilityClick(tools[event.index - 1].second)
            6 -> PluginAction.AccessibilityClick("draw")
            7 -> PluginAction.ShareText("MX Pocket quick note ${host.nowMillis()}")
            8 -> PluginAction.Repaint
            else -> null
        }
        action?.let(host::dispatch)
        if (action is PluginAction.AccessibilityClick) host.scope.launch {
            delay(300) // OneNote updates its accessibility tree shortly after the click.
            active = host.activeTool
            host.invalidate()
        }
    }
}
