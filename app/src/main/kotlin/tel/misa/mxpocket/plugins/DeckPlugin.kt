package tel.misa.mxpocket.plugins

import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.launch
import tel.misa.mxpocket.decks.DeckCommand as C
import tel.misa.mxpocket.decks.DeckState
import tel.misa.mxpocket.decks.navigationCommands
import tel.misa.mxpocket.i18n.KeypadCopy as K

internal fun C.capability() = when (deck) {
    "launcher" -> PluginCapability.OPEN_APPS
    "audio" -> PluginCapability.AUDIO
    "studio" -> PluginCapability.STUDIO
    "navigation" -> PluginCapability.SYSTEM_NAVIGATION
    "teams" -> PluginCapability.TEAMS
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
    private fun player(): List<Slot> {
        val media = state.media
        val volume = VolumeLevel(media.volume, media.maximum, media.muted)
        fun control(command: C, glyph: PlayerGlyph, footer: String = "", level: VolumeLevel? = null) = slot(command).let {
            it.copy(tile = it.tile.copy(glyph = glyph, footer = footer, playerFace = true, showLabel = false,
                showFooter = false, primary = command == C.PLAY, volumeLevel = level))
        }
        val transport = when (media.playing) {
            true -> PlayerGlyph.PAUSE
            false -> PlayerGlyph.PLAY
            null -> PlayerGlyph.PLAY_PAUSE
        }
        return listOf(control(C.QUIETER, PlayerGlyph.VOLUME_DOWN),
            control(if (media.muted) C.UNMUTE else C.MUTE, if (media.muted) PlayerGlyph.UNMUTE else PlayerGlyph.MUTE,
                volume.description, volume),
            control(C.LOUDER, PlayerGlyph.VOLUME_UP),
            control(C.PREVIOUS, PlayerGlyph.PREVIOUS), control(C.PLAY, transport), control(C.NEXT, PlayerGlyph.NEXT),
            control(C.BACK_10, PlayerGlyph.BACK_10),
            Slot(KeyTile(media.title.ifBlank { media.displayStatus }, media.displayStatus, tel.misa.mxpocket.decks.AUDIO_BACKGROUND.toInt(),
                glyph = PlayerGlyph.TRACK, readOnly = true, playerFace = true, showFooter = false)),
            control(C.FORWARD_10, PlayerGlyph.FORWARD_10))
    }
    private val pages get() = ((state.favorites.size + 6) / 7).coerceAtLeast(1)
    private val current get() = page.coerceIn(0, pages - 1)
    private fun launcher(): List<Slot> {
        return List(7) { index ->
            state.favorites.getOrNull(current * 7 + index)?.let { slot(C.OPEN, it.component) }
                ?: Slot(KeyTile(if (index == 0) K.NoFavorites.text() else "-", K.Favorites.text(), 0xff384674.toInt(),
                    readOnly = true, showLabel = index == 0, showFooter = false))
        } + listOf(slot(C.EDIT), Slot(KeyTile((if (pages > 1) K.NextApps else K.Favorites).text(),
            "${current + 1} / $pages", 0xff384674.toInt(), inputToken = "page:$current/$pages",
            glyph = DeckGlyph.FOLDER, readOnly = pages == 1), next = pages > 1))
    }
    private fun slots(): List<Slot> = when (descriptor.id) {
        "launcher" -> launcher()
        "audio" -> player()
        "studio" -> studioSlots.map { command ->
            command?.let { slot(it) } ?: Slot(KeyTile("-", "", 0xff49345f.toInt(), enabled = false,
                quietFace = true, showLabel = false, showFooter = false))
        }
        // These are persisted plugin.key indices, not the physical positions of the factory keys.
        "navigation" -> navigationCommands.map { slot(it) }
        "teams" -> tel.misa.mxpocket.decks.teamsSlots.map { slot(it) }
        else -> error("Unknown page")
    }
    override fun render(nowMillis: Long) = PluginFrame(slots().map { it.tile }, current)
    override fun onKey(event: PluginKeyEvent, host: PluginHost) {
        if (!event.pressed || event.synthetic) return
        val key = slots()[event.index]
        if (!key.tile.enabled || key.tile.readOnly) return
        if (key.next) {
            page = (current + 1) % pages
            host.invalidate()
        } else key.action?.let(host::dispatch)
    }
}
