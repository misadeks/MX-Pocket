package tel.misa.mxpocket.ui

import android.content.Context
import androidx.compose.ui.graphics.asImageBitmap
import tel.misa.mxpocket.KeyImages
import tel.misa.mxpocket.R
import tel.misa.mxpocket.deck.navigationFactorySlots
import tel.misa.mxpocket.plugins.BuiltInPlugins
import tel.misa.mxpocket.plugins.PluginFrame
import tel.misa.mxpocket.shared.ui.*

/** Deterministic display fixtures. No activation callbacks, host, timers or platform services. */
class UiPreviewScenes(context: Context) {
    private val plugins = BuiltInPlugins.create(context.getString(R.string.focus),
        context.getString(R.string.audio), context.getString(R.string.phone), oneNoteTitle = context.getString(R.string.onenote))
    val descriptors = plugins.map { it.descriptor }
    val ids = listOf("disconnected") + descriptors.map { it.id }
    private val note = "UI-only preview. No USB access, phone actions or running plugins."
    private val disconnectedNote = context.getString(R.string.not_connected)
    private val fixtures = ids.associateWith { lazy { buildState(it) } }

    fun state(id: String): PocketScreenState =
        requireNotNull(fixtures[id]) { "Unknown UI preview scene: $id. Use ${ids.joinToString()}." }.value

    private fun buildState(id: String): PocketScreenState {
        val plugin = if (id == "disconnected") plugins.first() else plugins.single { it.descriptor.id == id }
        val frame = plugin.render(0)
        val displayFrame = if (id == "audio") PluginFrame(frame.keys.mapIndexed { index, tile ->
            when (index) {
                1 -> tile.copy(footer = "6 / 15", volumeLevel = tel.misa.mxpocket.plugins.VolumeLevel(6,15,false))
                7 -> tile.copy(label = "Night train", footer = "Paused")
                4 -> tile.copy(label = "Play", glyph = tel.misa.mxpocket.plugins.PlayerGlyph.PLAY)
                else -> tile
            }
        }) else frame
        val keys = if (id == "navigation") navigationFactorySlots.map(frame.keys::get) else displayFrame.keys
        val images = KeyImages { null }
        return PocketScreenState(
            status = if (id == "disconnected") "$note\n$disconnectedNote" else "$note\n${plugin.descriptor.title} fixture",
            pluginTitle = plugin.descriptor.title,
            tiles = keys.mapIndexed { index, key -> key.toGridTile(true, images.image(index, key).asImageBitmap()) },
            profiles = descriptors.map { ProfileChoice(it.id, it.title) },
            activeId = plugin.descriptor.id,
            diagnostics = "UI fixture: no USB enumeration has been performed.",
            log = "Static scene: $id. Controls only emit UI preview events.",
        )
    }
}
