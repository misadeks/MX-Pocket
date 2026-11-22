package tel.misa.mxpocket.setup

import android.Manifest
import android.app.AppOpsManager
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
import androidx.compose.ui.test.assertIsOff
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
import tel.misa.mxpocket.decks.TeamsAccess
import tel.misa.mxpocket.decks.TeamsCallListener
import tel.misa.mxpocket.decks.TeamsToolbarService
import tel.misa.mxpocket.decks.NavigationService
import tel.misa.mxpocket.decks.OneNoteAccessService
import tel.misa.mxpocket.decks.CameraControlsAccess
import tel.misa.mxpocket.decks.DeckAccessActivity
import tel.misa.mxpocket.decks.DeckCommand
import tel.misa.mxpocket.shared.ui.SetupAction
import tel.misa.mxpocket.shared.ui.SetupFeature as Feature

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

    @Test fun dismissingFirstRunLeavesThePhoneDisconnectedWithoutPromptsOrService() {
        Robolectric.buildActivity(MainActivity::class.java, launcher).setup().use { main ->
            val host = shadowOf(main.get())
            val welcome = host.nextStartedActivity
            assertEquals(SetupActivity::class.java.name, welcome.component?.className)
            main.pause().stop()
            ActivityScenario.launch<SetupActivity>(welcome).use { wizard ->
                ui.onNodeWithText("Skip and try virtual keypad").assertDoesNotExist()
                ui.onNodeWithText("Not now").performClick()
                wizard.onActivity {
                    assertTrue(it.isFinishing)
                    assertEquals(android.app.Activity.RESULT_CANCELED, shadowOf(it).resultCode)
                }
            }
            main.start().resume()
            assertFalse(app.session.ready)
            assertNull(app.session.request)
            assertNull(shadowOf(app).nextStartedService)
            assertNull(host.lastRequestedPermission)
        }
        shadowOf(app).clearNextStartedActivities()
        Robolectric.buildActivity(MainActivity::class.java, launcher).setup().use { main ->
            assertNull(shadowOf(main.get()).nextStartedActivity)
        }
    }

    @Test fun cameraChoiceSurvivesRotationAndOnlyEnablesTheAppOptInOnTap() {
        ActivityScenario.launch<SetupActivity>(SetupActivity.intent(app, false)).use { wizard ->
            tap(Feature.STUDIO.title)
            wizard.recreate()
            ui.onNodeWithText(Feature.STUDIO.title).assertIsOn()
            ui.onNodeWithText("Review setup steps").performClick()
            wizard.recreate()
            ui.onNodeWithText("Session notification").assertExists()
            wizard.onActivity { assertNull(shadowOf(it).lastRequestedPermission) }
            assertFalse(CameraControlsAccess(app).enabled)
            tap("Enable keypad camera controls")
            ui.onNodeWithText("Open accessibility settings").assertExists()
            wizard.onActivity {
                assertNull(shadowOf(it).lastRequestedPermission)
                assertNull(shadowOf(it).nextStartedActivity)
            }
        }
        assertTrue(CameraControlsAccess(app).enabled)
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
            wizard.onActivity { assertListener(shadowOf(it).nextStartedActivity, MediaSessionAccess::class.java) }
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
        assertFalse(choices.takeLauncherWelcome(launcher.setData(android.net.Uri.parse("mxpocket://shortcut"))))
        assertTrue(choices.takeLauncherWelcome(launcher))
        assertFalse(choices.takeLauncherWelcome(launcher))
    }

    @Test fun firstNormalLauncherCanArriveAsNewIntentAfterNotification() {
        val notification = Intent(app, MainActivity::class.java)
            .addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP)
        Robolectric.buildActivity(MainActivity::class.java, notification).setup().use { main ->
            val host = shadowOf(main.get())
            assertNull(host.nextStartedActivity)
            main.newIntent(launcher)
            assertEquals(SetupActivity::class.java.name, host.nextStartedActivity.component?.className)
            main.newIntent(launcher)
            assertNull(host.nextStartedActivity)
        }
    }

    @Test fun restoredGuideAndAllAndroidGrantsStillShowFreshWelcomeButDoNotOptInToExtras() {
        app.getSharedPreferences("feature-setup", 0).edit().putBoolean("seen", true)
            .putBoolean(Manifest.permission.POST_NOTIFICATIONS, true)
            .putStringSet("features", Feature.entries.map { it.name }.toSet()).apply()
        shadowOf(app).grantPermissions(Manifest.permission.POST_NOTIFICATIONS)
        shadowOf(notices).setNotificationListenerAccessGranted(ComponentName(app, MediaSessionAccess::class.java), true)
        shadowOf(notices).setNotificationListenerAccessGranted(ComponentName(app, TeamsCallListener::class.java), true)
        Settings.Secure.putString(app.contentResolver, Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES,
            listOf(NavigationService::class.java, TeamsToolbarService::class.java, OneNoteAccessService::class.java)
                .joinToString(":") { ComponentName(app, it).flattenToString() })
        shadowOf(app.getSystemService(AppOpsManager::class.java)).setMode(AppOpsManager.OPSTR_GET_USAGE_STATS, app.applicationInfo.uid,
            app.packageName, AppOpsManager.MODE_ALLOWED)
        ShadowSettings.setCanDrawOverlays(true)
        app.session.setPluginEnabled("onenote", false)
        val device = ReflectionHelpers.newInstance(UsbDevice::class.java).also {
            ReflectionHelpers.setField(it, "mName", "authorized-keypad")
            ReflectionHelpers.setField(it, "mVendorId", 0x046d)
            ReflectionHelpers.setField(it, "mProductId", 0xc354)
            ReflectionHelpers.setField(it, "mConfigurations", emptyArray<UsbConfiguration>())
        }
        shadowOf(app.getSystemService(UsbManager::class.java)).addOrUpdateUsbDevice(device, true)
        Robolectric.buildActivity(MainActivity::class.java, launcher).setup().use { main ->
            val welcome = shadowOf(main.get()).nextStartedActivity
            assertEquals(SetupActivity::class.java.name, welcome.component?.className)
            main.pause().stop()
            ActivityScenario.launch<SetupActivity>(welcome).use { wizard ->
                ui.onNodeWithText(Feature.MUSIC.title).assertIsOff()
                ui.onNodeWithText(Feature.PROFILES.title).assertIsOff()
                tap(Feature.STUDIO.title)
                tap(Feature.MUSIC.title)
                tap(Feature.PROFILES.title)
                tap(Feature.ONENOTE.title)
                ui.onNodeWithText("Review setup steps").performClick()
                ui.onNodeWithText("This keypad is authorized").assertExists()
                listOf(SetupAction.USB, SetupAction.POST, SetupAction.MEDIA, SetupAction.NAVIGATION, SetupAction.USAGE, SetupAction.ONENOTE_ACCESS)
                    .forEach { ui.onNodeWithText(it.label).assertDoesNotExist() }
                ui.onNodeWithText(SetupAction.CAMERA.label).assertExists()
                ui.onNodeWithText(SetupAction.DETECTION.label).assertExists()
                tap(SetupAction.ONENOTE_PLUGIN.label)
                assertFalse("onenote" in app.session.disabledPlugins.value)
                assertFalse(app.session.detectForegroundApps.value)
                assertFalse(CameraControlsAccess(app).enabled)
                wizard.onActivity {
                    assertNull(shadowOf(it).lastRequestedPermission)
                    assertNull(shadowOf(it).nextStartedActivity)
                }
            }
        }
        assertFalse(SetupChoices(app).takeLauncherWelcome(launcher))
    }

    @Test fun missingProfilePreferencesAndTeamsConsentRemainIndependentOfGrantsAndLiveServices() {
        shadowOf(app.getSystemService(AppOpsManager::class.java)).setMode(AppOpsManager.OPSTR_GET_USAGE_STATS,
            app.applicationInfo.uid, app.packageName, AppOpsManager.MODE_IGNORED)
        app.session.setPluginEnabled("teams", false)
        TeamsAccess(app).enabled = false
        shadowOf(notices).setNotificationListenerAccessGranted(ComponentName(app, MediaSessionAccess::class.java), true)
        ActivityScenario.launch<SetupActivity>(SetupActivity.intent(app, true)).use { wizard ->
            tap(Feature.PROFILES.title)
            tap(Feature.TEAMS.title)
            tap(Feature.TEAMS_CONTROLS.title)
            ui.onNodeWithText("Review setup steps").performClick()
            tap(SetupAction.TEAMS_NOTICES.label)
            wizard.onActivity { assertListener(shadowOf(it).nextStartedActivity, TeamsCallListener::class.java) }
            wizard.moveToState(androidx.lifecycle.Lifecycle.State.CREATED)
            shadowOf(notices).setNotificationListenerAccessGranted(ComponentName(app, TeamsCallListener::class.java), true)
            Settings.Secure.putString(app.contentResolver, Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES,
                ComponentName(app, TeamsToolbarService::class.java).flattenToString())
            wizard.moveToState(androidx.lifecycle.Lifecycle.State.RESUMED)
            listOf(SetupAction.TEAMS_NOTICES, SetupAction.TOOLBAR).forEach { ui.onNodeWithText(it.label).assertDoesNotExist() }
            assertFalse(TeamsAccess(app).enabled)
            tap(SetupAction.TEAMS_PLUGIN.label)
            tap(SetupAction.TEAMS.label)
            assertTrue(TeamsAccess(app).enabled)
            tap(SetupAction.DETECTION.label)
            ui.onNodeWithText("Enable profile switching").assertDoesNotExist()
            ui.onNodeWithText(SetupAction.USAGE.label).assertExists()
            assertTrue(app.session.detectForegroundApps.value)
            wizard.onActivity { assertNull(shadowOf(it).nextStartedActivity) }
        }
    }

    @Test @Config(sdk = [28, 35])
    fun audioPluginOpensOnlyItsOwnDetailOrNamedFallbackAndRechecksOnReturn() {
        shadowOf(notices).setNotificationListenerAccessGranted(ComponentName(app, TeamsCallListener::class.java), true)
        ActivityScenario.launch<DeckAccessActivity>(DeckAccessActivity.intent(app, DeckCommand.MEDIA_ACCESS)).use { screen ->
            ui.onNodeWithText("Media session access not granted").assertExists()
            ui.onNodeWithText("go back without choosing either", substring = true).assertExists()
            tap("Open Android settings")
            screen.onActivity {
                val opened = shadowOf(it).nextStartedActivity
                if (Build.VERSION.SDK_INT >= 30) assertListener(opened, MediaSessionAccess::class.java)
                else assertEquals(Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS, opened.action)
            }
            screen.moveToState(androidx.lifecycle.Lifecycle.State.CREATED)
            shadowOf(notices).setNotificationListenerAccessGranted(ComponentName(app, MediaSessionAccess::class.java), true)
            screen.moveToState(androidx.lifecycle.Lifecycle.State.RESUMED)
            ui.onNodeWithText("Media session access allowed").assertExists()
            screen.onActivity { assertNull(shadowOf(it).nextStartedActivity) }
        }
    }

    private fun assertListener(intent: Intent, listener: Class<*>) {
        assertEquals(Settings.ACTION_NOTIFICATION_LISTENER_DETAIL_SETTINGS, intent.action)
        assertEquals(ComponentName(app, listener).flattenToString(), intent.getStringExtra(Settings.EXTRA_NOTIFICATION_LISTENER_COMPONENT_NAME))
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
