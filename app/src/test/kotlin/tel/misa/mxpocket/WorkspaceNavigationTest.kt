package tel.misa.mxpocket

import androidx.compose.ui.test.assertExists
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], qualifiers = "w400dp-h800dp")
class WorkspaceNavigationTest {
    @get:Rule val compose = createAndroidComposeRule<MainActivity>()

    @Test fun openTheLayoutEditorWorkflowLibraryAndConnectionSettingsFromHome() {
        compose.onNodeWithText("Keypad").performClick()
        compose.onNodeWithText("Key positions").assertExists()
        compose.onNodeWithText("Flows").performClick()
        compose.onNodeWithText("Keypad check").assertExists()
        compose.onNodeWithText("New workflow").performClick()
        compose.onNodeWithText("Workflow name").assertExists()
        compose.onNodeWithText("Plugins").performClick()
        compose.onNodeWithText("Edit favorites").assertExists()
        compose.onNodeWithText("Settings").performClick()
        compose.onNodeWithText("Connect automatically on USB attach").assertExists()
    }
}
