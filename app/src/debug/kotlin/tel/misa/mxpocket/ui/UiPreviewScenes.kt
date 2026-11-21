package tel.misa.mxpocket.ui

import android.content.Context
import tel.misa.mxpocket.R
import tel.misa.mxpocket.plugins.BuiltInPlugins
import tel.misa.mxpocket.plugins.PluginFrame

/** Deterministic display fixtures. No activation callbacks, host, timers or platform services. */
class UiPreviewScenes(context: Context) {
    private val plugins = BuiltInPlugins.create(context.getString(R.string.focus),
        context.getString(R.string.audio), context.getString(R.string.phone), context.getString(R.string.clipstudio))
    val descriptors = plugins.map { it.descriptor }
    val ids = listOf("disconnected") + descriptors.map { it.id }
    private val note = "UI-only preview. No USB access, phone actions or running plugins."
    private val disconnectedNote = context.getString(R.string.not_connected)

    fun state(id: String): PocketScreenState {
        require(id in ids) { "Unknown UI preview scene: $id. Use ${ids.joinToString()}." }
        val plugin = if (id == "disconnected") plugins.first() else plugins.single { it.descriptor.id == id }
        val frame = plugin.render(0)
        val displayFrame = if (id == "audio") PluginFrame(frame.keys.mapIndexed { index, tile ->
            if (index == 7) tile.copy(label = "6 / 15") else tile
        }) else frame
        return PocketScreenState(
            status = if (id == "disconnected") "$note\n$disconnectedNote" else "$note\n${plugin.descriptor.title} fixture",
            pluginTitle = plugin.descriptor.title,
            frame = displayFrame,
            diagnostics = "UI fixture: no USB enumeration has been performed.",
            log = "Static scene: $id. Controls only emit UI preview events.",
        )
    }
}
