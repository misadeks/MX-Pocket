package tel.misa.mxpocket

import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performTextReplacement
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performScrollToNode
import androidx.compose.ui.test.assertTextContains
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.hasClickAction
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
    @Test fun inactiveProfilePickerPreservesImportedCallsAndScopesBothGestures() {
        val repo = (compose.activity.application as PocketApplication).session.deck
        val custom = repo.createProfile("My desk").getOrThrow()
        val old = DeckKey(label = "Original", action = ActionCall("plugin.key", mapOf("pluginId" to "phone", "index" to "3")),
            holdAction = ActionCall("phone.open", mapOf("app" to "WEB")))
        repo.setKey(custom, "root", 0, old).getOrThrow()
        repo.importJson(repo.exportJson()).getOrThrow()
        val active = repo.state.value.activeProfileId
        fun clickChoice(text: String) {
            compose.onNodeWithTag("action-list").performScrollToNode(hasText(text))
            compose.onNodeWithText(text).performClick()
        }
        fun gesture(tag: String) = compose.onNodeWithTag(tag).performScrollTo().performClick()
        fun back() = compose.runOnIdle { compose.activity.onBackPressedDispatcher.onBackPressed() }
        fun open(profile: String, page: String) {
            compose.onNode(hasText(profile) and hasClickAction()).performScrollTo().performClick()
            compose.onNode(hasText(page) and hasText("Root page")).performScrollTo().performClick()
            compose.onNodeWithContentDescription("Key 1,", substring = true).performScrollTo().performClick()
        }
        compose.onNodeWithText("Profiles").performClick()
        // An inactive custom profile still owns its picker; browsing never changes the live keypad.
        val pageName = repo.state.value.profiles.single { it.id == custom }.pages.first().name
        open("My desk", pageName)
        for (tag in listOf("choose-binding", "choose-hold-binding"))
            compose.onNodeWithTag(tag).assertTextContains("Open logitech.com (legacy binding)", substring = true)
        compose.onNodeWithTag("key-label").performTextReplacement("Desk")
        compose.onNodeWithText("Save key").performScrollTo().performClick()
        fun saved() = repo.state.value.profiles.single { it.id == custom }.pages.first().keys.first()
        compose.waitUntil(5000) { saved() == old.copy(label = "Desk") }
        compose.onNodeWithContentDescription("Key 1, Desk").performClick()
        gesture("choose-binding")
        compose.onNodeWithText("Focus timer").assertDoesNotExist()
        clickChoice("Apps")
        clickChoice("Open dialer")
        gesture("choose-hold-binding")
        clickChoice("Media and volume")
        compose.onNodeWithText("Toggle mute").assertDoesNotExist()
        clickChoice("Volume +")
        compose.onNodeWithText("Save key").performScrollTo().performClick()
        compose.waitUntil(5000) { saved().action == ActionCall("phone.open", mapOf("app" to "DIALER")) &&
            saved().holdAction == ActionCall("media.volume-up") }
        compose.onNodeWithContentDescription("Key 1, Desk").performClick()
        compose.onNodeWithTag("choose-binding").assertTextContains("Tap: Open dialer")
        compose.onNodeWithTag("choose-hold-binding").assertTextContains("Hold: Volume +")
        back(); back(); back()
        open("Focus", "Focus")
        gesture("choose-hold-binding")
        clickChoice("Focus timer")
        compose.onNodeWithText("Start / pause").assertExists()
        back(); back(); back(); back(); back()
        open("Audio", "Audio")
        gesture("choose-binding")
        clickChoice("Media and volume")
        compose.onNodeWithText("Toggle mute").assertExists()
        assertEquals(active, repo.state.value.activeProfileId)
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
        compose.onNodeWithText("Pages and profiles").performClick()
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
