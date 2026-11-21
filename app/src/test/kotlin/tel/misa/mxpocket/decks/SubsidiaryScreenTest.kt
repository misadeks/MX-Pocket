package tel.misa.mxpocket.decks

import android.app.KeyguardManager
import android.content.Context
import android.content.Intent
import android.content.pm.ActivityInfo
import android.content.pm.ApplicationInfo
import android.content.pm.ResolveInfo
import android.net.Uri
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createEmptyComposeRule
import androidx.core.content.FileProvider
import androidx.test.core.app.ActivityScenario
import java.io.File
import org.junit.Assert.*
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.annotation.Implementation
import org.robolectric.annotation.Implements
import tel.misa.mxpocket.PhoneActionActivity
import tel.misa.mxpocket.PocketApplication
import tel.misa.mxpocket.R

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], qualifiers = "w400dp-h800dp")
class SubsidiaryScreenTest {
    @get:Rule val ui = createEmptyComposeRule()
    private val app get() = RuntimeEnvironment.getApplication() as PocketApplication

    @Before fun prepare() {
        shadowOf(app).grantPermissions("tel.misa.mxpocket.DYNAMIC_RECEIVER_NOT_EXPORTED_PERMISSION")
    }

    // FileProvider's Android canonical path matcher uses '/', but Robolectric files use the host OS.
    @Implements(value = FileProvider::class, isInAndroidSdk = false)
    class CaptureProvider {
        companion object {
            @JvmStatic @Implementation
            fun getUriForFile(context: Context, authority: String, file: File): Uri {
                require(authority == "${context.packageName}.captures")
                require(file.canonicalFile == File(context.filesDir, "captures/sample.jpg").canonicalFile)
                return Uri.parse("content://$authority/studio/sample.jpg")
            }
        }
    }

    @Test @Config(shadows = [CaptureProvider::class])
    fun capturePickerCancelsWithoutOptingInAndOnlyGrantsReadOnSelection() {
        val directory = File(app.filesDir, "captures").apply { mkdirs() }
        val photo = File(directory, "sample.jpg").apply { writeBytes(byteArrayOf(0)) }
        File(directory, "private.bin").writeBytes(byteArrayOf(0))
        ActivityScenario.launch(CameraControlsActivity::class.java).use { scenario ->
            val list = ui.onNodeWithText("Open previous Studio captures")
            list.performScrollTo().performClick()
            val capture = ui.onNodeWithText("JPG  ", substring = true)
            ui.waitUntil(5_000) { ui.onAllNodesWithText("JPG  ", substring = true).fetchSemanticsNodes().isNotEmpty() }
            ui.onNodeWithText("Cancel").performClick()
            capture.assertDoesNotExist()
            scenario.onActivity { assertNull(shadowOf(it).nextStartedActivity) }
            assertFalse(CameraControlsAccess(app).enabled)

            list.performScrollTo().performClick()
            ui.waitUntil(5_000) { ui.onAllNodesWithText("JPG  ", substring = true).fetchSemanticsNodes().isNotEmpty() }
            assertTrue(photo.delete())
            capture.performClick()
            ui.onNodeWithText("This capture is no longer available.").assertExists()
            scenario.onActivity { assertNull(shadowOf(it).nextStartedActivity) }

            photo.writeBytes(byteArrayOf(0))
            list.performScrollTo().performClick()
            ui.waitUntil(5_000) { ui.onAllNodesWithText("JPG  ", substring = true).fetchSemanticsNodes().isNotEmpty() }
            capture.performClick()
            scenario.onActivity {
                val viewer = requireNotNull(shadowOf(it).nextStartedActivity)
                assertEquals(Intent.ACTION_VIEW, viewer.action)
                assertEquals("image/jpeg", viewer.type)
                assertEquals("content://${app.packageName}.captures/studio/sample.jpg", viewer.dataString)
                assertEquals(Intent.FLAG_GRANT_READ_URI_PERMISSION,
                    viewer.flags and (Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION))
            }
        }
    }

    @Test fun cameraControlsRequireAnExplicitUnlockedOptIn() {
        ActivityScenario.launch(CameraControlsActivity::class.java).use {
            val keyguard = shadowOf(app.getSystemService(KeyguardManager::class.java))
            val enable = ui.onNodeWithText("Enable keypad camera controls")
            keyguard.setKeyguardLocked(true)
            enable.performScrollTo().performClick()
            assertFalse(CameraControlsAccess(app).enabled)
            keyguard.setKeyguardLocked(false)
            enable.performClick()
            assertTrue(CameraControlsAccess(app).enabled)
            ui.onNodeWithText("Turn off keypad camera controls").performScrollTo().performClick()
            assertFalse(CameraControlsAccess(app).enabled)
        }
    }

    @Test fun filteringFavoritesDoesNotChangeSelectionAndRestoresQueryOnRotation() {
        val pkg = "sample.player"
        val id = "$pkg/$pkg.Start"
        shadowOf(app.packageManager).addResolveInfoForIntent(
            Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER), ResolveInfo().apply {
                nonLocalizedLabel = "Test player"
                activityInfo = ActivityInfo().apply {
                    packageName = pkg; name = "$pkg.Start"; exported = true; enabled = true
                    applicationInfo = ApplicationInfo().apply { packageName = pkg }
                }
            })
        ActivityScenario.launch(FavoritesActivity::class.java).use { scenario ->
            ui.waitUntil(5_000) { ui.onAllNodesWithText("Test player").fetchSemanticsNodes().isNotEmpty() }
            ui.onNodeWithText("Test player").assertIsOff().performClick()
            assertTrue(id in app.session.favoriteApps.selected())
            ui.onNode(hasSetTextAction()).performTextReplacement("missing")
            ui.onNodeWithText("No matching apps.").assertExists()
            scenario.recreate()
            ui.waitUntil(5_000) { ui.onAllNodesWithText("No matching apps.").fetchSemanticsNodes().isNotEmpty() }
            ui.onNode(hasSetTextAction()).performTextClearance()
            ui.onNodeWithText("Test player").assertIsOn().performClick()
            assertFalse(id in app.session.favoriteApps.selected())
        }
    }

    @Test fun staleShortcutShowsDismissibleFailureWithoutStartingAnyTarget() {
        ActivityScenario.launch(PhoneActionActivity::class.java).use { scenario ->
            val message = ui.onNodeWithText(app.getString(R.string.phone_action_stale))
            message.assertExists()
            scenario.recreate()
            message.assertExists()
            scenario.onActivity { assertNull(shadowOf(it).nextStartedActivity) }
            ui.onNodeWithText("Done").performClick()
        }
    }
}
