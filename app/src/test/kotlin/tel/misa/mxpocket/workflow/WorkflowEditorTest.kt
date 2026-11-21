package tel.misa.mxpocket.workflow

import androidx.compose.material3.MaterialTheme
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], qualifiers = "w400dp-h800dp")
class WorkflowEditorTest {
    @get:Rule val compose = createComposeRule()

    @Test fun buildConfigureSaveRunAndReloadFromTheVisibleEditor() {
        val app = RuntimeEnvironment.getApplication()
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
        val calls = mutableListOf<String>()
        val host = object : WorkflowHost {
            override fun snapshot() = WorkflowContext()
            override fun actions() = listOf(WorkflowActionSpec("test.mark", "Mark", listOf(WorkflowField("tag", "Marker"))))
            override suspend fun execute(id: String, params: Map<String, String>): Boolean {
                calls += params.getValue("tag")
                return true
            }
        }
        val controller = WorkflowController(app, scope, host)
        try {
            compose.setContent { MaterialTheme { WorkflowScreen(controller) } }
            compose.onNodeWithText("New workflow").performClick()
            compose.onNodeWithText("Workflow name").performTextReplacement("Sound check")
            compose.onNodeWithText("Add step").performScrollTo().performClick()
            compose.onNodeWithText("Keypad or plugin action").performClick()
            compose.onNodeWithText("Marker").performScrollTo().performTextInput("ready")
            compose.onNodeWithText("Save workflow").performScrollTo().performClick()
            compose.waitUntil(10_000) { controller.definitions.value.any { it.name == "Sound check" } }
            compose.onAllNodesWithText("Run").onLast().performScrollTo().performClick()
            compose.waitUntil(10_000) { controller.history.value.isNotEmpty() }
            compose.onNodeWithText("Completed").assertExists()
            assertEquals(listOf("ready"), calls)
            val stored = WorkflowStore(app).load().definitions.single { it.name == "Sound check" }
            assertEquals("ready", stored.steps.single().params["tag"])
            compose.onNodeWithText("Run history").performScrollTo().performClick()
            compose.onNodeWithText("Sound check").assertExists()
        } finally { scope.cancel() }
    }
}
