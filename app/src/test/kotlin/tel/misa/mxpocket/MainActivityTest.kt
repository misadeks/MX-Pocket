package tel.misa.mxpocket

import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createEmptyComposeRule
import androidx.compose.ui.semantics.SemanticsActions
import androidx.test.core.app.ActivityScenario
import androidx.lifecycle.Lifecycle
import org.junit.Assert.assertNull
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import tel.misa.mxpocket.actions.ActionCall
import tel.misa.mxpocket.deck.DeckActions
import tel.misa.mxpocket.shared.ui.PocketScreenEvent
import tel.misa.mxpocket.shared.ui.virtualGrid

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
            assertEquals("tel.misa.mxpocket", app.packageName)
            ui.onNodeWithText("Connect keypad").performClick()
            ui.onNodeWithText("No MX Creative Keypad", substring = true).assertExists()
        }
    }
    @Test fun previewRunsTimerAndChangesPageWithoutClaimingUsbSuccess() {
        ActivityScenario.launch(MainActivity::class.java).use {
            ui.onNodeWithText("Focus").performClick()
            ui.onNodeWithText("Connect virtual keypad").performClick()
            ui.onNodeWithContentDescription("Key 1, Start", substring = true).performClick()
            ui.onNodeWithContentDescription("Key 1, Pause", substring = true).assertExists()
            ui.onNodeWithText("Next page").performScrollTo().performClick()
            assertEquals("audio", app.session.deck.state.value.activeProfileId)
            ui.onNodeWithContentDescription("Play/Pause").assertExists()
            ui.onNodeWithText("Next page").performScrollTo().performClick()
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

    @Test fun holdOnlyKeyRequiresTheSameLiveVirtualBoard() {
        ActivityScenario.launch(MainActivity::class.java).use { scene ->
            val session = app.session
            val deck = session.deck
            val key = deck.state.value.activePage.keys.first().copy(label = "Open Audio", action = null,
                holdAction = ActionCall(DeckActions.PROFILE, mapOf(DeckActions.PROFILE_ID to "audio")))
            scene.onActivity { deck.setKey("focus", "root", 0, key).getOrThrow() }
            ui.onNodeWithText("Focus").performClick()
            ui.onNodeWithText("Connect virtual keypad").performClick()
            val slot = ui.onNodeWithContentDescription("Key 1, Open Audio", substring = true)
            slot.performClick()
            assertEquals("focus", deck.state.value.activeProfileId)
            scene.onActivity { activity ->
                val stale = PocketScreenEvent.VirtualKey(0, virtualGrid(session.inputGeneration, deck.state.value,
                    session.frame(), session.frame(hold = true)), hold = true)
                session.disconnect()
                activity.handleScreenEvent(stale)
                session.startPreview()
                activity.handleScreenEvent(stale)
                assertEquals("focus", deck.state.value.activeProfileId)
            }
            slot.performSemanticsAction(SemanticsActions.OnLongClick)
            assertEquals("audio", deck.state.value.activeProfileId)
        }
    }
}
