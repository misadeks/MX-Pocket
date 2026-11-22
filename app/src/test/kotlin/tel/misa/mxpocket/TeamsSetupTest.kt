package tel.misa.mxpocket

import android.content.ComponentName
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

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class TeamsSetupTest {
    @get:Rule val ui = createAndroidComposeRule<TeamsAccessActivity>()

    @Test fun multipleProfilesCanOptIntoReadOnlyStatusAndManageOnlyTheTeamsListener() {
        val activity = ui.activity
        val access = TeamsAccess(activity)
        assertFalse(access.enabled)
        shadowOf(activity.getSystemService(UserManager::class.java)).addProfile(0, 10, "Work", ShadowUserManager.FLAG_MANAGED_PROFILE)
        ui.activityRule.scenario.recreate()
        ui.onNodeWithText(CallStatus.NO_KEYPAD.detail).assertExists()
        ui.onNodeWithText("With multiple Android profiles", substring = true).assertExists()
        ui.onNodeWithText("Enable Teams call status").performScrollTo().assertIsEnabled().performClick()
        assertTrue(access.enabled)
        assertFalse(access.granted)
        assertFalse(access.toolbarGranted)
        ui.onNodeWithText("Notification access: off").performScrollTo().performClick()
        val opened = shadowOf(ui.activity).nextStartedActivity
        assertEquals(Settings.ACTION_NOTIFICATION_LISTENER_DETAIL_SETTINGS, opened.action)
        assertEquals(ComponentName(activity, TeamsCallListener::class.java).flattenToString(),
            opened.getStringExtra(Settings.EXTRA_NOTIFICATION_LISTENER_COMPONENT_NAME))
    }
}
