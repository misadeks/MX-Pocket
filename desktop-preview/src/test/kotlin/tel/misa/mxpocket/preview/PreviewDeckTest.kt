package tel.misa.mxpocket.preview

import kotlin.test.Test
import org.junit.Rule
import org.junit.rules.TemporaryFolder
import tel.misa.mxpocket.deck.DeckRepository
import java.io.File
import kotlin.test.assertEquals
import tel.misa.mxpocket.FocusTimer
import tel.misa.mxpocket.plugins.present

class PreviewDeckTest {
    @get:Rule val folder = TemporaryFolder()
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

    @Test fun factoryNavigationTimerAndSimulatedAudio() {
    val deck = PreviewDeck(DeckRepository(File(folder.root, "deck.json")))
    fun label(key: Int, now: Long = 0) = deck.tiles(now)[key - 1].label
    check(deck.profileName == "Focus")
    deck.press(6, 0) // 15-minute preset
    deck.press(0, 0)
    check(label(2, 1_000) == "14:59")

    deck.cycle(-1)
    check(deck.profileName == "Navigation")
    check(deck.press(1, 1_000).contains("No system action"))
    deck.cycle(1)
    check(label(1, 1_000) == "Pause")
    deck.cycle(2)
    check(deck.profileName == "Phone")
    check(deck.press(0, 1_000).contains("No phone action"))
    deck.press(7, 1_000) // Phone's Audio shortcut
    check(deck.profileName == "Audio")
    deck.press(2, 1_000)
    check(label(8) == "7 / 15")
    deck.press(6, 1_000)
    check(label(8) == "0 / 15")
    deck.press(8, 1_000)
    check(label(8) == "7 / 15")
    repeat(deck.profileCount) {
        check(deck.tiles(1_000).size == 9)
        deck.cycle(1)
    }
    check(deck.profileName == "Audio")
    }
}
