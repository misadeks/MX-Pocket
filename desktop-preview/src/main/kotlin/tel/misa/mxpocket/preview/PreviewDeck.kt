package tel.misa.mxpocket.preview

import tel.misa.mxpocket.FocusTimer
import tel.misa.mxpocket.deck.defaultDeck
import tel.misa.mxpocket.plugins.TicTacToeGame

/** Factory layouts and the Focus clock are shared with Android. All other state is window-local. */
class PreviewDeck {
    private var deck = defaultDeck()
    private val focus = FocusTimer()
    private val xo = TicTacToeGame()
    private var volume = 6
    private var muted = false
    private var playing = false
    private val tracks = listOf("Night train", "First light", "Homeward")
    private var track = 0

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
        "audio" -> listOf("Volume -", if (muted) "Unmute" else "Mute", "Volume +", "Previous",
            if (playing) "Pause" else "Play", "Next", "Seek back 10 seconds", tracks[track], "Seek forward 10 seconds")
        "phone" -> listOf("Camera", "Settings", "Dialer", "Web", "Phone", "Alarm", "Focus", "Audio", "Repaint")
        "launcher" -> listOf("No favorites", "-", "-", "-", "-", "-", "-", "Edit favorites", "Favorites")
        "studio" -> listOf("Shutter", "Record", "Stop", "-", "-", "-", "Open Video", "Open Camera", "Camera controls")
        "navigation" -> listOf("Back", "Home", "Recents", "Scroll up", "Select", "Scroll down",
            "Prev focus", "Access info", "Next focus")
        else -> emptyList()
    }

    data class Tile(val label: String, val enabled: Boolean, val footer: String = "", val readOnly: Boolean = false)
    private data class Binding(val plugin: String, val index: Int)

    private fun binding(slot: Int): Binding? {
        val key = deck.activePage.keys[slot]
        val action = key.action?.takeIf { key.enabled && it.id == "plugin.key" } ?: return null
        return Binding(action.params.getValue("pluginId"), action.params.getValue("index").toInt())
    }

    fun tiles(now: Long): List<Tile> {
        val cells = if (profileId == "game.xo") xo.cells() else emptyList()
        return deck.activePage.keys.indices.map { slot ->
            val action = binding(slot)
            if (action?.plugin == "game.xo") {
                val label = cells[action.index].label
                return@map Tile(label.ifEmpty { "-" }, label.isNotEmpty() && label != "X-O")
            }
            val label = action?.let { labels(it.plugin, now).getOrNull(it.index) }
            when {
                action?.plugin == "audio" && label != null -> when (action.index) {
                    1 -> Tile(label, true, "${if (muted) 0 else volume} / 15")
                    6, 8 -> Tile(label, false, "No seek position")
                    7 -> Tile(label, false, "Now playing · ${if (playing) "Playing" else "Paused"}", readOnly = true)
                    else -> Tile(label, true)
                }
                action?.plugin == "navigation" && action.index == 2 -> Tile("Recents", false, "Needs access")
                action?.plugin == "navigation" && action.index == 7 -> Tile("Access info", true, "System nav off")
                else -> Tile(label ?: "-", label != null && label != "-")
            }
        }
    }

    fun press(slot: Int, now: Long): String {
        val (plugin, index) = binding(slot) ?: return "Unassigned key."
        if (plugin == "game.xo") {
            xo.press(index)
            return xo.cells()[4].footer
        }
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
                1 -> muted = !muted
                3, 5 -> track = Math.floorMod(track + if (index == 5) 1 else -1, tracks.size)
                4 -> playing = !playing
                6, 8 -> return "Seeking is unavailable in this preview."
                7 -> return "Now playing: ${tracks[track]}."
            }
            "navigation" -> return when (index) {
                2 -> "Recents needs Android navigation access. No system action sent."
                7 -> "Access info: system navigation is off in this desktop preview. No settings opened."
                else -> "$label is simulated. No system action sent."
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
