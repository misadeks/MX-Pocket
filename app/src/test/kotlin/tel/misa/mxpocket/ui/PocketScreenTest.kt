package tel.misa.mxpocket.ui

import android.content.Intent
import android.media.AudioManager
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.semantics.SemanticsActions
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
import tel.misa.mxpocket.shared.ui.*

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], qualifiers = "w400dp-h800dp")
class PocketScreenTest {
    @get:Rule val ui = createComposeRule()
    private val app get() = RuntimeEnvironment.getApplication()

    @OptIn(ExperimentalTestApi::class)
    @Test fun redrawDoesNotExecuteAndEachInputModalityEmitsExactlyOneTap() {
        val scenes = UiPreviewScenes(app)
        val state = mutableStateOf(scenes.state("focus"))
        val events = mutableListOf<PocketScreenEvent>()
        ui.setContent { PocketTheme { HomeScreen(state.value, events::add) } }
        ui.runOnIdle { state.value = scenes.state("audio") }
        val key = ui.onNodeWithContentDescription("Key 1, Volume -", substring = true)
        key.performScrollTo()
        key.performTouchInput { down(center); cancel() }
        ui.runOnIdle { assertTrue(events.isEmpty()) }
        key.performTouchInput { click() }
        key.performClick()
        key.performSemanticsAction(SemanticsActions.RequestFocus)
        key.performKeyInput { pressKey(Key.Enter) }
        ui.runOnIdle { assertEquals(List(3) { PocketScreenEvent.TapKey(0) }, events) }
    }

    @Test fun debugPreviewChangesSceneWithoutRunningPlatformActions() {
        ActivityScenario.launch<UiPreviewActivity>(Intent(app, UiPreviewActivity::class.java).putExtra("scene", "audio")).use { scene ->
            val volume = app.getSystemService(AudioManager::class.java)
            val before = volume.getStreamVolume(AudioManager.STREAM_MUSIC)
            ui.onNodeWithContentDescription("Key 1, Volume -", substring = true).performClick()
            assertEquals(before, volume.getStreamVolume(AudioManager.STREAM_MUSIC))
            ui.onNodeWithText("Phone").performClick()
            ui.onNodeWithContentDescription("Key 1, Camera", substring = true).performClick()
            scene.onActivity { assertNull(shadowOf(it).nextStartedActivity) }
            ui.onNodeWithText("No action executed.", substring = true).assertExists()
            scene.recreate()
            ui.onNodeWithContentDescription("Key 1, Camera", substring = true).assertExists()
        }
    }

    @Test fun previewFixturesAreRepeatableAndRejectUnknownScenes() {
        val scenes = UiPreviewScenes(app)
        for (id in scenes.ids) {
            assertEquals(scenes.state(id), scenes.state(id))
            assertEquals(9, scenes.state(id).tiles.size)
        }
        assertThrows(IllegalArgumentException::class.java) { scenes.state("unregistered") }
    }
}
