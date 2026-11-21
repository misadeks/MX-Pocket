package tel.misa.mxpocket.setup

import android.Manifest
import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.ComponentName
import android.content.Intent
import android.content.pm.PackageManager
import android.hardware.usb.UsbConfiguration
import android.hardware.usb.UsbDevice
import android.hardware.usb.UsbManager
import android.os.Build
import android.os.Looper
import android.provider.Settings
import androidx.compose.ui.test.assertIsOn
import androidx.compose.ui.test.junit4.createEmptyComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.test.core.app.ActivityScenario
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
import org.robolectric.shadows.ShadowSettings
import org.robolectric.util.ReflectionHelpers
import tel.misa.mxpocket.DirectShortcuts
import tel.misa.mxpocket.KeypadService
import tel.misa.mxpocket.KeypadSessionTest
import tel.misa.mxpocket.MainActivity
import tel.misa.mxpocket.PocketApplication
import tel.misa.mxpocket.UsbAccess
import tel.misa.mxpocket.decks.MediaSessionAccess

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], qualifiers = "w400dp-h800dp")
class FeatureSetupTest {
    @get:Rule val ui = createEmptyComposeRule()
    private val app get() = RuntimeEnvironment.getApplication() as PocketApplication
    private val notices get() = app.getSystemService(NotificationManager::class.java)
    private val launcher get() = Intent(app, MainActivity::class.java).setAction(Intent.ACTION_MAIN)
        .addCategory(Intent.CATEGORY_LAUNCHER)

    @Before fun prepare() {
        shadowOf(app).grantPermissions("tel.misa.mxpocket.DYNAMIC_RECEIVER_NOT_EXPORTED_PERMISSION")
        shadowOf(app.packageManager).setSystemFeature(PackageManager.FEATURE_CAMERA_ANY, true)
    }

    private fun tap(label: String) = ui.onNodeWithText(label).performScrollTo().performClick()

    @Test fun skipReturnsToWorkingVirtualKeypadWithoutPromptsOrService() {
        Robolectric.buildActivity(MainActivity::class.java, launcher).setup().use { main ->
            val host = shadowOf(main.get())
            val welcome = host.nextStartedActivityForResult
            assertEquals(SetupActivity::class.java.name, welcome.intent.component?.className)
            main.pause().stop()
            var result = android.app.Activity.RESULT_CANCELED
            ActivityScenario.launch<SetupActivity>(welcome.intent).use { wizard ->
                ui.onNodeWithText("Skip and try virtual keypad").performClick()
                wizard.onActivity {
                    assertTrue(it.isFinishing)
                    result = shadowOf(it).resultCode
                }
            }
            main.start().resume()
            host.receiveResult(welcome.intent, result, null)
            assertTrue(app.session.preview)
            assertTrue(app.session.phoneKey(0))
            assertNull(shadowOf(app).nextStartedService)
            assertNull(host.lastRequestedPermission)
        }
        app.session.disconnect()
        shadowOf(app).clearNextStartedActivities()
        Robolectric.buildActivity(MainActivity::class.java, launcher).setup().use { main ->
            assertNull(shadowOf(main.get()).nextStartedActivity)
        }
    }

    @Test fun cameraChoiceSurvivesRotationAndOnlyRequestsCameraOnTap() {
        ActivityScenario.launch<SetupActivity>(SetupActivity.intent(app, false)).use { wizard ->
            tap(Feature.STUDIO.title)
            wizard.recreate()
            ui.onNodeWithText(Feature.STUDIO.title).assertIsOn()
            ui.onNodeWithText("Review setup steps").performClick()
            wizard.recreate()
            ui.onNodeWithText("Session notification").assertDoesNotExist()
            wizard.onActivity { assertNull(shadowOf(it).lastRequestedPermission) }
            tap("Allow Studio camera")
            wizard.onActivity {
                assertArrayEquals(arrayOf(Manifest.permission.CAMERA), shadowOf(it).lastRequestedPermission.requestedPermissions)
                assertEquals("android.content.pm.action.REQUEST_PERMISSIONS", shadowOf(it).nextStartedActivity.action)
                assertNull(shadowOf(it).nextStartedActivity)
            }
        }
        // Process-independent selection, not a cached grant. Android can revoke a one-time grant.
        shadowOf(app).grantPermissions(Manifest.permission.CAMERA)
        ActivityScenario.launch<SetupActivity>(SetupActivity.intent(app, false)).use { wizard ->
            ui.onNodeWithText("Review setup steps").performClick()
            ui.onNodeWithText("Camera permission allowed").assertExists()
            wizard.moveToState(androidx.lifecycle.Lifecycle.State.CREATED)
            shadowOf(app).denyPermissions(Manifest.permission.CAMERA)
            SetupChoices(app).markAsked(Manifest.permission.CAMERA)
            wizard.moveToState(androidx.lifecycle.Lifecycle.State.RESUMED)
            tap("Open app settings")
            wizard.onActivity {
                val intent = shadowOf(it).nextStartedActivity
                assertEquals(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, intent.action)
                assertEquals("package:${app.packageName}", intent.dataString)
            }
        }
    }

    @Test @Config(shadows = [KeypadSessionTest.ConsentUsb::class])
    fun usbStepAfterRecreationRechecksActualDeviceInsteadOfTrustingCallback() {
        val usb = shadowOf(app.getSystemService(UsbManager::class.java)) as KeypadSessionTest.ConsentUsb
        val device = ReflectionHelpers.newInstance(UsbDevice::class.java).also {
            ReflectionHelpers.setField(it, "mName", "keypad")
            ReflectionHelpers.setField(it, "mVendorId", 0x046d)
            ReflectionHelpers.setField(it, "mProductId", 0xc354)
            ReflectionHelpers.setField(it, "mConfigurations", emptyArray<UsbConfiguration>())
        }
        usb.addOrUpdateUsbDevice(device, false)
        ActivityScenario.launch<SetupActivity>(SetupActivity.intent(app, false)).use { wizard ->
            tap(Feature.USB.title)
            ui.onNodeWithText("Review setup steps").performClick()
            tap("Allow this keypad")
            val response = usb.reply
            wizard.recreate()
            response.send()
            shadowOf(Looper.getMainLooper()).idle()
            ui.onNodeWithText("USB access was not granted or the keypad was removed. You can try again.").assertExists()
            tap("Allow this keypad")
            usb.addOrUpdateUsbDevice(device, true)
            usb.reply.send()
            shadowOf(Looper.getMainLooper()).idle()
            ui.onNodeWithText("This keypad is authorized").assertExists()
            ui.onNodeWithText("Allow session notifications").assertExists()
            assertNull(shadowOf(app).nextStartedService)
        }
    }

    @Test fun specialAccessReturnsRecheckGrantWithoutEnablingAppPreferenceOrPromptingAgain() {
        ActivityScenario.launch<SetupActivity>(SetupActivity.intent(app, false)).use { wizard ->
            tap(Feature.DIRECT.title)
            tap(Feature.MUSIC.title)
            tap(Feature.NAVIGATION.title)
            ui.onNodeWithText("Review setup steps").performClick()
            tap("Open notification access")
            wizard.onActivity { assertEquals(Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS, shadowOf(it).nextStartedActivity.action) }
            tap("Open accessibility settings")
            wizard.onActivity { assertEquals(Settings.ACTION_ACCESSIBILITY_SETTINGS, shadowOf(it).nextStartedActivity.action) }
            tap("Open special access")
            wizard.onActivity { assertEquals(Settings.ACTION_MANAGE_OVERLAY_PERMISSION, shadowOf(it).nextStartedActivity.action) }
            wizard.moveToState(androidx.lifecycle.Lifecycle.State.CREATED)
            ShadowSettings.setCanDrawOverlays(true)
            shadowOf(notices).setNotificationListenerAccessGranted(ComponentName(app, MediaSessionAccess::class.java), true)
            wizard.moveToState(androidx.lifecycle.Lifecycle.State.RESUMED)
            ui.onNodeWithText("Media session access allowed").assertExists()
            assertFalse(DirectShortcuts(app).enabled)
            tap("Enable one-press shortcuts")
            assertTrue(DirectShortcuts(app).enabled)
            wizard.recreate()
            ui.onNodeWithText("One-press shortcuts on").assertExists()
            wizard.onActivity { assertNull(shadowOf(it).nextStartedActivity) }
        }
    }

    @Test @Config(sdk = [28, 35])
    fun usbWithoutHardwareCanConfigureNotificationsAndHandlerButCannotStartSession() {
        ActivityScenario.launch<SetupActivity>(SetupActivity.intent(app, false)).use { wizard ->
            tap(Feature.AUTO.title)
            ui.onNodeWithText("Review setup steps").performClick()
            ui.onNodeWithText("No supported keypad attached").assertExists()
            ui.onNodeWithText("Allow this keypad").assertDoesNotExist()
            assertFalse(UsbAccess(app).automatic)
            tap("Enable plug-in handling")
            assertTrue(UsbAccess(app).automatic)
            if (Build.VERSION.SDK_INT >= 33) {
                tap("Allow session notifications")
                wizard.onActivity {
                    assertArrayEquals(arrayOf(Manifest.permission.POST_NOTIFICATIONS), shadowOf(it).lastRequestedPermission.requestedPermissions)
                }
                shadowOf(app).grantPermissions(Manifest.permission.POST_NOTIFICATIONS)
            } else ui.onNodeWithText("Allow session notifications").assertDoesNotExist()
            wizard.moveToState(androidx.lifecycle.Lifecycle.State.CREATED)
            notices.createNotificationChannel(NotificationChannel(KeypadService.CHANNEL, "Session", NotificationManager.IMPORTANCE_NONE))
            wizard.moveToState(androidx.lifecycle.Lifecycle.State.RESUMED)
            tap("Open session channel")
            wizard.onActivity {
                val channel = shadowOf(it).nextStartedActivity
                assertEquals(Settings.ACTION_CHANNEL_NOTIFICATION_SETTINGS, channel.action)
                assertEquals(KeypadService.CHANNEL, channel.getStringExtra(Settings.EXTRA_CHANNEL_ID))
            }
            assertNull(shadowOf(app).nextStartedService)
            assertNull(app.session.request)
        }
    }

    @Test fun onlyFreshLauncherConsumesWelcome() {
        val choices = SetupChoices(app)
        assertFalse(choices.takeLauncherWelcome(Intent(UsbManager.ACTION_USB_DEVICE_ATTACHED)))
        assertFalse(choices.takeLauncherWelcome(Intent(app, MainActivity::class.java)))
        assertFalse(choices.takeLauncherWelcome(launcher.addFlags(Intent.FLAG_ACTIVITY_LAUNCHED_FROM_HISTORY)))
        assertFalse(choices.takeLauncherWelcome(launcher.putExtra(Intent.EXTRA_SHORTCUT_ID, "key")))
        assertTrue(choices.takeLauncherWelcome(launcher))
        assertFalse(choices.takeLauncherWelcome(launcher))
    }

    @Test fun upgradeDoesNotInterruptLauncher() {
        @Suppress("DEPRECATION")
        val info = app.packageManager.getPackageInfo(app.packageName, 0)
        info.lastUpdateTime = info.firstInstallTime + 1000
        shadowOf(app.packageManager).installPackage(info)
        assertFalse(SetupChoices(app).takeLauncherWelcome(launcher))
    }

    @Test fun reentryPicksUpExistingFeaturesAndDeselectionDoesNotResetThem() {
        UsbAccess(app).automatic = true
        ShadowSettings.setCanDrawOverlays(true)
        DirectShortcuts(app).enabled = true
        ActivityScenario.launch<SetupActivity>(SetupActivity.intent(app, false)).use {
            ui.onNodeWithText(Feature.AUTO.title).assertIsOn()
            ui.onNodeWithText(Feature.DIRECT.title).assertIsOn()
            tap(Feature.AUTO.title)
            tap(Feature.DIRECT.title)
            ui.onNodeWithText("Review setup steps").performClick()
            assertTrue(UsbAccess(app).automatic)
            assertTrue(DirectShortcuts(app).enabled)
        }
    }
}
