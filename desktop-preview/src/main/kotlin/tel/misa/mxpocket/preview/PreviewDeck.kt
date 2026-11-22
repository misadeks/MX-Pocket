package tel.misa.mxpocket.preview

import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import kotlinx.coroutines.CoroutineScope
import tel.misa.mxpocket.FocusTimer
import tel.misa.mxpocket.actions.ActionCall
import tel.misa.mxpocket.deck.*
import tel.misa.mxpocket.decks.DeckCommand
import tel.misa.mxpocket.decks.navigationCommands
import tel.misa.mxpocket.decks.teamsSlots
import tel.misa.mxpocket.decks.TeamsState
import tel.misa.mxpocket.i18n.KeypadCopy as K
import tel.misa.mxpocket.i18n.displayKeyLabel
import tel.misa.mxpocket.i18n.displayName
import tel.misa.mxpocket.i18n.displayPageName
import tel.misa.mxpocket.plugins.*
import tel.misa.mxpocket.shared.ui.PocketScreenEvent
import tel.misa.mxpocket.shared.ui.VirtualGrid
import tel.misa.mxpocket.shared.ui.virtualGrid

/** Owns the local plugins; only a physical-input dispatch may call the Windows adapter. */
class PreviewDeck(val repository: DeckRepository, private val sendMedia: (MediaKey) -> String = ::sendWindowsMedia) {
    private val deck get() = repository.operatingState.value
    private val focus = FocusTimer()
    private val xo = TicTacToeGame()
    private var volume = 6
    private var muted = false
    private var playing = false
    private val tracks = listOf(K.NightTrain, K.FirstLight, K.Homeward)
    private var track = 0
    private val knownPlugins = factoryProfiles().map { it.id }.toSet()
    private var lastSnapshot: DeckState? = null
    private var generation = 0L
    fun resetInput() { generation++ }
    internal fun pageButtons(scope: CoroutineScope, longPressMs: Long, status: (String) -> Unit,
        now: () -> Long = ::monotonicMs) = PageButtons(scope, now, { longPressMs }, { delta ->
            cycle(delta)
            status(tel.misa.mxpocket.i18n.WorkspaceCopy.Selected.text(profileName))
        }, repository::showHome, { repository.enterGame().onFailure { status(it.message ?: K.Unavailable.text()) } })
    private var simulation by androidx.compose.runtime.mutableStateOf(false)
    var simulator: Boolean
        get() = simulation
        set(value) { if (simulation != value) { simulation = value; resetInput() } }

    val profileName get() = deck.activeProfile.displayName()
    val profileIndex get() = deck.navigationProfiles.indexOf(deck.activeProfile)
    val profileCount get() = deck.navigationProfiles.size
    val profileId get() = deck.activeProfileId
    val profiles get() = deck.navigationProfiles.map { it.id to it.displayName() }

    fun setPluginEnabled(id: String, enabled: Boolean) {
        require(id in knownPlugins)
        repository.setDisabledPlugins(if (enabled) deck.disabledPluginIds - id else deck.disabledPluginIds + id)
    }

    fun select(id: String) {
        profileReason(id)?.let { throw IllegalArgumentException(it) }
        if (id == PROFILE_HOME) repository.showHome() else repository.openProfileRoot(id).getOrThrow()
    }

    fun cycle(delta: Int) {
        if (profileId == GAME_PROFILE) {
            repository.changePage(delta).getOrThrow()
            return
        }
        val ids = listOf(PROFILE_HOME) + deck.navigationProfiles.filter { profileReason(it.id) == null }.map { it.id }
        val current = ids.indexOf(profileId)
        select(ids[Math.floorMod(current.toLong() + delta, ids.size.toLong()).toInt()])
    }

    fun tick(now: Long) = focus.tick(now)
    val timerRunning get() = focus.running

    private data class Binding(val plugin: String, val index: Int, val command: DeckCommand? = null)

    private fun binding(action: ActionCall?): Binding? {
        if (action == null) return null
        if (action.id == "plugin.key") return Binding(action.params.getValue("pluginId"), action.params.getValue("index").toInt())
        val command = DeckCommand.fromId(action.id) ?: return null
        if (action.commandBinding().params.isNotEmpty()) return null
        val index = when (command.deck) {
            "audio" -> when (command) {
                DeckCommand.MUTE, DeckCommand.UNMUTE -> 1
                DeckCommand.MEDIA_ACCESS, DeckCommand.VOLUME_LEVEL -> -1
                else -> audioSlots.indexOf(command)
            }
            "navigation" -> navigationCommands.indexOf(command)
            "teams" -> teamsSlots.indexOf(command)
            else -> return null
        }
        return Binding(command.deck, index, command)
    }

    private fun face(deck: DeckState, saved: DeckKey, assigned: ActionCall?, selectedPlugin: String?, now: Long): KeyTile {
        val key = saved.copy(action = assigned, label = deck.activeProfile.displayKeyLabel(deck.activePage, saved))
        val action = binding(assigned)
        val name = when (assigned?.id) {
            DeckActions.PAGE -> deck.activeProfile.pages.find { it.id == assigned.params[DeckActions.PAGE_ID] }?.let(deck.activeProfile::displayPageName)
            DeckActions.PROFILE -> deck.profiles.find { it.id == assigned.params[DeckActions.PROFILE_ID] }?.displayName()
            DeckActions.ROOT -> K.Root.text()
            DeckActions.BACK -> K.Back.text()
            else -> assigned?.let { DeckCommand.fromId(it.id)?.displayLabel ?: it.id }
        }
        val native = when (action?.plugin) {
            "focus" -> focus.present(now).keys[action.index]
            "phone" -> phoneFrame().keys[action.index].let { tile ->
                if (action.index in 6..7 || tile.readOnly) tile else tile.copy(enabled = false, footer = K.AndroidOnly.text())
            }
            "audio" -> if (!simulator) {
                val control = MediaKey.forSlot(action.index)
                val glyph = listOf(PlayerGlyph.VOLUME_DOWN, PlayerGlyph.MUTE, PlayerGlyph.VOLUME_UP,
                    PlayerGlyph.PREVIOUS, PlayerGlyph.PLAY_PAUSE, PlayerGlyph.NEXT).getOrNull(action.index)
                KeyTile(action.command?.displayLabel ?: control?.label ?: when (action.index) {
                    6 -> K.SeekBack.text(); 8 -> K.SeekForward.text(); else -> K.NowPlaying.text()
                }, tel.misa.mxpocket.i18n.DesktopCopy.WindowsMedia.text(), 0xff303438.toInt(), glyph = glyph, playerFace = glyph != null,
                    showLabel = glyph == null, showFooter = false, enabled = control != null)
            } else {
                val i = action.index
                val level = VolumeLevel(if (muted) 0 else volume, 15, muted)
                val glyph = listOf(PlayerGlyph.VOLUME_DOWN, when (action.command) {
                    DeckCommand.MUTE -> PlayerGlyph.MUTE
                    DeckCommand.UNMUTE -> PlayerGlyph.UNMUTE
                    else -> if (muted) PlayerGlyph.UNMUTE else PlayerGlyph.MUTE
                },
                    PlayerGlyph.VOLUME_UP, PlayerGlyph.PREVIOUS, if (playing) PlayerGlyph.PAUSE else PlayerGlyph.PLAY,
                    PlayerGlyph.NEXT, PlayerGlyph.BACK_10, PlayerGlyph.TRACK, PlayerGlyph.FORWARD_10).getOrNull(i)
                val text = when {
                    action.command == DeckCommand.VOLUME_LEVEL -> level.description
                    i == 7 -> tracks[track].text()
                    i == 4 -> (if (playing) K.Pause else K.Play).text()
                    i == 1 && action.command == null -> (if (muted) K.Unmute else K.Mute).text()
                    else -> (action.command ?: audioSlots.getOrNull(i))?.displayLabel.orEmpty()
                }
                KeyTile(text, when (i) {
                    -1 -> if (action.command == DeckCommand.VOLUME_LEVEL) K.Volume.text() else K.AndroidOnly.text()
                    1 -> level.description
                    6, 8 -> K.NoSeek.text()
                    7 -> (if (playing) K.Playing else K.Paused).text()
                    else -> ""
                }, 0xff303438.toInt(), glyph = glyph, primary = i == 4, volumeLevel = level.takeIf { i == 1 },
                    showLabel = i == 7 || glyph == null, showFooter = glyph == null, playerFace = glyph != null,
                    readOnly = i == 7 || action.command == DeckCommand.VOLUME_LEVEL,
                    enabled = (i < 6 || i == 7) && action.command != DeckCommand.MEDIA_ACCESS)
            }
            "game.xo" -> xo.frame().keys[action.index]
            "studio" -> {
                val command = studioSlots.getOrNull(action.index)
                KeyTile(command?.displayLabel ?: "-", K.AndroidOnly.text(), 0xff49345f.toInt(), enabled = false,
                    glyph = command?.legend(), showLabel = command != null, showFooter = command != null, quietFace = true)
            }
            "navigation" -> (action.command ?: navigationCommands[action.index]).navigationFace(systemAccess = null)
            "teams" -> TeamsState().tile(action.command ?: teamsSlots[action.index]).let { tile ->
                tile.copy(label = if (tile.readOnly) K.CallStatus.text() else tile.label,
                    enabled = false, footer = K.AndroidOnly.text())
            }
            "launcher" -> {
                val title = when (action.index) { 0 -> K.NoFavorites; 7 -> K.EditFavorites; 8 -> K.Favorites; else -> null }
                KeyTile(title?.text() ?: "-", K.AndroidOnly.text(), 0xff384674.toInt(), enabled = false,
                    showLabel = title != null, showFooter = title != null, quietFace = true)
            }
            else -> null
        }
        val navigation = assigned?.id in setOf(DeckActions.PAGE, DeckActions.PROFILE, DeckActions.BACK, DeckActions.ROOT, MORE_PROFILES)
        val owner = assigned?.slotOwner()
        val ownerAvailable = owner == null || owner == selectedPlugin
        val unavailableReason = profileReason(deck.activeProfileId) ?: assigned?.let { bindingReason(it, simulator) }
        val disabled = action?.plugin in deck.disabledPluginIds || assigned?.targetProfileId() in deck.disabledPluginIds ||
            assigned?.targetProfileId() == GAME_PROFILE
        val available = key.enabled && !disabled && unavailableReason == null && ownerAvailable && (navigation || native?.enabled == true && native.label != "-")
        return key.present(native, name ?: "-", when {
            !key.enabled || disabled -> K.Disabled.text()
            unavailableReason != null -> unavailableReason
            !ownerAvailable -> K.Unavailable.text()
            native != null -> native.footer
            !available && assigned != null -> K.Unavailable.text()
            else -> ""
        }, available)
    }

    data class Board(val faces: List<KeyTile>, val grid: VirtualGrid)

    fun board(now: Long, snapshot: DeckState = deck): Board {
        // An edit away and back may be conflated by Compose. The repository publishes fresh immutable snapshots.
        if (snapshot !== lastSnapshot) { generation++; lastSnapshot = snapshot }
        val page = snapshot.activePage
        val owner = page.selectedSlotOwner(knownPlugins::contains)
        val pluginPage = if (owner == "game.xo") xo.interactionPage else 0
        fun frame(hold: Boolean) = PluginFrame(page.keys.map { key ->
            val call = if (hold) key.holdAction else key.action ?: key.holdAction
            face(snapshot, key, call, owner, now)
        }, pluginPage)
        val display = frame(false)
        val holds = if (page.keys.any { it.holdAction != null }) frame(true) else null
        return Board(display.keys, virtualGrid(generation, snapshot, display, holds))
    }

    fun tiles(now: Long): List<KeyTile> = board(now).faces

    /** A stale Home callback is never reinterpreted as input to the currently displayed slot. */
    fun press(event: PocketScreenEvent.VirtualKey, now: Long): String? = synchronized(repository) {
        val current = board(now)
        if (!simulator || event.grid != current.grid) null else press(event.index, now, event.hold, current)
    }

    internal fun physical(slot: Int, grid: VirtualGrid, now: Long, hold: Boolean): String? = synchronized(repository) {
        val current = board(now)
        if (simulator || grid != current.grid) null else press(slot, now, hold, current)
    }

    private fun press(slot: Int, now: Long, hold: Boolean, current: Board): String {
        val key = current.grid.page.keys.getOrNull(slot) ?: return K.Unassigned.text()
        val call = (if (hold) key.holdAction else key.action) ?: return K.Unassigned.text()
        if (DeckCommand.fromId(call.id)?.deck in setOf("audio", "navigation") && call.commandBinding().params.isNotEmpty())
            return K.InvalidParams.text()
        val chosen = if (hold) current.grid.holds?.get(slot) else current.grid.taps[slot]
        if (!current.grid.taps[slot].actionable || chosen?.actionable != true) {
            val owner = current.grid.page.selectedSlotOwner(knownPlugins::contains)
            val label = if (hold) face(deck, key, call, owner, now).label else current.faces[slot].label
            return when (binding(call)?.plugin) {
                "phone" -> K.PhoneUnavailable.text(label)
                else -> K.SystemUnavailable.text(label)
            }
        }
        val navigation = when (call.id) {
            DeckActions.BACK -> repository.back()
            DeckActions.ROOT -> repository.root()
            DeckActions.PAGE -> repository.openPage(call.params.getValue(DeckActions.PAGE_ID))
            DeckActions.PROFILE -> if (deck.activeProfileId == PROFILE_HOME) repository.openProfileRoot(call.params.getValue(DeckActions.PROFILE_ID))
                else repository.selectProfile(call.params.getValue(DeckActions.PROFILE_ID))
            MORE_PROFILES -> repository.moreProfiles()
            else -> null
        }
        if (navigation != null) return navigation.fold({ K.OnPage.text(profileName, deck.activeProfile.displayPageName(deck.activePage)) },
            { it.message ?: K.CannotChangePage.text() })
        val (plugin, index, command) = binding(call)
            ?: return K.NotInSimulator.text(call.id)
        if (plugin == "game.xo") {
            xo.press(index)
            return xo.cells()[4].footer
        }
        val label = command?.displayLabel ?: if (hold) face(deck, key, call,
            current.grid.page.selectedSlotOwner(knownPlugins::contains), now).label else current.faces[slot].label
        when (plugin) {
            "focus" -> when (index) {
                0 -> focus.toggle(now)
                2 -> focus.reset()
                3 -> focus.adjust(-5)
                5 -> focus.adjust(5)
                6, 7, 8 -> focus.select(listOf(15, 25, 50)[index - 6])
                else -> return K.FocusRemaining.text(focus.label(now))
            }
            "audio" -> if (!simulator) {
                val control = MediaKey.forSlot(index) ?: return K.SystemUnavailable.text(label)
                return sendMedia(control)
            } else when (index) {
                -1 -> return (if (command == DeckCommand.VOLUME_LEVEL) K.VolumeDisplayOnly else K.MediaAndroid).text()
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
                6, 8 -> return K.NoPreviewSeek.text()
                7 -> return K.TrackPlaying.text(tracks[track].text())
            }
            else -> return K.SystemUnavailable.text(label)
        }
        return if (plugin == "focus") K.FocusRemaining.text(focus.label(now)) else K.PreviewOnly.text(label)
    }

}
