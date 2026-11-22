package tel.misa.mxpocket.shared.ui

import androidx.compose.foundation.layout.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import tel.misa.mxpocket.FocusTimer
import tel.misa.mxpocket.actions.ActionCall
import tel.misa.mxpocket.deck.*
import tel.misa.mxpocket.plugins.present

class WorkspaceWindowTest {
    @get:Rule val ui = createComposeRule()
    @get:Rule val temp = TemporaryFolder()

    @Test fun simulatorOpensDiagnosticsWithoutReplacingSettingsOrOfferingAndroidActions() {
        var open by mutableStateOf(false)
        val state = PocketScreenState("Virtual keypad connected", "Focus", emptyList(), emptyList(), "focus",
            simulator = DesktopSimulator("Windows simulator"), diagnostics = "No USB adapter", log = "Connected")
        ui.setContent {
            PocketTheme {
                PocketWorkspace(WorkspaceTab.SETTINGS, {}, state, emptyList(), {}, {}, {}, openDiagnosticsWindow = { open = true })
                if (open) DiagnosticsScreen(state, { open = false }) {}
            }
        }
        val details = ui.onNodeWithText(state.diagnostics)
        details.assertDoesNotExist()
        ui.onNodeWithText("Diagnostics").performScrollTo().performClick()
        details.assertExists()
        ui.onNodeWithText(state.log).assertExists()
        ui.onNodeWithText("Refresh USB diagnostics").assertDoesNotExist()
        ui.onNodeWithText("Export session log").assertDoesNotExist()
        ui.onNodeWithText("Close").performScrollTo().performClick()
        details.assertDoesNotExist()
        ui.onNodeWithText("Diagnostics").assertHasClickAction()
    }

    @Test fun switchingOffTheEditedProfileHidesItsKeysButNotACustomNamesake() {
        val repo = DeckRepository(File(temp.root, "deck.json"))
        val custom = repo.duplicateProfile("audio", "Audio").getOrThrow()
        repo.renameProfile("audio", "My player").getOrThrow()
        repo.selectProfile("audio").getOrThrow()
        val store = repo.asEditorStore()
        ui.setContent {
            CompositionLocalProvider(LocalDensity provides Density(.75f)) {
                Box(Modifier.requiredWidth(900.dp)) { PocketTheme {
                    LayoutEditor(store, availableFactoryKeyBindings(repo.state.collectAsState().value.disabledPluginIds),
                        emptyMap(), {}, {})
                } }
            }
        }
        ui.onNodeWithText("Swap keys").performScrollTo().performClick()
        ui.onNodeWithContentDescription("Key 1,", substring = true).performClick()
        ui.onNodeWithTag("key-label").assertExists()
        ui.runOnIdle { repo.setDisabledPlugins(setOf("audio")) }
        ui.onNodeWithTag("key-label").assertDoesNotExist()
        ui.onNodeWithText("My player", substring = true).assertDoesNotExist()
        ui.onNodeWithText("Tic Tac Toe", substring = true).assertDoesNotExist()
        ui.onNodeWithText("Audio").performClick()
        ui.waitUntil(5_000) { repo.state.value.activeProfileId == custom }
        ui.onNodeWithText("Swap keys").performScrollTo().performClick()
        ui.onNodeWithContentDescription("Key 1,", substring = true).performClick()
        ui.onNodeWithTag("choose-binding").performClick()
        ui.onNodeWithText("Audio key 1").assertDoesNotExist()
    }

    @Test fun unsupportedPluginExplainsItselfWithoutAKeyboardOrClickTarget() {
        val supported = PluginItem("focus", "Focus", true, "Timer", "Runs locally.", true, false, null, canToggle = false)
        val blocked = supported.copy(id = "studio", title = "Studio", settingsLabel = "Camera controls",
            canToggle = true, unavailableReason = "Camera controls require Android.")
        val events = mutableListOf<PocketScreenEvent>()
        var width by mutableStateOf(1100.dp)
        ui.setContent {
            Box(Modifier.requiredWidth(width)) {
                PocketTheme { PluginsScreen(listOf(blocked, supported), events::add) }
            }
        }
        for (w in listOf(1100.dp, 390.dp)) {
            ui.runOnIdle { width = w }
            val row = ui.onNodeWithText("Studio")
            row.assertTextContains(blocked.unavailableReason!!).assertIsNotEnabled().assertHasNoClickAction()
            row.assert(SemanticsMatcher.keyNotDefined(SemanticsActions.RequestFocus))
            ui.onNodeWithText("Camera controls").assertDoesNotExist()
            ui.onNodeWithText("Make default profile").performClick()
        }
        assertEquals(List(2) { PocketScreenEvent.DefaultProfile("focus") }, events)
    }

    @Test fun unavailableBindingCanBeReadAndReplacedWithoutChangingTheOtherGesture() {
        val camera = BindingOption("Camera", ActionCall("camera.open"), "Requires the phone camera.")
        val back = BindingOption("Back", ActionCall(DeckActions.BACK))
        val draft = KeyDraft(defaultDeck().activePage.keys.first().copy(action = camera.call, holdAction = back.call))
        draft.picking = BindingTarget.TAP
        ui.setContent { PocketTheme { BindingDialog(listOf(camera, back), draft) } }
        ui.onNodeWithText("Camera").assertTextContains(camera.unavailableReason!!)
            .assertHasNoClickAction().assert(SemanticsMatcher.keyNotDefined(SemanticsActions.RequestFocus))
        ui.runOnIdle { assertEquals(camera.call, draft.call) }
        ui.onNodeWithText("Unassigned").performClick()
        ui.runOnIdle {
            assertEquals(null, draft.call)
            assertEquals(back.call, draft.holdCall)
        }
    }

    @Test fun openingWorkshopKeepsAnUnavailableImportedProfileIntact() {
        val repo = DeckRepository(File(temp.root, "imported.json"))
        repo.selectProfile("studio").getOrThrow()
        val imported = repo.state.value
        val state = PocketScreenState("", "Studio", emptyList(), imported.profiles.map {
            ProfileChoice(it.id, it.name, if (it.id == "studio") "Requires Android camera." else null)
        }, imported.activeProfileId)
        ui.setContent {
            Box(Modifier.requiredWidth(1000.dp)) {
                PocketTheme { LayoutEditor(repo.asEditorStore(), emptyList(), emptyMap(), {}, {}, live = state) }
            }
        }
        ui.onNode(hasText("Studio") and hasText("Requires Android camera.")).assertIsNotEnabled().assertHasNoClickAction()
        ui.onNodeWithText("Pin this profile").assertIsNotEnabled()
        ui.onNodeWithText("Set as default").assertIsNotEnabled()
        ui.onNodeWithText("Swap keys").assertIsEnabled()
        ui.runOnIdle { assertEquals(imported, repo.state.value) }
    }

    @Test fun desktopUsbAndSimulationNeverShareAnInputTarget() {
        val frame = FocusTimer().present(0)
        val grid = virtualGrid(1, defaultDeck(), frame, null)
        val sim = DesktopSimulator("Test virtual keypad", supportsUsb = true)
        var state by mutableStateOf(PocketScreenState("", "Focus", frame.keys.map { it.toGridTile(true) },
            listOf(ProfileChoice("focus", "Focus"), ProfileChoice("studio", "Studio", "Camera requires Android.")),
            "focus", simulator = sim))
        var tab by mutableStateOf(WorkspaceTab.HOME)
        val events = mutableListOf<PocketScreenEvent>()
        ui.setContent { PocketTheme { PocketWorkspace(tab, { tab = it }, state, emptyList(), events::add, {}, {}) } }
        ui.onNodeWithText("Switch layout").performScrollTo().performClick()
        ui.onNodeWithText("Studio").assertIsNotEnabled().assertHasNoClickAction()
        ui.onNode(hasText("Focus") and isSelected()).performClick()
        ui.onNodeWithText("Connect USB keypad").performScrollTo().performClick()
        ui.onNodeWithText("Settings").performClick()
        ui.onNodeWithText("Feature setup").assertDoesNotExist()
        ui.onNodeWithText("Choose keypad background").assertDoesNotExist()
        ui.onNodeWithText("Repaint LCD").assertDoesNotExist()
        ui.runOnIdle { state = state.copy(simulator = sim.copy(grid = grid)) }
        ui.onNodeWithText("Connect USB keypad").assertIsNotEnabled()
        ui.onNodeWithText("Disconnect virtual keypad").performScrollTo().performClick()
        // Even if the host briefly exposes a stale grid, USB owns input from connection start.
        ui.runOnIdle { state = state.copy(connectionActive = true) }
        ui.onNodeWithText("Connect virtual keypad").assertIsNotEnabled()
        ui.onNodeWithText("Cancel USB connection").assertIsEnabled()
        ui.onNodeWithText("Repaint LCD").assertDoesNotExist()
        ui.runOnIdle { state = state.copy(usbReady = true) }
        ui.onNodeWithText("Repaint LCD").performScrollTo().performClick()
        ui.onNodeWithText("Home").performClick()
        ui.onNodeWithText("USB connected").assertExists()
        ui.onNodeWithContentDescription("Key 1, Start, Focus").assertHasNoClickAction()
        assertEquals(listOf(PocketScreenEvent.ToggleConnection, PocketScreenEvent.StopPreview, PocketScreenEvent.Repaint), events)
    }

    @Test fun onlyAConnectedDesktopSimulatorOffersKeyInput() {
        val frame = FocusTimer().present(0)
        val grid = virtualGrid(1, defaultDeck(), frame, null)
        var sim by mutableStateOf(DesktopSimulator("Windows simulator"))
        var tab by mutableStateOf(WorkspaceTab.SETTINGS)
        val inputs = mutableListOf<PocketScreenEvent>()
        ui.setContent {
            val screen = PocketScreenState("", "Focus", frame.keys.map { it.toGridTile(true) },
                listOf(ProfileChoice("focus", "Focus")), "focus", simulator = sim)
            PocketTheme {
                PocketWorkspace(tab, { tab = it }, screen, emptyList(), {
                    when (it) {
                        PocketScreenEvent.EnterPreview -> sim = sim.copy(grid = grid)
                        PocketScreenEvent.StopPreview -> sim = sim.copy(grid = null)
                        else -> inputs += it
                    }
                }, {}, {})
            }
        }
        ui.onNodeWithText("Connect virtual keypad").performScrollTo().performClick()
        ui.onNodeWithText("Home").performClick()
        ui.onNodeWithContentDescription("Connect to device").assertDoesNotExist()
        ui.onNodeWithContentDescription("Key 1, Start, Focus").performScrollTo().performClick()
        ui.onNodeWithText("Settings").performClick()
        ui.onNodeWithText("Disconnect virtual keypad").performScrollTo().performClick()
        ui.onNodeWithText("Home").performClick()
        ui.onNodeWithContentDescription("Key 1, Start, Focus").assertHasNoClickAction()
        assertEquals(listOf(PocketScreenEvent.VirtualKey(0, grid)), inputs)
    }

    @Test fun resizingAcrossNavigationRailPreservesTheOpenEditorAndNeverRunsAKey() {
        val repo = DeckRepository(File(temp.root, "deck.json"))
        val store = repo.asEditorStore()
        var width by mutableStateOf(380.dp)
        var tab by mutableStateOf(WorkspaceTab.HOME)
        val events = mutableListOf<PocketScreenEvent>()
        val frame = FocusTimer().present(0)
        val grid = virtualGrid(1, repo.state.value, frame, null)
        val screen = PocketScreenState("Virtual keypad connected", "Focus", frame.keys.map { it.toGridTile(true) },
            listOf(ProfileChoice("focus", "Focus")), "focus", simulator = DesktopSimulator("Windows simulator", grid))
        ui.setContent {
            // The test surface is fixed in pixels; change layout constraints, not platform.
            CompositionLocalProvider(LocalDensity provides Density(.75f)) {
                Box(Modifier.requiredWidth(width).fillMaxHeight()) {
                    PocketTheme {
                        PocketWorkspace(tab, { tab = it }, screen, emptyList(), events::add,
                            profiles = { SimulatorProfiles(screen, { events += PocketScreenEvent.SelectPlugin(it) }) {
                                LayoutEditor(store, emptyList(), emptyMap(), {}, {})
                            } }, workflows = {})
                    }
                }
            }
        }
        fun assertFocusBoard() {
            screen.tiles.forEachIndexed { index, tile ->
                ui.onNodeWithContentDescription("Key ${index + 1}, ${tile.label}, ${tile.footer}").assertExists()
            }
        }
        assertFocusBoard()
        ui.runOnIdle { width = 900.dp }
        assertFocusBoard()
        ui.runOnIdle { width = 380.dp }
        ui.onNodeWithContentDescription("Key 1, Start, Focus").performClick()
        assertEquals(listOf(PocketScreenEvent.VirtualKey(0, grid)), events)
        events.clear()
        ui.onNodeWithText("Profiles").performClick()
        ui.onNodeWithText("Swap keys").performScrollTo().performClick()
        ui.onNodeWithContentDescription("Key 1,", substring = true).performClick()
        ui.onNodeWithTag("key-label").performTextReplacement("New label")
        ui.runOnIdle { width = 900.dp }
        ui.onNodeWithTag("wide-editor").assertExists()
        ui.onNodeWithText("Manage profile").assertExists()
        ui.onNodeWithTag("key-label").assertTextContains("New label")
        ui.runOnIdle { width = 380.dp }
        ui.onNodeWithTag("compact-editor").assertExists()
        ui.onNodeWithTag("key-label").assertTextContains("New label")
        ui.runOnIdle {
            assertTrue(events.isEmpty())
            assertEquals("", repo.state.value.activePage.keys.first().label)
        }
    }
}
