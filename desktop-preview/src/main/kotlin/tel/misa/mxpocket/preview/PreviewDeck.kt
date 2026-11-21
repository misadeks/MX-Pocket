package tel.misa.mxpocket.preview

import tel.misa.mxpocket.FocusTimer
import tel.misa.mxpocket.deck.*
import tel.misa.mxpocket.decks.DeckCommand
import tel.misa.mxpocket.decks.navigationCommands
import tel.misa.mxpocket.plugins.*

/** Uses the same saved deck and folder histories as the editor. Device effects never leave this host. */
class PreviewDeck(val repository: DeckRepository) {
    private val deck get() = repository.state.value
    private val focus = FocusTimer()
    private val xo = TicTacToeGame()
    private var volume = 6
    private var muted = false
    private var playing = false
    private val tracks = listOf("Night train", "First light", "Homeward")
    private var track = 0
    private val knownPlugins = defaultDeck().profiles.map { it.id }.toSet()
    private val selectedPlugin get() = deck.activePage.keys.firstNotNullOfOrNull {
        it.action?.slotOwner()?.takeIf(knownPlugins::contains)
    }

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
        "audio" -> listOf("Volume -", if (muted) "Unmute" else "Mute", "Volume +", "Previous",
            if (playing) "Pause" else "Play", "Next", "Seek back 10 seconds", tracks[track], "Seek forward 10 seconds")
        "phone" -> phoneFrame().keys.map { it.label }
        "launcher" -> listOf("No favorites", "-", "-", "-", "-", "-", "-", "Edit favorites", "Favorites")
        "studio" -> listOf("Shutter", "Record", "Stop", "-", "-", "-", "Open Video", "Open Camera", "Camera controls")
        "navigation" -> listOf("Back", "Home", "Recents", "Scroll up", "Select", "Scroll down",
            "Prev focus", "Access info", "Next focus")
        else -> emptyList()
    }

    private data class Binding(val plugin: String, val index: Int, val command: DeckCommand? = null)

    private fun binding(slot: Int): Binding? {
        val action = deck.activePage.keys[slot].action ?: return null
        if (action.id == "plugin.key") return Binding(action.params.getValue("pluginId"), action.params.getValue("index").toInt())
        val command = DeckCommand.fromId(action.id) ?: return null
        if (action.commandBinding().params.isNotEmpty()) return null
        val index = when (command.deck) {
            "audio" -> when (command) {
                DeckCommand.MUTE, DeckCommand.UNMUTE -> 1
                DeckCommand.MEDIA_ACCESS, DeckCommand.VOLUME_LEVEL -> -1
                else -> audioCommands.indexOf(command)
            }
            "navigation" -> navigationCommands.indexOf(command)
            else -> return null
        }
        return Binding(command.deck, index, command)
    }

    fun tiles(now: Long): List<KeyTile> = deck.activePage.keys.indices.map { slot ->
        val action = binding(slot)
        val key = deck.activePage.keys[slot]
        val assigned = key.action
        val dynamic = when (action?.command) {
            DeckCommand.MUTE, DeckCommand.UNMUTE, DeckCommand.MEDIA_ACCESS -> action.command.label
            DeckCommand.VOLUME_LEVEL -> "${if (muted) 0 else volume} / 15"
            else -> action?.let { labels(it.plugin, now).getOrNull(it.index) }
        }
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
            "audio" -> {
                val i = action.index
                val level = VolumeLevel(if (muted) 0 else volume, 15, muted)
                val glyph = listOf(PlayerGlyph.VOLUME_DOWN, when (action.command) {
                    DeckCommand.MUTE -> PlayerGlyph.MUTE
                    DeckCommand.UNMUTE -> PlayerGlyph.UNMUTE
                    else -> if (muted) PlayerGlyph.UNMUTE else PlayerGlyph.MUTE
                },
                    PlayerGlyph.VOLUME_UP, PlayerGlyph.PREVIOUS, if (playing) PlayerGlyph.PAUSE else PlayerGlyph.PLAY,
                    PlayerGlyph.NEXT, PlayerGlyph.BACK_10, PlayerGlyph.TRACK, PlayerGlyph.FORWARD_10).getOrNull(i)
                KeyTile(dynamic.orEmpty(), when (i) {
                    -1 -> if (action.command == DeckCommand.VOLUME_LEVEL) "Volume" else "Android only"
                    1 -> level.description
                    6, 8 -> "No seek position"
                    7 -> if (playing) "Playing" else "Paused"
                    else -> ""
                }, 0xff303438.toInt(), glyph = glyph, primary = i == 4, volumeLevel = level.takeIf { i == 1 },
                    readOnly = i == 7 || action.command == DeckCommand.VOLUME_LEVEL,
                    enabled = i < 6 && action.command != DeckCommand.MEDIA_ACCESS)
            }
            "game.xo" -> xo.cells()[action.index].let {
                KeyTile(it.label.ifEmpty { "-" }, it.footer, it.background, enabled = it.label.isNotEmpty() && it.label != "X-O")
            }
            "studio" -> KeyTile(dynamic.orEmpty(), "Android only", 0xff49345f.toInt())
            "navigation" -> KeyTile(dynamic.orEmpty(), when (action.index) {
                2 -> "Needs access"
                7 -> "System nav off"
                else -> "Android only"
            }, when (action.index) {
                4 -> 0xffa6e4ce.toInt()
                3, 5, 6, 8 -> 0xff245650.toInt()
                else -> 0xff263943.toInt()
            }, enabled = action.index != 2, glyph = listOf(NavigationGlyph.BACK, NavigationGlyph.HOME,
                NavigationGlyph.RECENTS, NavigationGlyph.UP, NavigationGlyph.SELECT, NavigationGlyph.DOWN,
                NavigationGlyph.PREVIOUS_FOCUS, NavigationGlyph.ACCESS_INFO, NavigationGlyph.NEXT_FOCUS)[action.index])
            "launcher" -> KeyTile(dynamic.orEmpty(), "Android only", 0xff384674.toInt())
            else -> null
        }
        val navigation = assigned?.id in setOf(DeckActions.PAGE, DeckActions.PROFILE, DeckActions.BACK, DeckActions.ROOT)
        val owner = assigned?.slotOwner()
        val ownerAvailable = owner == null || owner == selectedPlugin
        val available = key.enabled && ownerAvailable && (navigation || native?.enabled == true && dynamic != "-")
        key.present(native, name ?: "-", when {
            !key.enabled -> "Disabled"
            !ownerAvailable -> "Unavailable"
            native != null -> native.footer
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
            else -> null
        }
        if (navigation != null) return navigation.fold({ "On $profileName / ${deck.activePage.name}." },
            { it.message ?: "Cannot change page." })
        if (DeckCommand.fromId(call.id)?.deck in setOf("audio", "navigation") && call.commandBinding().params.isNotEmpty())
            return "Invalid action parameters. No action sent."
        val (plugin, index, command) = binding(slot)
            ?: return "${call.id} is not available in the Windows simulator. No action sent."
        if (call.slotOwner()?.let { it != selectedPlugin } == true) return "Inactive plugin binding. No action sent."
        if (plugin == "game.xo") {
            xo.press(index)
            return xo.cells()[4].footer
        }
        val label = command?.label ?: labels(plugin, now).getOrNull(index) ?: return "Unsupported binding."
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
                -1 -> return if (command == DeckCommand.VOLUME_LEVEL) "Volume display only." else "Media access requires Android."
                0, 2 -> {
                    volume = (volume + if (index == 2) 1 else -1).coerceIn(0, 15)
                    muted = false
                }
                1 -> muted = when (command) {
                    DeckCommand.MUTE -> true
                    DeckCommand.UNMUTE -> false
                    else -> !muted
                }
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
                repository.selectProfile(target).getOrElse { return it.message ?: "Profile unavailable." }
                return "Opened $profileName from Phone."
            } else return "$label is simulated. No phone action sent."
            else -> return "$label is simulated. No system action sent."
        }
        return if (plugin == "focus") "Focus timer: ${focus.label(now)} remaining."
            else "$label in preview only. Windows media is unchanged."
    }

    private companion object {
        val audioCommands = listOf(DeckCommand.QUIETER, DeckCommand.MUTE, DeckCommand.LOUDER, DeckCommand.PREVIOUS,
            DeckCommand.PLAY, DeckCommand.NEXT, DeckCommand.BACK_10, null, DeckCommand.FORWARD_10)
    }
}
