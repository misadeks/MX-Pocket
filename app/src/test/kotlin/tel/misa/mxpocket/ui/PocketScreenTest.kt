package tel.misa.mxpocket.ui

import android.content.Intent
import android.media.AudioManager
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.test.core.app.ActivityScenario
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import tel.misa.mxpocket.actions.ActionCall
import tel.misa.mxpocket.deck.defaultDeck
import tel.misa.mxpocket.shared.ui.*

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], qualifiers = "w400dp-h800dp")
class PocketScreenTest {
    @get:Rule val ui = createComposeRule()
    private val app get() = RuntimeEnvironment.getApplication()

    @Config(sdk = [28, 35])
    @Test fun redrawPreservesDiagnosticSelectionAndAnnouncesStatusPolitely() {
        val scenes = UiPreviewScenes(app)
        val state = mutableStateOf(scenes.state("disconnected"))
        val tab = mutableStateOf(WorkspaceTab.HOME)
        val events = mutableListOf<PocketScreenEvent>()
        ui.setContent { PocketTheme { PocketWorkspace(tab.value, { tab.value = it }, state.value,
            emptyList(), events::add, editor = {}, workflows = {}) } }
        ui.onNodeWithText(state.value.status).assert(SemanticsMatcher.expectValue(SemanticsProperties.LiveRegion, LiveRegionMode.Polite))
        ui.onNodeWithText("Settings").performClick()
        val readout = ui.onNodeWithText(state.value.diagnostics)
        readout.performScrollTo().performSemanticsAction(SemanticsActions.SetSelection) { it(2, 5, false) }
        ui.runOnIdle { state.value = scenes.state("audio").copy(connectionActive = true) }
        readout.assert(SemanticsMatcher.expectValue(SemanticsProperties.TextSelectionRange, TextRange(2, 5)))
        ui.runOnIdle { assertTrue(events.isEmpty()) }
    }

    @OptIn(ExperimentalTestApi::class)
    @Test fun redrawDoesNotExecuteAndEachInputModalityEmitsExactlyOneTap() {
        val scenes = UiPreviewScenes(app)
        val state = mutableStateOf(scenes.state("focus"))
        val events = mutableListOf<PocketScreenEvent>()
        ui.setContent { PocketTheme { HomeScreen(state.value, events::add) } }
        ui.runOnIdle { state.value = scenes.state("audio") }
        val key = ui.onNodeWithContentDescription("Volume -")
        key.performScrollTo()
        key.performTouchInput { down(center); cancel() }
        ui.runOnIdle { assertTrue(events.isEmpty()) }
        key.performTouchInput { click() }
        key.performClick()
        key.performSemanticsAction(SemanticsActions.RequestFocus)
        key.performKeyInput { pressKey(Key.Enter) }
        ui.runOnIdle { assertEquals(List(3) { PocketScreenEvent.VirtualKey(0) }, events) }
    }

    @Test fun debugPreviewChangesSceneWithoutRunningPlatformActions() {
        ActivityScenario.launch<UiPreviewActivity>(Intent(app, UiPreviewActivity::class.java).putExtra("scene", "audio")).use { scene ->
            val volume = app.getSystemService(AudioManager::class.java)
            val before = volume.getStreamVolume(AudioManager.STREAM_MUSIC)
            ui.onNodeWithContentDescription("Volume -").performClick()
            assertEquals(before, volume.getStreamVolume(AudioManager.STREAM_MUSIC))
            // Profiles are switched on the Profiles tab; Home's page buttons move to the next fixture.
            ui.onNodeWithContentDescription("Next page").performClick()
            ui.onNodeWithContentDescription("Key 1, Camera", substring = true).performClick()
            scene.onActivity { assertNull(shadowOf(it).nextStartedActivity) }
            ui.onNodeWithText("No action executed.", substring = true).assertExists()
            scene.recreate()
            ui.onNodeWithContentDescription("Key 1, Camera", substring = true).assertExists()
        }
    }

    @Test fun operationalHoldConsumesReleaseAndAReboundKeyCancelsThePress() {
        val focus = UiPreviewScenes(app).state("focus")
        val page = defaultDeck().activePage
        val keys = page.keys.toMutableList().apply { this[0] = first().copy(holdAction = ActionCall("page.root")) }
        val targets = List(9) { VirtualGrid.Target(null, actionable = true) }
        val grid = VirtualGrid(1, "focus", page.copy(keys = keys), 0, targets, targets)
        val state = mutableStateOf(focus.copy(simulating = true, virtualGrid = grid))
        val events = mutableListOf<PocketScreenEvent>()
        ui.setContent { PocketTheme { HomeScreen(state.value, events::add) } }
        val key = ui.onNodeWithContentDescription("Key 1, Start", substring = true).performScrollTo()
        key.performTouchInput { click() }
        key.performTouchInput { longClick() }
        key.performSemanticsAction(SemanticsActions.OnLongClick)
        key.performTouchInput { down(center); cancel() }
        ui.runOnIdle {
            assertEquals(listOf(PocketScreenEvent.VirtualKey(0, grid), PocketScreenEvent.VirtualKey(0, grid, true),
                PocketScreenEvent.VirtualKey(0, grid, true)), events)
            events.clear()
        }

        // Same visible key, new action. Neither the earlier Tap nor the replacement Hold may run.
        key.performTouchInput { down(center) }
        val replacements = keys.toMutableList().apply { this[0] = first().copy(holdAction = ActionCall("page.back")) }
        val rebound = grid.copy(page = page.copy(keys = replacements))
        ui.runOnIdle { state.value = state.value.copy(virtualGrid = rebound) }
        key.performTouchInput { up() }
        ui.runOnIdle { assertTrue(events.isEmpty()) }
        key.performClick()
        ui.runOnIdle { assertEquals(listOf(PocketScreenEvent.VirtualKey(0, rebound)), events) }
    }

    @Test fun previewFixturesAreRepeatableAndRejectUnknownScenes() {
        val scenes = UiPreviewScenes(app)
        for (id in scenes.ids) {
            assertEquals(scenes.state(id), scenes.state(id))
            assertEquals(9, scenes.state(id).tiles.size)
        }

        assertThrows(IllegalArgumentException::class.java) { scenes.state("unregistered") }
    }

    @GraphicsMode(GraphicsMode.Mode.NATIVE)
    @Test fun liveMirrorRestoresActionsAfterReadOnlyAudioWithOrWithoutKeyImages() {
        val scenes = UiPreviewScenes(app)
        val events = mutableListOf<PocketScreenEvent>()
        val image = androidx.compose.ui.graphics.ImageBitmap(118, 118)
        val state = mutableStateOf(scenes.state("focus"))
        ui.setContent { PocketTheme { HomeScreen(state.value, events::add) } }
        for (scene in listOf("audio", "focus")) for (art in listOf(null, image, null)) {
            val frame = scenes.state(scene)
            ui.runOnIdle {
                events.clear()
                state.value = frame.copy(tiles = frame.tiles.map { it.copy(image = art) })
            }
            frame.tiles.forEachIndexed { i, tile ->
                val node = ui.onNodeWithContentDescription(tile.accessibleName ?:
                    listOf("Key ${i + 1}", tile.label, tile.footer).filter(String::isNotBlank).joinToString(", "))
                when {
                    tile.readOnly -> node.assertHasNoClickAction()
                    !tile.enabled -> node.assertIsNotEnabled()
                    else -> node.performClick()
                }
            }
            if (scene == "audio") {
                ui.onNodeWithContentDescription("Mute, Sound on, 6 / 15").assertExists()
                ui.onNodeWithContentDescription("Now playing, Night train, Paused").assertHasNoClickAction()
            }
            val actionable = if (scene == "audio") (0..5).toList() else listOf(0, 2, 3, 5, 6, 7, 8)
            assertEquals(actionable.map { PocketScreenEvent.VirtualKey(it) }, events)
        }
    }
}
