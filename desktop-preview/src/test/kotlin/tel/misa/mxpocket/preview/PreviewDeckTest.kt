package tel.misa.mxpocket.preview

import kotlin.test.Test
import org.junit.Rule
import org.junit.rules.TemporaryFolder
import tel.misa.mxpocket.deck.DeckRepository
import java.io.File
import kotlin.test.assertEquals
import kotlin.test.assertNull
import tel.misa.mxpocket.FocusTimer
import tel.misa.mxpocket.plugins.present
import tel.misa.mxpocket.actions.ActionCall
import tel.misa.mxpocket.deck.DeckActions
import tel.misa.mxpocket.deck.DeckKey
import tel.misa.mxpocket.decks.DeckCommand
import tel.misa.mxpocket.shared.ui.factoryKeyBindings
import tel.misa.mxpocket.shared.ui.PocketScreenEvent

class PreviewDeckTest {
    @get:Rule val folder = TemporaryFolder()
    @Test fun manualTeamsPageNeverSimulatesALiveMeetingOrMicToggle() {
        val repo = DeckRepository(File(folder.root, "deck.json"))
        val deck = PreviewDeck(repo)
        deck.select("teams")
        deck.press(7, 0)
        assertEquals("In call", repo.state.value.activePage.name)
        val mic = deck.tiles(0)[4]
        assertEquals("Mic unavailable", mic.label)
        check(!mic.enabled)
        deck.press(4, 0)
        assertEquals(mic, deck.tiles(0)[4])
        deck.press(8, 0)
        assertEquals("Work", repo.state.value.activePage.name)
    }

    @Test fun holdUsesItsOwnActionAndAvailabilityWithoutFallingBackToTap() {
        val repo = DeckRepository(File(folder.root, "deck.json"))
        val deck = PreviewDeck(repo)
        deck.select("audio")
        val key = DeckKey(action = ActionCall(DeckCommand.LOUDER.id))
        fun bind(hold: DeckCommand) = repo.setKey("audio", "root", 0, key.copy(holdAction = ActionCall(hold.id))).getOrThrow()
        fun volume() = deck.tiles(0)[1].footer
        bind(DeckCommand.QUIETER)
        deck.press(0, 0, hold = true)
        assertEquals("5 / 15", volume())
        deck.press(0, 0)
        assertEquals("6 / 15", volume())
        // A readout and an Android-only action are both unavailable, irrespective of the Tap face.
        for (hold in listOf(DeckCommand.VOLUME_LEVEL, DeckCommand.MEDIA_ACCESS)) {
            bind(hold)
            val board = deck.board(0)
            check(!board.grid.holds!![0].actionable)
            check(deck.press(PocketScreenEvent.VirtualKey(0, board.grid, hold = true), 0)!!.contains("unavailable"))
            assertEquals("6 / 15", volume())
        }
    }

    @Test fun holdOnlyPluginUsesFallbackOwnerAndRejectsEventsFromAnEarlierBoard() {
        val repo = DeckRepository(File(folder.root, "deck.json"))
        val deck = PreviewDeck(repo)
        val page = repo.createPage("focus", "Hold only").getOrThrow()
        val audio = DeckKey(holdAction = ActionCall("plugin.key", mapOf("pluginId" to "audio", "index" to "2")))
        repo.setKey("focus", page, 0, audio).getOrThrow()
        repo.openPage(page).getOrThrow()
        fun hold() = PocketScreenEvent.VirtualKey(0, deck.board(0).grid, hold = true)
        val initial = hold()
        check(deck.tiles(0)[0].let { it.enabled && it.label == "Volume +" })
        assertEquals("Unassigned key.", deck.press(0, 0))
        check(deck.press(initial, 0)!!.contains("Windows media is unchanged"))
        deck.resetInput()
        assertNull(deck.press(initial, 0))
        val beforeRebind = hold()
        repo.setKey("focus", page, 1, DeckKey(action = ActionCall("plugin.key",
            mapOf("pluginId" to "focus", "index" to "0")))).getOrThrow()
        assertNull(deck.press(beforeRebind, 0))
        check(!deck.tiles(0)[0].enabled) // Any registered Tap owner wins, even in a later slot.
        check(deck.press(0, 0, hold = true).contains("unavailable"))
        val beforePageSwitch = hold()
        deck.select("audio")
        assertNull(deck.press(beforePageSwitch, 0))
        assertEquals("7 / 15", deck.tiles(0)[1].footer)
    }

    @Test fun legacyOwnerAndExplicitMediaCommandsKeepTheirMeaningsOnEditedPages() {
        val repo = DeckRepository(File(folder.root, "deck.json"))
        val deck = PreviewDeck(repo)
        fun bind(i: Int, command: DeckCommand, migrated: Boolean = false) {
            val owner = if (migrated) mapOf(DeckActions.LEGACY_SLOT_OWNER to "audio") else emptyMap()
            repo.setKey("focus", "root", i, DeckKey(action = ActionCall(command.id, owner))).getOrThrow()
        }
        bind(1, DeckCommand.MUTE, true)
        bind(2, DeckCommand.QUIETER)
        bind(3, DeckCommand.VOLUME_LEVEL)
        check(!deck.tiles(0)[1].enabled)
        deck.press(1, 0); deck.press(2, 0)
        check(deck.tiles(0)[3].let { it.label == "5 / 15" && it.readOnly })
        bind(0, DeckCommand.UNMUTE, true)
        check(deck.tiles(0)[1].enabled)
        repeat(2) { deck.press(1, 0) }
        check(deck.tiles(0)[3].label == "0 / 15")
        repeat(2) { deck.press(0, 0) }
        check(deck.tiles(0)[3].label == "5 / 15")
        repo.setKey("focus", "root", 2, DeckKey(action = ActionCall(DeckCommand.QUIETER.id,
            mapOf("unexpected" to "x")))).getOrThrow()
        check(!deck.tiles(0)[2].enabled)
        check(deck.press(2, 0).startsWith("Invalid action parameters"))
        check(deck.tiles(0)[3].label == "5 / 15")
    }
    @Test fun pageButtonsUseFolderHistoryBeforeCyclingProfiles() {
        val repo = DeckRepository(File(folder.root, "deck.json"))
        val deck = PreviewDeck(repo)
        val page = repo.createPage("focus", "Projects").getOrThrow()
        deck.cycle(1)
        assertEquals(page, repo.state.value.activePage.id)
        deck.cycle(-1)
        assertEquals("root", repo.state.value.activePage.id)
        assertEquals("focus", deck.profileId)
    }

    @Test fun focusUsesTheProductionFrameAndKeepsPerKeyVisualOverrides() {
        val repo = DeckRepository(File(folder.root, "deck.json"))
        val deck = PreviewDeck(repo)
        val factory = FocusTimer().present(0).keys
        assertEquals(factory, deck.tiles(0))
        val state = repo.state.value
        val custom = state.activePage.keys[2].copy(label = "Restart", icon = "R", color = 0xff7d3645.toInt())
        repo.setKey(state.activeProfileId, state.activePage.id, 2, custom).getOrThrow()
        assertEquals(factory.mapIndexed { i, tile ->
            if (i == 2) tile.copy(label = "Restart", icon = "R", background = custom.color) else tile
        }, deck.tiles(0))
    }

    @Test fun navigationUsesFactoryBindingsAndNeverClaimsAndroidAccess() {
        val deck = PreviewDeck(DeckRepository(File(folder.root, "deck.json")))
        deck.select("navigation")
        val up = factoryKeyBindings.single { it.label == "Navigation key 2: Scroll up" }
        assertEquals(deck.repository.state.value.activePage.keys[1].action, up.call)
        val tiles = deck.tiles(0)
        check(tiles.map { it.label } == listOf("Back", "Scroll up", "Home", "Prev focus", "Select",
            "Next focus", "Recents", "Scroll down", "Access info"))
        check(tiles.none { it.enabled && !it.readOnly })
        check(tiles[6].let { !it.enabled && it.label == "Recents" && it.footer == "Needs access" })
        check(tiles.last().footer == "System nav off")
        tiles.forEachIndexed { index, tile -> check(deck.press(index, 0).startsWith(tile.label)) }
    }

    @Test fun factoryNavigationTimerAndSimulatedAudio() {
    val deck = PreviewDeck(DeckRepository(File(folder.root, "deck.json")))
    fun label(key: Int, now: Long = 0) = deck.tiles(now)[key - 1].label
    check(deck.profileName == "Focus")
    deck.press(6, 0) // 15-minute preset
    deck.press(0, 0)
    check(label(2, 1_000) == "14:59")

    deck.cycle(-1)
    check(deck.profileId == "onenote")
    deck.cycle(-1)
    check(deck.profileId == "teams")
    deck.cycle(-1)
    check(deck.profileId == "game.xo")
    deck.cycle(-1)
    check(deck.profileName == "Navigation")
    check(deck.press(1, 1_000).contains("No system action"))
    deck.cycle(4)
    check(label(1, 1_000) == "Pause")
    deck.cycle(2)
    check(deck.profileName == "Phone")
    check(deck.press(0, 1_000).contains("No phone action"))
    deck.press(7, 1_000) // Phone's Audio shortcut
    check(deck.profileName == "Audio")
    fun mute() = deck.tiles(0)[1]
    check(mute().label == "Mute" && mute().footer == "6 / 15")
    deck.press(2, 1_000); check(mute().footer == "7 / 15")
    deck.press(1, 1_000); check(mute().label == "Unmute" && mute().footer == "0 / 15")
    deck.press(1, 1_000); check(mute().footer == "7 / 15")
    deck.press(0, 1_000); check(mute().footer == "6 / 15")
    deck.press(4, 1_000); check(label(5) == "Pause")
    val original = label(8)
    deck.press(5, 1_000); check(label(8) != original)
    deck.press(3, 1_000); check(label(8) == original)
    val before = deck.tiles(0)
    check(before[7].readOnly)
    for (slot in 6..8) { check(!before[slot].enabled || before[slot].readOnly); deck.press(slot, 1_000) }
    check(before == deck.tiles(0))
    repeat(deck.repository.state.value.profiles.sumOf { it.pages.size }) {
        check(deck.tiles(1_000).size == 9)
        deck.cycle(1)
    }
    check(deck.profileName == "Audio")
    }

    @Test fun xoUsesThePlayableAndroidBoard() {
        val deck = PreviewDeck(DeckRepository(File(folder.root, "deck.json")))
        deck.select("game.xo")
        fun press(slot: Int) = deck.press(slot, 0)
        fun label(slot: Int) = deck.tiles(0)[slot].label
        check(label(0) == "1 Player" && label(2) == "2 Players")
        press(0)
        press(0)
        check(label(0) == "X" && label(4) == "O")
        press(1)
        check(press(3).startsWith("CPU wins"))
        press(8)
        press(2)
        for (slot in listOf(0, 3, 1, 4)) press(slot)
        check(press(2).startsWith("X wins"))
        deck.cycle(1)
        deck.cycle(-1)
        press(4)
        check(label(2) == "2 Players")
    }
}
