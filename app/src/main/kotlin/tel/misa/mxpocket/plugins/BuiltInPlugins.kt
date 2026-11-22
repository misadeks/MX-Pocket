package tel.misa.mxpocket.plugins

import android.content.res.Resources
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import tel.misa.mxpocket.FocusTimer
import tel.misa.mxpocket.i18n.builtInPluginTitle
import tel.misa.mxpocket.i18n.KeypadCopy as K
import tel.misa.mxpocket.i18n.AccessCopy
import tel.misa.mxpocket.i18n.OneNoteCopy as N

fun PluginDescriptor.displayTitle() = builtInPluginTitle(id, title)

fun PluginCapability.displayName() = when (this) {
    PluginCapability.AUDIO -> K.CapAudio
    PluginCapability.OPEN_APPS -> K.CapApps
    PluginCapability.NAVIGATION -> K.CapNav
    PluginCapability.TONE -> K.CapTone
    PluginCapability.STUDIO -> K.CapStudio
    PluginCapability.SYSTEM_NAVIGATION -> K.CapSystemNav
    PluginCapability.TEAMS -> K.CapTeams
    PluginCapability.ACCESSIBILITY -> N.Accessibility
}.text()

fun PhoneApp.displayName() = when (this) {
    PhoneApp.CAMERA -> K.Camera
    PhoneApp.SETTINGS -> K.Settings
    PhoneApp.DIALER -> K.Dialer
    PhoneApp.WEB -> K.Web
    PhoneApp.ALARMS -> K.Alarms
    PhoneApp.ONENOTE -> N.Brand
}.text()

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
            6, 7 -> tel.misa.mxpocket.deck.phoneShortcutProfile(event.index)?.let(PluginAction::SelectPlugin)
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
    private var lastTools: List<Pair<String, String>> = emptyList()
    private var active: String? = null
    private var settings: OneNoteSettings? = null
    private var page = 0
    private var colorsOpen = false
    private var opening = false
    private var textPage = 0
    private var shapePage = 0
    private var foreground = false

    override fun onActivate(host: PluginHost) {
        host.scope.launch {
            while (true) {
                foreground = host.oneNoteForeground
                val updated = host.oneNoteSettings
                if (updated?.textColor == true && settings?.textColor != true) page = 0
                settings = updated
                if (settings == null && !opening) {
                    colorsOpen = false
                    val updatedTools = host.toolBar
                    if (updatedTools.any { it.first.startsWith("shape:") } && tools.none { it.first.startsWith("shape:") }) shapePage = 0
                    tools = updatedTools
                    if (tools.any { it.first.isNotBlank() }) lastTools = tools.toList()
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
        val inactive = tools.none { it.first.isNotBlank() }
        val displayedTools = if (inactive) lastTools else tools
        val frame = if (displayedTools.any { it.first.startsWith("shape:") }) {
            val pages = ((displayedTools.size + 5) / 6).coerceAtLeast(1)
            val currentPage = shapePage.coerceIn(0, pages - 1)
            PluginFrame(List(9) { index -> when (index) {
                in 0..5 -> faces.shape(displayedTools.getOrNull(currentPage * 6 + index))
                6 -> faces.pageKey(false, currentPage > 0)
                7 -> faces.option("style:cancel" to tel.misa.mxpocket.i18n.AccessCopy.Cancel.text(), false)
                else -> faces.pageKey(true, currentPage + 1 < pages, "${currentPage + 1} / $pages")
            } })
        } else if (displayedTools.any { it.first.startsWith("style:") || it.first.startsWith("size:") }) {
            val size = displayedTools.any { it.first.startsWith("size:") }
            PluginFrame(List(9) { faces.option(displayedTools.getOrNull(it), size) })
        } else if (displayedTools.any { it.first.startsWith("text:") }) {
            val displayed = if (textPage == 0) displayedTools.take(8) else displayedTools.drop(8).take(7) + ("text:draw" to N.Draw.text())
            PluginFrame(List(9) { index ->
                if (index == 8) faces.pageKey(textPage == 0, footer = "${textPage + 1} / 2")
                else faces.ribbon(displayed.getOrNull(index))
            })
        } else faces.draw(displayedTools.filter { it.second != "More options" }, if (inactive) null else active)
        return if (!inactive) frame else frame.copy(keys = frame.keys.mapIndexed { index, tile ->
            if (index == 4 && !foreground) return@mapIndexed faces.openOneNote()
            val glyph = tile.glyph
            tile.copy(enabled = false, selection = null, detail = "",
                glyph = if (glyph is OneNoteLegend && glyph.ink != null) glyph.copy(ink = 0xff999da0.toInt()) else glyph)
        })
    }

    override fun onKey(event: PluginKeyEvent, host: PluginHost) {
        if (!event.pressed || event.synthetic) return
        if (settings == null && tools.none { it.first.isNotBlank() }) {
            if (event.index == 4 && !host.oneNoteForeground) host.dispatch(PluginAction.OpenApp(PhoneApp.ONENOTE))
            return
        }
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
        if (tools.any { it.first.startsWith("shape:") }) {
            val pages = ((tools.size + 5) / 6).coerceAtLeast(1)
            shapePage = shapePage.coerceIn(0, pages - 1)
            when (event.index) {
                in 0..5 -> tools.getOrNull(shapePage * 6 + event.index)?.let { host.dispatch(PluginAction.AccessibilityClick(it.first)) }
                6 -> shapePage = (shapePage - 1).coerceAtLeast(0)
                7 -> host.dispatch(PluginAction.AccessibilityClick("shape:cancel"))
                8 -> shapePage = (shapePage + 1).coerceAtMost(pages - 1)
            }
            host.invalidate()
            return
        }
        if (tools.any { it.first.startsWith("style:") || it.first.startsWith("size:") }) {
            if (opening) return
            val id = tools.getOrNull(event.index)?.first.orEmpty()
            if (id.isBlank() || id == "size:current") return
            if (id.startsWith("size:down:") || id.startsWith("size:up:")) {
                opening = true
                host.dispatch(PluginAction.AccessibilityClick(id))
                host.scope.launch {
                    delay(1200)
                    opening = false
                    host.invalidate()
                }
            } else host.dispatch(PluginAction.AccessibilityClick(id))
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
        tools = emptyList(); active = null; settings = null; colorsOpen = false; opening = false; textPage = 0; page = 0; shapePage = 0
    }
}
