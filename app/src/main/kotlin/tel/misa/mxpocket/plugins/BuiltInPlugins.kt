package tel.misa.mxpocket.plugins

import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import tel.misa.mxpocket.FocusTimer

/** Registration order is the page-button order. Add your compiled-in plugin here. */
object BuiltInPlugins {
    fun create(focusTitle: String, audioTitle: String, phoneTitle: String): List<PocketPlugin> =
        listOf(FocusPlugin(focusTitle), DeckPlugin("audio", audioTitle, PluginCapability.AUDIO), PhonePlugin(phoneTitle),
            DeckPlugin("launcher", "App Launcher", PluginCapability.OPEN_APPS),
            DeckPlugin("studio", "Pocket Studio", PluginCapability.STUDIO),
            DeckPlugin("navigation", "Android Navigation", PluginCapability.SYSTEM_NAVIGATION))
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

    override fun render(nowMillis: Long) = frame(
        listOf(if (timer.running) "Pause" else "Start", timer.label(nowMillis), "Reset",
            "-5 min", "${timer.durationMillis / 60_000} min", "+5 min", "15 min", "25 min", "50 min"),
        "FOCUS", 0xff194375.toInt(),
    )

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

    override fun render(nowMillis: Long) = frame(
        listOf("Camera", "Settings", "Dialer", "Web", "Phone", "Alarm", "Focus", "Audio", "Repaint"),
        "PHONE", 0xff49345f.toInt(),
    )

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
