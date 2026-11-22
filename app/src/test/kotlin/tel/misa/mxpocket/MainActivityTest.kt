package tel.misa.mxpocket

import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createEmptyComposeRule
import android.content.Intent
import android.os.Bundle
import androidx.test.core.app.ActivityScenario
import androidx.lifecycle.Lifecycle
import org.junit.Assert.*
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import tel.misa.mxpocket.shared.ui.PocketScreenEvent
import tel.misa.mxpocket.shared.ui.virtualGrid
import tel.misa.mxpocket.ui.ThemePreference
import tel.misa.mxpocket.ui.LanguagePreference
import tel.misa.mxpocket.i18n.AppLanguage
import tel.misa.mxpocket.shared.ui.ThemeMode

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
            ui.onNodeWithContentDescription("Connect to device").performClick()
            ui.onNodeWithText("No MX Creative Keypad", substring = true).assertExists()
            ui.onNodeWithText("Not connected").assertExists()
        }
    }
    @Test fun disconnectedHomeCanBrowseLayoutsButNotRunKeys() {
        ActivityScenario.launch(MainActivity::class.java).use {
            ui.onNodeWithText("Connect virtual keypad").assertDoesNotExist()
            ui.onNodeWithText("Settings").performClick()
            ui.onNodeWithText("Connect keypad").assertExists()
            ui.onNodeWithText("Connect virtual keypad").assertDoesNotExist()
            ui.onNodeWithText("Disconnect virtual keypad").assertDoesNotExist()
            ui.onNodeWithText("Home").performClick()
            ui.onNodeWithText("Focus").performClick()
            ui.onNodeWithContentDescription("Key 1, Start", substring = true).assertHasNoClickAction()
            ui.onNodeWithContentDescription("Next page").performScrollTo().performClick()
            assertEquals("audio", app.session.deck.state.value.activeProfileId)
            ui.onNodeWithContentDescription("Play/Pause").assertExists()
            ui.onNodeWithContentDescription("Next page").performScrollTo().performClick()
            ui.onNodeWithContentDescription("Key 1, Camera", substring = true).assertHasNoClickAction()
            assertFalse(app.session.ready)
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

    @Test fun homeConnectionDoesNotChangeTheSavedAppearance() {
        ThemePreference.set(app, ThemeMode.DARK)
        ActivityScenario.launch(MainActivity::class.java).use { scene ->
            ui.onNodeWithContentDescription("Connect to device").performClick()
            ui.onNodeWithText("Settings").performClick()
            ui.onNodeWithText("Dark").assertIsSelected()
            ui.onNodeWithText("System").performScrollTo().performClick()
            scene.recreate()
            ui.onNodeWithText("System").assertIsSelected()
            assertEquals("SYSTEM", app.getSharedPreferences("appearance", 0).getString("theme", null))
        }
    }

    @Test fun languageSelectorChangesNavigationAndMissingDeviceMessageAcrossRecreation() {
        try {
            ActivityScenario.launch(MainActivity::class.java).use { scene ->
                ui.onNodeWithText("Settings").performClick()
                ui.onNodeWithText("Language").performScrollTo().performClick()
                ui.onNodeWithText("Français").performClick()
                scene.recreate()
                ui.onNodeWithText("Accueil").performClick()
                ui.onNodeWithContentDescription("Connecter l’appareil").performClick()
                ui.onNodeWithText("Vérifiez le câble", substring = true).assertExists()
                assertEquals(AppLanguage.FRENCH, LanguagePreference.choice(app))
                assertNull(app.session.request)
            }
        } finally { LanguagePreference.set(app, null) }
    }

    @Test fun staleLaunchAndRestoredAttemptCannotConnectOrDispatchDesktopInput() {
        val intent = Intent(app, MainActivity::class.java).setAction("PREVIEW").putExtra("preview", true)
        val stale = Bundle().apply {
            putString("tab", "PREVIEW")
            putString("target", "absent-keypad")
            putBoolean("notificationPrompt", true)
        }
        Robolectric.buildActivity(MainActivity::class.java, intent).create(stale).start().resume().visible().use {
            val session = app.session
            it.newIntent(intent)
            it.get().handleScreenEvent(PocketScreenEvent.EnterPreview)
            it.get().handleScreenEvent(PocketScreenEvent.VirtualKey(0, virtualGrid(0, session.deck.state.value, session.frame(), null)))
            ui.onNodeWithContentDescription("Connect to device").assertExists()
            ui.onNodeWithContentDescription("Key 1, Focus", substring = true).assertHasNoClickAction()
            assertFalse(session.ready)
            assertNull(session.request)
            assertNull(shadowOf(app).nextStartedService)
        }
    }
}
