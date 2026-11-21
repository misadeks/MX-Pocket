package tel.misa.mxpocket.plugins

import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.launch
import tel.misa.mxpocket.decks.DeckCommand as C
import tel.misa.mxpocket.decks.DeckState

internal fun C.capability() = when (deck) {
    "launcher" -> PluginCapability.OPEN_APPS
    "audio" -> PluginCapability.AUDIO
    "studio" -> PluginCapability.STUDIO
    "navigation" -> PluginCapability.SYSTEM_NAVIGATION
    else -> error("Unmapped deck")
}

internal class DeckPlugin(id: String, title: String, capability: PluginCapability) : PocketPlugin {
    override val descriptor = PluginDescriptor(id, title, setOf(capability))
    private var state = DeckState()
    private var page = 0
    private data class Slot(val tile: KeyTile, val action: PluginAction.Deck? = null, val next: Boolean = false)

    override fun onActivate(host: PluginHost) {
        val states = host.deckState
        state = states.value
        host.scope.launch { states.collect { state = it; host.invalidate() } }
    }
    override fun onDeactivate() { state = DeckState() }
    private fun slot(command: C, component: String? = null) =
        Slot(state.tile(command, component), PluginAction.Deck(command, component))
    private fun launcher(): List<Slot> {
        val pages = ((state.favorites.size + 6) / 7).coerceAtLeast(1)
        val current = page.coerceIn(0, pages - 1)
        return List(7) { index ->
            state.favorites.getOrNull(current * 7 + index)?.let { slot(C.OPEN, it.component) }
                ?: Slot(KeyTile(if (index == 0) "No favorites" else "-", "Favorites", 0xff384674.toInt()))
        } + listOf(slot(C.EDIT), Slot(KeyTile(if (pages > 1) "Next apps" else "Favorites",
            "${current + 1} / $pages", 0xff384674.toInt(), inputToken = "page:$current/$pages"), next = pages > 1))
    }
    private fun slots(): List<Slot> = when (descriptor.id) {
        "launcher" -> launcher()
        "audio" -> {
            val media = state.media
            val access = slot(C.MEDIA_ACCESS)
            listOf(slot(C.QUIETER), slot(C.PLAY), slot(C.LOUDER), slot(C.PREVIOUS),
                access.copy(tile = access.tile.copy(label = media.title.ifBlank { media.status },
                    footer = if (media.access) media.status else "Media access")),
                slot(C.NEXT), slot(C.MUTE), Slot(KeyTile("${media.volume} / ${media.maximum}", "Volume",
                    tel.misa.mxpocket.decks.AUDIO_BACKGROUND.toInt())),
                slot(C.UNMUTE))
        }
        "studio" -> listOf(C.SHUTTER, C.START_VIDEO, C.STOP_VIDEO, null, null, null,
            C.VIDEO, C.STUDIO, C.CAMERA_ACCESS).map { command ->
            command?.let { slot(it) } ?: Slot(KeyTile("-", "", 0xff49345f.toInt(), enabled = false))
        }
        // These are persisted plugin.key indices, not the physical positions of the factory keys.
        "navigation" -> listOf(C.BACK, C.HOME, C.RECENTS, C.UP, C.ACTIVATE, C.DOWN, C.REVERSE, C.NAV_ACCESS, C.FORWARD).map { slot(it) }
        else -> error("Unknown page")
    }
    override fun render(nowMillis: Long) = PluginFrame(slots().map { it.tile })
    override fun onKey(event: PluginKeyEvent, host: PluginHost) {
        if (!event.pressed || event.synthetic) return
        val key = slots()[event.index]
        if (key.next) {
            val pages = ((state.favorites.size + 6) / 7).coerceAtLeast(1)
            page = (page.coerceIn(0, pages - 1) + 1) % pages
            host.invalidate()
        } else key.action?.let(host::dispatch)
    }
}
