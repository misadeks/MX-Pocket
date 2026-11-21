package tel.misa.mxpocket.plugins

/** Test probe without the ACCESSIBILITY capability that dispatches a command on press. */
class AccessibilityCommandProbe(id: String) : PocketPlugin {
    override val descriptor = PluginDescriptor(id, id)
    override fun render(nowMillis: Long) = frame(List(9) { "Probe" }, descriptor.id, 0xff000000.toInt())
    override fun onKey(event: PluginKeyEvent, host: PluginHost) {
        if (event.pressed) host.dispatch(PluginAction.AccessibilityCommand("clipstudio.tool.pencil"))
    }
}
