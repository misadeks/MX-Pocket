package tel.misa.mxpocket.plugins

import android.content.res.Resources
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import tel.misa.mxpocket.FocusTimer

/** Registration order is the page-button order. Add your compiled-in plugin here. */
object BuiltInPlugins {
    fun create(focusTitle: String, audioTitle: String, phoneTitle: String, xoTitle: String = TIC_TAC_TOE_TITLE, oneNoteTitle: String = "OneNote", resources: Resources? = null): List<PocketPlugin> =
        listOf(FocusPlugin(focusTitle), DeckPlugin("audio", audioTitle, PluginCapability.AUDIO), PhonePlugin(phoneTitle),
            DeckPlugin("launcher", "App Launcher", PluginCapability.OPEN_APPS),
            DeckPlugin("studio", "Pocket Studio", PluginCapability.STUDIO),
            DeckPlugin("navigation", "Android Navigation", PluginCapability.SYSTEM_NAVIGATION),
            TicTacToePlugin(xoTitle), DeckPlugin("teams", "Teams", PluginCapability.TEAMS),
            OneNotePlugin(oneNoteTitle, resources))
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
class OneNotePlugin(title: String, resources: Resources? = null) : PocketPlugin {
    private val faces = OneNoteFaces(resources)
    override val descriptor = PluginDescriptor("onenote", title,
        setOf(PluginCapability.OPEN_APPS, PluginCapability.NAVIGATION, PluginCapability.ACCESSIBILITY))

    // The ink toolbar's tools as (description, description); empty outside the draw view.
    private var tools: List<Pair<String, String>> = emptyList()
    private var active: String? = null
    private var settings: OneNoteSettings? = null
    private var page = 0
    private var colorsOpen = false
    private var opening = false
    private var textPage = 0

    override fun onActivate(host: PluginHost) {
        host.scope.launch {
            while (true) {
                val updated = host.oneNoteSettings
                if (updated?.textColor == true && settings?.textColor != true) page = 0
                settings = updated
                if (settings == null && !opening) {
                    colorsOpen = false
                    tools = host.toolBar
                    active = host.activeTool
                }
                host.invalidate()
                delay(250)
            }
        }
    }

    // More options belongs to the app menu, not the nine draw tools.
    private fun selectable() = tools.filter { it.second != "More options" }

    override fun render(nowMillis: Long): PluginFrame {
        val current = settings
        if (current != null) {
            return if (colorsOpen || current.textColor) faces.palette(current, palettePage(current)) else faces.settings(current)
        }
        if (tools.any { it.first.startsWith("style:") || it.first.startsWith("size:") }) {
            val size = tools.any { it.first.startsWith("size:") }
            return PluginFrame(List(9) { faces.option(tools.getOrNull(it), size) })
        }
        if (tools.any { it.first.startsWith("text:") }) {
            val displayed = if (textPage == 0) tools.take(8) else tools.drop(8).take(7) + ("text:draw" to "Draw")
            return PluginFrame(List(9) { index ->
                if (index == 8) faces.pageKey(textPage == 0, footer = "${textPage + 1} / 2")
                else faces.ribbon(displayed.getOrNull(index))
            })
        }
        return faces.draw(selectable(), active)
    }

    override fun onKey(event: PluginKeyEvent, host: PluginHost) {
        if (!event.pressed || event.synthetic) return
        val current = settings
        if (current != null) {
            if (colorsOpen || current.textColor) {
                page = palettePage(current)
                when (event.index) {
                    in 0..5 -> current.colors.getOrNull(page * 6 + event.index)?.let {
                        host.dispatch(PluginAction.OneNoteSetting(color = current.colorIds.getOrNull(page * 6 + event.index) ?: it))
                        colorsOpen = false
                    }
                    6 -> page = (page - 1).coerceAtLeast(0)
                    7 -> if (current.textColor) host.dispatch(PluginAction.OneNoteSetting(close = true)) else colorsOpen = false
                    8 -> page = (page + 1).coerceAtMost((current.colors.size - 1).coerceAtLeast(0) / 6)
                }
            } else when (event.index) {
                0 -> host.dispatch(PluginAction.OneNoteSetting(thicknessDelta = -1))
                2 -> host.dispatch(PluginAction.OneNoteSetting(thicknessDelta = 1))
                4 -> { page = 0; colorsOpen = true }
                7 -> host.dispatch(PluginAction.OneNoteSetting(close = true))
            }
            host.invalidate()
            return
        }
        if (tools.any { it.first.startsWith("style:") || it.first.startsWith("size:") }) {
            val item = tools.getOrNull(event.index)
            if (!item?.first.isNullOrEmpty()) host.dispatch(PluginAction.AccessibilityClick(item!!.first))
            return
        }
        if (tools.any { it.first.startsWith("text:") }) {
            if (event.index == 8) { textPage = 1 - textPage; host.invalidate(); return }
            val item = if (textPage == 0) tools.getOrNull(event.index)
                else if (event.index == 7) "text:draw" to "Draw" else tools.getOrNull(event.index + 8)
            if (!item?.first.isNullOrEmpty()) host.dispatch(PluginAction.AccessibilityClick(item!!.first))
            return
        }
        val tool = selectable().getOrNull(event.index) ?: return
        val showSettings = tool.second == active && ("pen options" in tool.second || "highlighter options" in tool.second)
        if (showSettings) opening = true
        host.dispatch(PluginAction.AccessibilityClick(tool.first))
        host.scope.launch {
            delay(300) // OneNote updates its accessibility tree shortly after the click.
            settings = host.oneNoteSettings
            opening = false
            if (settings == null) active = host.activeTool
            host.invalidate()
        }
    }

    private fun palettePage(current: OneNoteSettings) = page.coerceIn(0, (current.colors.size - 1).coerceAtLeast(0) / 6)

    override fun onDeactivate() {
        tools = emptyList(); active = null; settings = null; colorsOpen = false; opening = false; textPage = 0; page = 0
    }
}
