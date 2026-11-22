package tel.misa.mxpocket

import android.os.UserHandle
import android.os.UserManager
import android.provider.Settings
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.shadows.ShadowUserManager
import tel.misa.mxpocket.decks.*
import tel.misa.mxpocket.i18n.AccessCopy
import tel.misa.mxpocket.setup.SetupAccess
import tel.misa.mxpocket.setup.SetupChoices
import tel.misa.mxpocket.shared.ui.SetupFeature

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class TeamsSetupTest {
    @get:Rule val ui = createAndroidComposeRule<TeamsAccessActivity>()

    @Test fun callStatusDefaultsOffAndRemembersEitherChoiceWithoutGrantingAndroidAccess() {
        val activity = ui.activity
        val access = TeamsAccess(activity)
        assertFalse(access.enabled)
        val users = activity.getSystemService(UserManager::class.java)
        shadowOf(users).addProfile(0, 10, "Work", ShadowUserManager.FLAG_MANAGED_PROFILE)
        ui.activityRule.scenario.recreate()
        ui.onNodeWithText(CallStatus.NO_KEYPAD.detail).assertExists()
        val warning = AccessCopy.TeamsProfileNotice.text()
        ui.onNodeWithText(warning).assertExists()
        val wizard = SetupAccess(ui.activity, SetupChoices(ui.activity))
        val controls = SetupFeature.TEAMS_CONTROLS
        assertEquals(warning, wizard.options().single { it.feature == controls }.unavailableReason)
        assertFalse(wizard.steps(setOf(controls)).any { it.feature == controls })
        assertTrue(users.requestQuietModeEnabled(true, UserHandle.getUserHandleForUid(1_010_000)))
        ui.onNodeWithText(warning).assertDoesNotExist()
        assertNull(wizard.options().single { it.feature == controls }.unavailableReason)
        assertTrue(wizard.steps(setOf(controls)).any { it.feature == controls })
        ui.onNodeWithText("Enable Teams call status").performScrollTo().assertIsEnabled().performClick()
        ui.activityRule.scenario.recreate()
        assertTrue(TeamsAccess(ui.activity).enabled)
        ui.onNodeWithText("Turn off Teams call status").performScrollTo().performClick()
        ui.activityRule.scenario.recreate()
        assertFalse(TeamsAccess(ui.activity).enabled)
        assertFalse(access.granted)
        assertFalse(access.toolbarGranted)
        ui.onNodeWithText("Notification access: off").performScrollTo().performClick()
        val opened = shadowOf(ui.activity).nextStartedActivity
        assertEquals(Settings.ACTION_NOTIFICATION_LISTENER_DETAIL_SETTINGS, opened.action)
        assertEquals(NotificationAccess(activity).component.flattenToString(),
            opened.getStringExtra(Settings.EXTRA_NOTIFICATION_LISTENER_COMPONENT_NAME))
    }
}
