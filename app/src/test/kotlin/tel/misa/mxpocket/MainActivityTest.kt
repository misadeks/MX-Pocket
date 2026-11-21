package tel.misa.mxpocket

import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createEmptyComposeRule
import androidx.test.core.app.ActivityScenario
import androidx.lifecycle.Lifecycle
import org.junit.Assert.assertNull
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28, 35], qualifiers = "w400dp-h800dp")
class MainActivityTest {
    @get:Rule val ui = createEmptyComposeRule()
    private val app get() = RuntimeEnvironment.getApplication() as PocketApplication
    @Before fun grantAppOwnedBroadcastPermission() {
        shadowOf(app).grantPermissions("tel.misa.mxpocket.DYNAMIC_RECEIVER_NOT_EXPORTED_PERMISSION")
    }
    @Test fun startsWithoutUsbDeviceAndConnectReportsMissingHardware() {
        ActivityScenario.launch(MainActivity::class.java).use {
            ui.onNodeWithText("Connect keypad").performClick()
            ui.onNodeWithText("No MX Creative Keypad", substring = true).assertExists()
        }
    }
    @Test fun previewRunsTimerAndChangesPageWithoutClaimingUsbSuccess() {
        ActivityScenario.launch(MainActivity::class.java).use {
            ui.onNodeWithText("Connect virtual keypad").performClick()
            ui.onNodeWithContentDescription("Key 1, Start", substring = true).performClick()
            ui.onNodeWithContentDescription("Key 1, Pause", substring = true).assertExists()
            ui.onNodeWithText("Next page").performClick()
            ui.onNodeWithContentDescription("Play/Pause", substring = true).assertExists()
            ui.onNodeWithText("Next page").performClick()
            ui.onNodeWithContentDescription("Key 1, Camera", substring = true).assertExists()
            ui.onNodeWithText("No USB messages are sent", substring = true).assertExists()
            ui.onNodeWithText("Nine LCD image uploads acknowledged", substring = true).assertDoesNotExist()
        }
    }
    @Test fun backgroundLifecycleNeverStartsServiceForAnIdleScreen() {
        ActivityScenario.launch(MainActivity::class.java).use {
            it.moveToState(Lifecycle.State.CREATED)
            it.moveToState(Lifecycle.State.RESUMED)
            ui.onNodeWithText(app.getString(R.string.not_connected)).assertExists()
            assertNull(shadowOf(app).nextStartedService)
        }
    }
}
