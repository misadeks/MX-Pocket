package tel.misa.mxpocket.preview

import tel.misa.mxpocket.FocusTimer
import tel.misa.mxpocket.deck.*
import tel.misa.mxpocket.plugins.*

/** Uses the same saved deck and folder histories as the editor. Device effects never leave this host. */
class PreviewDeck(val repository: DeckRepository) {
    private val deck get() = repository.state.value
    private val focus = FocusTimer()
    private var volume = 6
    private var muted = false
    private var playing = false

    val profileName get() = deck.activeProfile.name
    val profileIndex get() = deck.profiles.indexOf(deck.activeProfile)
    val profileCount get() = deck.profiles.size
    val profileId get() = deck.activeProfileId
    val profiles get() = deck.profiles.map { it.id to it.name }

    fun select(id: String) {
        repository.selectProfile(id).getOrThrow()
    }

    fun cycle(delta: Int) {
        repository.changePage(delta).getOrThrow()
    }

    fun tick(now: Long) = focus.tick(now)

    private fun labels(plugin: String, now: Long): List<String> = when (plugin) {
        "focus" -> focus.present(now).keys.map { it.label }
        "audio" -> listOf("Volume -", if (playing) "Pause" else "Play", "Volume +", "Previous",
            "Media access", "Next", "Mute", "${if (muted) 0 else volume} / 15", "Unmute")
        "phone" -> phoneFrame().keys.map { it.label }
        "launcher" -> listOf("No favorites", "-", "-", "-", "-", "-", "-", "Edit favorites", "Favorites")
        "studio" -> listOf("Shutter", "Record", "Stop", "-", "-", "-", "Open Video", "Open Camera", "Camera controls")
        "navigation" -> listOf("Back", "Home", "Recents", "Scroll up", "Activate", "Scroll down",
            "Prior item", "Navigation access", "Next item")
        else -> emptyList()
    }

    private data class Binding(val plugin: String, val index: Int)

    private fun binding(slot: Int): Binding? {
        val key = deck.activePage.keys[slot]
        val action = key.action?.takeIf { key.enabled && it.id == "plugin.key" } ?: return null
        return Binding(action.params.getValue("pluginId"), action.params.getValue("index").toInt())
    }

    fun tiles(now: Long): List<KeyTile> = deck.activePage.keys.indices.map { slot ->
        val action = binding(slot)
        val key = deck.activePage.keys[slot]
        val assigned = key.action
        val dynamic = action?.let { labels(it.plugin, now).getOrNull(it.index) }
        val name = when (assigned?.id) {
            DeckActions.PAGE -> deck.activeProfile.pages.find { it.id == assigned.params[DeckActions.PAGE_ID] }?.name
            DeckActions.PROFILE -> deck.profiles.find { it.id == assigned.params[DeckActions.PROFILE_ID] }?.name
            DeckActions.ROOT -> "Root"
            DeckActions.BACK -> "Back"
            else -> dynamic ?: assigned?.id
        }
        val native = when (action?.plugin) {
            "focus" -> focus.present(now).keys[action.index]
            "phone" -> phoneFrame().keys[action.index]
            "audio" -> KeyTile(dynamic.orEmpty(), "Simulated media", 0xff303438.toInt())
            "studio" -> KeyTile(dynamic.orEmpty(), "Android only", 0xff49345f.toInt())
            "navigation" -> KeyTile(dynamic.orEmpty(), "Android only", 0xff254c69.toInt())
            "launcher" -> KeyTile(dynamic.orEmpty(), "Android only", 0xff384674.toInt())
            else -> null
        }
        val navigation = assigned?.id in setOf(DeckActions.PAGE, DeckActions.PROFILE, DeckActions.BACK, DeckActions.ROOT)
        val available = key.enabled && (navigation || native != null && dynamic != "-")
        key.present(native, name ?: "-", when {
            !key.enabled -> "Disabled"
            !available && assigned != null -> "Unavailable"
            else -> native?.footer.orEmpty()
        }, available)
    }

    fun press(slot: Int, now: Long): String {
        val key = deck.activePage.keys[slot]
        val call = key.action?.takeIf { key.enabled } ?: return "Unassigned key."
        val navigation = when (call.id) {
            DeckActions.BACK -> repository.back()
            DeckActions.ROOT -> repository.root()
            DeckActions.PAGE -> repository.openPage(call.params.getValue(DeckActions.PAGE_ID))
            DeckActions.PROFILE -> repository.selectProfile(call.params.getValue(DeckActions.PROFILE_ID))
            "plugin.key" -> null
            else -> return "${call.id} is not available in the Windows simulator. No action sent."
        }
        if (navigation != null) return navigation.fold({ "On $profileName / ${deck.activePage.name}." },
            { it.message ?: "Cannot change page." })
        val (plugin, index) = binding(slot) ?: return "Unassigned key."
        val label = labels(plugin, now).getOrNull(index) ?: return "Unsupported binding."
        when (plugin) {
            "focus" -> when (index) {
                0 -> focus.toggle(now)
                2 -> focus.reset()
                3 -> focus.adjust(-5)
                5 -> focus.adjust(5)
                6, 7, 8 -> focus.select(listOf(15, 25, 50)[index - 6])
                else -> return "Focus timer: ${focus.label(now)} remaining."
            }
            "audio" -> when (index) {
                0, 2 -> {
                    volume = (volume + if (index == 2) 1 else -1).coerceIn(0, 15)
                    muted = false
                }
                1 -> playing = !playing
                6, 8 -> muted = index == 6
                7 -> return "Simulated volume: ${if (muted) 0 else volume} / 15."
                else -> return "$label is simulated. No media command sent."
            }
            "phone" -> if (index == 6 || index == 7) {
                val target = if (index == 6) "focus" else "audio"
                repository.selectProfile(target).getOrElse { return it.message ?: "Profile unavailable." }
                return "Opened $profileName from Phone."
            } else return "$label is simulated. No phone action sent."
            else -> return "$label is simulated. No system action sent."
        }
        return if (plugin == "focus") "Focus timer: ${focus.label(now)} remaining."
            else "$label in preview only. Windows media is unchanged."
    }
}
