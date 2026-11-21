package tel.misa.mxpocket.setup

import android.Manifest
import android.accessibilityservice.AccessibilityServiceInfo
import android.app.Activity
import android.app.NotificationManager
import android.content.ComponentName
import android.content.Intent
import android.content.pm.PackageManager
import android.hardware.usb.UsbManager
import android.net.Uri
import android.os.Build
import android.provider.Settings
import android.view.accessibility.AccessibilityManager
import tel.misa.mxpocket.DirectShortcuts
import tel.misa.mxpocket.KeypadService
import tel.misa.mxpocket.R
import tel.misa.mxpocket.UsbAccess
import tel.misa.mxpocket.decks.DeckAccessActivity
import tel.misa.mxpocket.decks.NavigationService
import tel.misa.mxpocket.decks.hasMediaSessionAccess
import tel.misa.mxpocket.driver.UsbDiagnostics

internal enum class SetupAction(val label: String) {
    USB("Allow this keypad"), POST("Allow session notifications"), APP_NOTICES("Open notification settings"),
    CHANNEL("Open session channel"), AUTO("Enable plug-in handling"), MEDIA("Open notification access"),
    CAMERA("Allow Studio camera"), APP_DETAILS("Open app settings"), OVERLAY("Open special access"),
    DIRECT("Enable one-press shortcuts"), NAVIGATION("Open accessibility settings")
}

internal data class SetupStep(val title: String, val detail: String, val status: String,
    val ready: Boolean = false, val action: SetupAction? = null)

internal class SetupAccess(private val host: Activity, private val choices: SetupChoices) {
    val usb: UsbManager = host.getSystemService(UsbManager::class.java)
    val attach = UsbAccess(host)
    val shortcuts = DirectShortcuts(host)
    private val notifications = host.getSystemService(NotificationManager::class.java)
    private val packageUri = Uri.fromParts("package", host.packageName, null)
    private fun granted(permission: String) = host.checkSelfPermission(permission) == PackageManager.PERMISSION_GRANTED
    private fun restricted(permission: String) =
        choices.asked(permission) && !host.shouldShowRequestPermissionRationale(permission)
    fun keypads() = usb.deviceList.values.filter(UsbDiagnostics::isKeypad)
    private fun navigationAllowed(): Boolean {
        val component = ComponentName(host, NavigationService::class.java)
        return host.getSystemService(AccessibilityManager::class.java)
            .getEnabledAccessibilityServiceList(AccessibilityServiceInfo.FEEDBACK_ALL_MASK).any {
                val service = it.resolveInfo.serviceInfo
                ComponentName(service.packageName, service.name) == component
            }
    }

    fun existingFeatures() = buildSet {
        if (attach.automatic || keypads().any(usb::hasPermission)) add(Feature.USB)
        if (attach.automatic) add(Feature.AUTO)
        if (host.hasMediaSessionAccess()) add(Feature.MUSIC)
        if (granted(Manifest.permission.CAMERA)) add(Feature.STUDIO)
        if (shortcuts.enabled) add(Feature.DIRECT)
        if (navigationAllowed()) add(Feature.NAVIGATION)
    }

    fun steps(features: Set<Feature>): List<SetupStep> = buildList {
        if (features.any { it == Feature.USB || it == Feature.AUTO || it == Feature.DIRECT }) {
            val devices = keypads()
            val device = devices.singleOrNull()
            val allowed = device != null && usb.hasPermission(device)
            add(SetupStep("Physical keypad", "Connect one MX Creative Keypad with a data cable. USB access applies to this attachment, not all USB devices.", when {
                devices.isEmpty() -> "No supported keypad attached"
                device == null -> "More than one keypad attached"
                allowed -> "This keypad is authorized"
                else -> "This keypad needs approval"
            }, allowed, SetupAction.USB.takeIf { device != null && !allowed }))
            val notice = when {
                Build.VERSION.SDK_INT >= 33 && !granted(Manifest.permission.POST_NOTIFICATIONS) ->
                    if (restricted(Manifest.permission.POST_NOTIFICATIONS)) SetupAction.APP_NOTICES else SetupAction.POST
                !notifications.areNotificationsEnabled() -> SetupAction.APP_NOTICES
                KeypadService.sessionChannelBlocked(notifications) -> SetupAction.CHANNEL
                else -> null
            }
            add(SetupStep("Session notification", "MX Pocket requires a visible notification so you can disconnect a background USB session.", when (notice) {
                null -> "Notifications allowed"
                SetupAction.CHANNEL -> "Keypad session channel is off"
                SetupAction.POST -> "Notification permission is off"
                else -> "App notifications are off"
            }, notice == null, notice))
        }
        if (Feature.AUTO in features) {
            val enabled = attach.automatic
            add(SetupStep("Connect on plug-in", "Enabling this option makes MX Pocket a USB handler. Replug the keypad and choose MX Pocket if Android asks. Android controls the default; you can always connect manually.",
                if (enabled) "App option on; Android chooses the handler" else "App option off", enabled, SetupAction.AUTO.takeUnless { enabled }))
        }
        if (Feature.MUSIC in features) {
            val allowed = host.hasMediaSessionAccess()
            add(SetupStep("Player controls and artwork", DeckAccessActivity.disclosure(true) +
                " In Notification access, select MX Pocket media sessions. This is separate from showing session notifications.",
                if (allowed) "Media session access allowed" else "Basic media keys only", allowed, SetupAction.MEDIA.takeUnless { allowed }))
        }
        if (Feature.STUDIO in features) {
            val camera = host.packageManager.hasSystemFeature(PackageManager.FEATURE_CAMERA_ANY)
            val allowed = granted(Manifest.permission.CAMERA)
            val action = when {
                !camera || allowed -> null
                restricted(Manifest.permission.CAMERA) -> SetupAction.APP_DETAILS
                else -> SetupAction.CAMERA
            }
            add(SetupStep("Pocket Studio camera", if (action == SetupAction.APP_DETAILS)
                "Choose Permissions, then Camera in Android app settings, or leave Studio off."
                else "Only Pocket Studio uses the camera. Setup never opens the viewfinder or starts a capture.",
                when { !camera -> "No camera reported"; allowed -> "Camera permission allowed"; else -> "Camera permission off" },
                camera && allowed, action))
        }
        if (Feature.DIRECT in features) {
            val allowed = Settings.canDrawOverlays(host)
            val enabled = shortcuts.enabled
            add(SetupStep("One-press app opening", host.getString(R.string.direct_shortcuts_rationale),
                when { enabled -> "One-press shortcuts on"; allowed -> "Access allowed; app option off"; else -> "Display over other apps is off" },
                enabled, when { enabled -> null; allowed -> SetupAction.DIRECT; else -> SetupAction.OVERLAY }))
        }
        if (Feature.NAVIGATION in features) {
            val allowed = navigationAllowed()
            add(SetupStep("Cross-app navigation", DeckAccessActivity.disclosure(false) +
                " In Accessibility, select MX Pocket navigation.", if (allowed) "Navigation service enabled" else "In-app navigation only",
                allowed, SetupAction.NAVIGATION.takeUnless { allowed }))
        }
    }

    fun settings(action: SetupAction): Intent = when (action) {
        SetupAction.APP_NOTICES -> Intent(Settings.ACTION_APP_NOTIFICATION_SETTINGS).putExtra(Settings.EXTRA_APP_PACKAGE, host.packageName)
        SetupAction.CHANNEL -> Intent(Settings.ACTION_CHANNEL_NOTIFICATION_SETTINGS)
            .putExtra(Settings.EXTRA_APP_PACKAGE, host.packageName).putExtra(Settings.EXTRA_CHANNEL_ID, KeypadService.CHANNEL)
        SetupAction.APP_DETAILS -> Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, packageUri)
        SetupAction.MEDIA, SetupAction.NAVIGATION -> DeckAccessActivity.settings(action == SetupAction.MEDIA)
        SetupAction.OVERLAY -> Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION, packageUri)
        else -> error("Not a settings action: $action")
    }
}
