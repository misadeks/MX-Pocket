package tel.misa.mxpocket.preview

import kotlin.test.Test

class PreviewDeckTest {
    @Test fun factoryNavigationTimerAndSimulatedAudio() {
    val deck = PreviewDeck()
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
