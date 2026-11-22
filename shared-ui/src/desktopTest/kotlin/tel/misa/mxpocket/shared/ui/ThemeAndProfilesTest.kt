package tel.misa.mxpocket.shared.ui

import androidx.compose.foundation.layout.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.unit.dp
import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import tel.misa.mxpocket.FocusTimer
import tel.misa.mxpocket.deck.*
import tel.misa.mxpocket.plugins.present

class ThemeAndProfilesTest {
    @get:Rule val ui = createComposeRule()
    @get:Rule val temp = TemporaryFolder()

    private fun state(mode: ThemeMode = ThemeMode.SYSTEM) = PocketScreenState("Not connected", "Focus",
        FocusTimer().present(0).keys.map { it.toGridTile(true) },
        defaultDeck().profiles.map { ProfileChoice(it.id, it.name) }, "focus", themeMode = mode)

    @Test fun homeOffersConnectionAndOnlySettingsChangesTheAppearance() {
        var mode by mutableStateOf(ThemeMode.DARK)
        var tab by mutableStateOf(WorkspaceTab.HOME)
        val events = mutableListOf<PocketScreenEvent>()
        ui.setContent {
            PocketTheme(mode) {
                PocketWorkspace(tab, { tab = it }, state(mode), emptyList(), {
                    events += it
                    if (it is PocketScreenEvent.SetTheme) mode = it.mode
                }, profiles = {}, workflows = {})
            }
        }
        ui.onNodeWithContentDescription("Switch to light theme").assertDoesNotExist()
        ui.onNodeWithContentDescription("Connect to device").performClick()
        assertEquals(listOf(PocketScreenEvent.ToggleConnection), events)
        ui.onNodeWithText("Settings").performClick()
        ui.onNodeWithText("Display", ignoreCase = true).assertDoesNotExist()
        ui.onNodeWithText("Choose keypad background").assertDoesNotExist()
        ui.onNodeWithText("Repaint all keys").assertDoesNotExist()
        ui.onNodeWithText("Display profile:", substring = true).assertDoesNotExist()
        ui.onNodeWithText("Diagnostics").performScrollTo().performClick()
        ui.onNodeWithText("Display profile:", substring = true).assertExists()
        ui.onNodeWithText("Back to Settings").performClick()
        ui.onNodeWithText("Display profile:", substring = true).assertDoesNotExist()
        ui.onNodeWithText("Dark").assertIsSelected()
        ui.onNodeWithText("Light").performScrollTo().performClick()
        ui.onNodeWithText("Dark").performScrollTo().performClick()
        ui.onNodeWithText("System").performScrollTo().performClick()
        ui.runOnIdle {
            assertEquals(listOf(ThemeMode.LIGHT, ThemeMode.DARK, ThemeMode.SYSTEM),
                events.filterIsInstance<PocketScreenEvent.SetTheme>().map { it.mode })
        }
    }

    @Test fun carouselSelectsATappedProfileAndNeverSwitchesOnItsOwn() {
        val profiles = defaultDeck().profiles.map { ProfileChoice(it.id, it.name) }
        var active by mutableStateOf("focus")
        val chosen = mutableListOf<String>()
        ui.setContent {
            PocketTheme(ThemeMode.LIGHT) {
                Box(Modifier.width(400.dp)) { ProfileCarousel(profiles, active) { chosen += it; active = it } }
            }
        }
        ui.waitForIdle()
        ui.runOnIdle { assertTrue("Showing the carousel must not switch profiles", chosen.isEmpty()) }
        ui.onAllNodesWithText("Audio").onFirst().performClick()
        ui.waitForIdle()
        ui.runOnIdle {
            assertEquals("audio", active)
            assertTrue(chosen.isNotEmpty() && chosen.all { it == "audio" })
        }
    }

    @Test fun profilesTabDoesNotSelectOverTheHiddenGameOrOfferItsEditor() {
        val repo = DeckRepository(File(temp.root, "deck.json"))
        repo.enterGame().getOrThrow()
        ui.setContent {
            val live by repo.operatingState.collectAsState()
            val surface = state().copy(activeId = live.activeProfileId,
                profiles = live.visibleProfiles.map { ProfileChoice(it.id, it.name) })
            PocketTheme {
                PocketWorkspace(WorkspaceTab.PROFILES, {}, surface, emptyList(), {}, profiles = {
                    SimulatorProfiles(surface, { repo.openProfileRoot(it).getOrThrow() }) {
                        LayoutEditor(repo.asEditorStore(), emptyList(), emptyMap(), {}, {})
                    }
                }, workflows = {})
            }
        }
        ui.waitForIdle()
        ui.onNodeWithText("Swap keys").assertDoesNotExist()
        ui.onNodeWithText("Tic Tac Toe").assertDoesNotExist()
        ui.runOnIdle { assertEquals(GAME_PROFILE, repo.operatingState.value.activeProfileId) }
        ui.onAllNodesWithText("Focus").onFirst().performClick()
        ui.onNodeWithText("Swap keys").assertExists()
    }

    private fun openEditor(): DeckRepository {
        val repo = DeckRepository(File(temp.root, "deck.json"))
        ui.setContent {
            PocketTheme(ThemeMode.DARK) {
                Box(Modifier.width(400.dp).fillMaxHeight()) {
                    LayoutEditor(repo.asEditorStore(), emptyList(), emptyMap(), {}, {})
                }
            }
        }
        return repo
    }

    private fun assertSwapped(repo: DeckRepository, before: List<DeckKey>, a: Int, b: Int) {
        ui.waitUntil(5_000) { repo.state.value.activePage.keys != before }
        ui.runOnIdle {
            val after = repo.state.value.activePage.keys
            val expected = before.toMutableList().also { it[a] = before[b]; it[b] = before[a] }
            assertEquals(expected, after)
            assertEquals("root", repo.state.value.activePage.id)
        }
    }

    @Test fun editorHasNoKeyPreviewOnlyTheSwapButton() {
        openEditor()
        ui.onNodeWithText("Key positions").assertDoesNotExist()
        ui.onNodeWithText("Page on deck").assertDoesNotExist()
        ui.onNodeWithText("Open page on deck").assertDoesNotExist()
        ui.onAllNodesWithContentDescription("Key 1,", substring = true).assertCountEquals(0)
        ui.onNodeWithText("Swap keys").assertExists()
    }

    @Test fun draggingAKeyOntoAnotherSwapsOnlyThatPairAndKeepsTheDialogOpen() {
        val repo = openEditor()
        val before = repo.state.value.activePage.keys
        ui.onNodeWithText("Swap keys").performScrollTo().performClick()
        ui.onNodeWithTag("swap-grid").performTouchInput {
            val unit = height / 561f
            swipe(Offset(115 * unit, 116 * unit), Offset(240 * unit, 116 * unit), 400)
        }
        assertSwapped(repo, before, 0, 1)
        ui.onNodeWithText("Drag a key onto another", substring = true).assertExists()
        ui.onNodeWithText("Done").performClick()
        ui.onNodeWithText("Drag a key onto another", substring = true).assertDoesNotExist()
    }

    @Test fun screenReadersCanSwapWithoutDragging() {
        val repo = openEditor()
        val before = repo.state.value.activePage.keys
        ui.onNodeWithText("Swap keys").performScrollTo().performClick()
        val actions = ui.onNodeWithContentDescription("Key 1,", substring = true).fetchSemanticsNode()
            .config[SemanticsActions.CustomActions]
        assertEquals(8, actions.size)
        ui.runOnIdle { actions.single { it.label == "Swap with key 3" }.action() }
        assertSwapped(repo, before, 0, 2)
    }

    @Test fun tappingAKeyInTheSwapDialogOpensItsEditorWithoutRunningIt() {
        val repo = openEditor()
        val before = repo.state.value
        ui.onNodeWithText("Swap keys").performScrollTo().performClick()
        ui.onNodeWithContentDescription("Key 1,", substring = true).performClick()
        ui.onNodeWithText("Edit key 1").assertExists()
        ui.onNodeWithText("Drag a key onto another", substring = true).assertDoesNotExist()
        ui.runOnIdle { assertEquals(before, repo.state.value) }
    }

    @Test fun choosingAProfileInThePickerPutsItOnTheKeypad() {
        val repo = openEditor()
        ui.onNodeWithText("Launcher / Launcher ▾").performClick()
        ui.onNodeWithText("Audio").performClick()
        ui.waitUntil(5_000) { repo.state.value.activeProfileId == "audio" }
        ui.onNodeWithText("Audio / Audio ▾").assertExists()
    }
}
