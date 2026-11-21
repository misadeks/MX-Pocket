package tel.misa.mxpocket.preview

import tel.misa.mxpocket.FocusTimer
import tel.misa.mxpocket.deck.defaultDeck

/** Factory layouts and the Focus clock are shared with Android. All other state is window-local. */
class PreviewDeck {
    private var deck = defaultDeck()
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
        deck = deck.copy(activeProfileId = deck.profiles.single { it.id == id }.id)
    }

    fun cycle(delta: Int) {
        val index = Math.floorMod(profileIndex + delta, profileCount)
        deck = deck.copy(activeProfileId = deck.profiles[index].id)
    }

    fun tick(now: Long) = focus.tick(now)

    private fun labels(plugin: String, now: Long): List<String> = when (plugin) {
        "focus" -> listOf(if (focus.running) "Pause" else "Start", focus.label(now), "Reset",
            "-5 min", "${focus.durationMillis / 60_000} min", "+5 min", "15 min", "25 min", "50 min")
        "audio" -> listOf("Volume -", if (playing) "Pause" else "Play", "Volume +", "Previous",
            "Media access", "Next", "Mute", "${if (muted) 0 else volume} / 15", "Unmute")
        "phone" -> listOf("Camera", "Settings", "Dialer", "Web", "Phone", "Alarm", "Focus", "Audio", "Repaint")
        "launcher" -> listOf("No favorites", "-", "-", "-", "-", "-", "-", "Edit favorites", "Favorites")
        "studio" -> listOf("Shutter", "Record", "Stop", "-", "-", "-", "Open Video", "Open Camera", "Camera controls")
        "navigation" -> listOf("Back", "Home", "Recents", "Scroll up", "Activate", "Scroll down",
            "Prior item", "Navigation access", "Next item")
        else -> emptyList()
    }

    data class Tile(val label: String, val enabled: Boolean)
    private data class Binding(val plugin: String, val index: Int)

    private fun binding(slot: Int): Binding? {
        val key = deck.activePage.keys[slot]
        val action = key.action?.takeIf { key.enabled && it.id == "plugin.key" } ?: return null
        return Binding(action.params.getValue("pluginId"), action.params.getValue("index").toInt())
    }

    fun tiles(now: Long): List<Tile> = deck.activePage.keys.indices.map { slot ->
        val action = binding(slot)
        val label = action?.let { labels(it.plugin, now).getOrNull(it.index) }
        Tile(label ?: "-", label != null && label != "-")
    }

    fun press(slot: Int, now: Long): String {
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
                deck = deck.copy(activeProfileId = deck.profiles.single { it.id == target }.id)
                return "Opened $profileName from Phone."
            } else return "$label is simulated. No phone action sent."
            else -> return "$label is simulated. No system action sent."
        }
        return if (plugin == "focus") "Focus timer: ${focus.label(now)} remaining."
            else "$label in preview only. Windows media is unchanged."
    }
}
