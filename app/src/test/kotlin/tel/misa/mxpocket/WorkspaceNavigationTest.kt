package tel.misa.mxpocket

import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onLast
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.performSemanticsAction
import android.content.Intent
import android.content.pm.ActivityInfo
import android.content.pm.ApplicationInfo
import android.content.pm.ResolveInfo
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import tel.misa.mxpocket.setup.SetupActivity
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import tel.misa.mxpocket.deck.DeckRule

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], qualifiers = "w400dp-h800dp")
class WorkspaceNavigationTest {
    @get:Rule val compose = createAndroidComposeRule<MainActivity>()

    @Suppress("DEPRECATION")
    @Test fun chooseAnInstalledAppForAPickedProfileWithoutEnablingDetectionOrEditingKeys() {
        val activity = compose.activity
        val session = (activity.application as PocketApplication).session
        val pkg = "example.camera"
        shadowOf(activity.application.packageManager).addResolveInfoForIntent(Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER),
            ResolveInfo().apply {
                activityInfo = ActivityInfo().apply {
                    packageName = pkg; name = "$pkg.Main"; exported = true; enabled = true
                    applicationInfo = ApplicationInfo().apply { packageName = pkg }
                }
                nonLocalizedLabel = "Test camera"
            })
        val before = session.deck.state.value.profiles.first { it.id == "studio" }
        compose.onNodeWithText("Profiles").performClick()
        compose.onNodeWithText("Focus / Focus ▾").performClick()
        // The carousel also shows a Studio card; the picker's entry comes after it.
        compose.onAllNodesWithText("Studio").onLast().performClick()
        // The picker and the carousel agree: the chosen profile goes on the keypad.
        compose.waitUntil(10_000) { session.deck.state.value.activeProfileId == "studio" }
        compose.onNodeWithText("Profile settings and rules").performScrollTo().performClick()
        compose.onNodeWithText("Choose app").performScrollTo().performClick()
        compose.onNodeWithText("When this app is open, show Studio").performScrollTo()
        compose.waitUntil(10_000) { compose.onAllNodes(hasText("Test camera")).fetchSemanticsNodes().isNotEmpty() }
        compose.onNodeWithText("Test camera").assertIsEnabled().performSemanticsAction(SemanticsActions.OnClick)
        compose.waitUntil(10_000) { session.deck.state.value.profiles.first { it.id == "studio" }.rules.isNotEmpty() }
        assertEquals(before.copy(rules = listOf(DeckRule(FOREGROUND_APP, pkg, 5))),
            session.deck.state.value.profiles.first { it.id == "studio" })
        assertEquals("studio", session.deck.state.value.activeProfileId)
        assertFalse(session.deck.state.value.automatic)
        assertFalse(session.detectForegroundApps.value)
        assertFalse(FOREGROUND_APP in session.signals.value)
    }

    @org.robolectric.annotation.GraphicsMode(org.robolectric.annotation.GraphicsMode.Mode.NATIVE)
    @Test fun openTheLayoutEditorWorkflowLibraryAndConnectionSettingsFromHome() {
        fun capture(name: String) = compose.runOnIdle {
            val view = compose.activity.window.decorView
            val bitmap = android.graphics.Bitmap.createBitmap(view.width, view.height, android.graphics.Bitmap.Config.ARGB_8888)
            view.draw(android.graphics.Canvas(bitmap))
            java.io.File("build/reports/workspace-preview/android-$name.png").apply {
                parentFile.mkdirs()
                outputStream().use { bitmap.compress(android.graphics.Bitmap.CompressFormat.PNG, 100, it) }
            }
        }
        capture("home")
        compose.onNodeWithText("Profiles").performClick()
        compose.onNodeWithText("Swap keys").assertExists()
        compose.onNodeWithText("Key positions").assertDoesNotExist()
        capture("profiles")
        compose.onNodeWithText("Flows").performClick()
        compose.onNodeWithText("Keypad check").assertExists()
        capture("flows")
        compose.onNodeWithText("New workflow").performClick()
        compose.onNodeWithText("Workflow name").assertExists()
        compose.onNodeWithText("Plugins").performClick()
        compose.onNodeWithText("Edit favorites").assertExists()
        capture("plugins")
        compose.onNodeWithText("Settings").performClick()
        compose.onNodeWithText("Connect automatically on USB attach").assertExists()
        capture("settings")
        compose.onNodeWithText("Feature setup").performClick()
        assertEquals(SetupActivity::class.java.name, shadowOf(compose.activity).nextStartedActivity.component?.className)
    }
}
