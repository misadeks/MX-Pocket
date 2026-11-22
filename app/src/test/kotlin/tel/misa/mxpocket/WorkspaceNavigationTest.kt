package tel.misa.mxpocket

import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performTextReplacement
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
import tel.misa.mxpocket.actions.ActionCall
import tel.misa.mxpocket.deck.DeckKey
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
        compose.onNodeWithText("Studio").performScrollTo().performClick()
        compose.onNodeWithText("Profile settings and rules").performScrollTo().performClick()
        compose.onNodeWithText("Choose app").performScrollTo().performClick()
        compose.onNodeWithText("When this app is open, show Studio").performScrollTo()
        compose.waitUntil(10_000) { compose.onAllNodes(hasText("Test camera")).fetchSemanticsNodes().isNotEmpty() }
        compose.onNodeWithText("Test camera").assertIsEnabled().performSemanticsAction(SemanticsActions.OnClick)
        compose.waitUntil(10_000) { session.deck.state.value.profiles.first { it.id == "studio" }.rules.isNotEmpty() }
        assertEquals(before.copy(rules = listOf(DeckRule(FOREGROUND_APP, pkg, 5))),
            session.deck.state.value.profiles.first { it.id == "studio" })
        assertEquals("launcher", session.deck.state.value.activeProfileId)
        assertFalse(session.deck.state.value.automatic)
        assertFalse(session.detectForegroundApps.value)
        assertFalse(FOREGROUND_APP in session.signals.value)
    }

    @org.robolectric.annotation.GraphicsMode(org.robolectric.annotation.GraphicsMode.Mode.NATIVE)
    @Test fun offlineEditStaysOnTheSavedPageAndBackRetracesTheLibrary() {
        val session = (compose.activity.application as PocketApplication).session
        val repo = session.deck
        val work = repo.createProfile("Work").getOrThrow()
        val meetings = repo.createPage(work, "Meetings").getOrThrow()
        val original = DeckKey(label = "Previous", action = ActionCall("page.back"),
            holdAction = ActionCall("workflow.run", mapOf("workflowId" to "custom")))
        repo.setKey(work, meetings, 3, original).getOrThrow()
        compose.runOnIdle { session.setPluginEnabled("studio", false); session.selectProfile("audio") }
        val before = repo.state.value
        fun back() = compose.runOnIdle { compose.activity.onBackPressedDispatcher.onBackPressed() }
        compose.onNodeWithText("Profiles").performClick()
        compose.onNodeWithText("Studio").assertDoesNotExist()
        compose.onNode(hasText("Work") and hasText("2 pages")).performScrollTo().performClick()
        compose.onNodeWithText("Meetings").performClick()
        compose.onNodeWithContentDescription("Key 4, Previous").performScrollTo().performClick()
        assertEquals(before, repo.state.value)
        compose.onNodeWithTag("choose-binding").performScrollTo().performClick()
        compose.onNodeWithText("Open page: Meetings").performClick()
        // A live switch while editing must not retarget the open key or discard its draft.
        compose.onNodeWithTag("key-label").performTextReplacement("Return")
        compose.runOnIdle { session.selectProfile("phone") }
        compose.onNodeWithText("Save key").performScrollTo().performClick()
        compose.waitUntil(5000) {
            repo.state.value.profiles.single { it.id == work }.pages.single { it.id == meetings }.keys[3] ==
                original.copy(label = "Return", action = ActionCall("page.open", mapOf("pageId" to meetings)))
        }
        compose.onNodeWithContentDescription("Key 4, Return").performClick()
        back()
        compose.onNodeWithContentDescription("Key 4, Return").assertExists()
        back()
        compose.onNodeWithText("Pages").assertExists()
        back()
        compose.onNode(hasText("Work") and hasText("2 pages")).assertExists()
        assertEquals("phone", repo.state.value.activeProfileId)
        assertEquals(before.histories, repo.state.value.histories)
        assertFalse(session.ready)
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
        compose.onNodeWithText("New profile").assertExists()
        compose.onNodeWithText("Swipe to switch").assertDoesNotExist()
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
