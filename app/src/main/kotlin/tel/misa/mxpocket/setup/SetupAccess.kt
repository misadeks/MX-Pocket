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
import tel.misa.mxpocket.decks.CameraControlsAccess
import tel.misa.mxpocket.decks.CameraControlsActivity
import tel.misa.mxpocket.decks.NavigationService
import tel.misa.mxpocket.decks.hasMediaSessionAccess
import tel.misa.mxpocket.driver.UsbDiagnostics
import tel.misa.mxpocket.i18n.AccessCopy
import tel.misa.mxpocket.shared.ui.SetupAction
import tel.misa.mxpocket.shared.ui.SetupFeature as Feature
import tel.misa.mxpocket.shared.ui.SetupStep
import tel.misa.mxpocket.shared.ui.CameraControlsDisclosure
import tel.misa.mxpocket.shared.ui.DeckAccess
import tel.misa.mxpocket.shared.ui.DirectShortcutDisclosure
import tel.misa.mxpocket.ui.LanguagePreference

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
        if (CameraControlsAccess(host).enabled) add(Feature.STUDIO)
        if (shortcuts.enabled) add(Feature.DIRECT)
        if (navigationAllowed()) add(Feature.NAVIGATION)
    }

    fun steps(features: Set<Feature>): List<SetupStep> = buildList {
        if (features.any { it == Feature.USB || it == Feature.AUTO || it == Feature.DIRECT }) {
            val devices = keypads()
            val device = devices.singleOrNull()
            val allowed = device != null && usb.hasPermission(device)
            add(SetupStep(AccessCopy.PhysicalKeypad.text(), AccessCopy.UsbInstructions.text(), when {
                devices.isEmpty() -> AccessCopy.NoKeypad
                device == null -> AccessCopy.MultipleKeypads
                allowed -> AccessCopy.KeypadAuthorized
                else -> AccessCopy.KeypadNeedsApproval
            }.text(), allowed, SetupAction.USB.takeIf { device != null && !allowed }))
            val notice = when {
                Build.VERSION.SDK_INT >= 33 && !granted(Manifest.permission.POST_NOTIFICATIONS) ->
                    if (restricted(Manifest.permission.POST_NOTIFICATIONS)) SetupAction.APP_NOTICES else SetupAction.POST
                !notifications.areNotificationsEnabled() -> SetupAction.APP_NOTICES
                KeypadService.sessionChannelBlocked(notifications) -> SetupAction.CHANNEL
                else -> null
            }
            add(SetupStep(AccessCopy.SessionNotice.text(), AccessCopy.SessionNoticeDetail.text(), when (notice) {
                null -> AccessCopy.NoticesAllowed
                SetupAction.CHANNEL -> AccessCopy.ChannelOff
                SetupAction.POST -> AccessCopy.NoticePermissionOff
                else -> AccessCopy.AppNoticesOff
            }.text(), notice == null, notice))
        }
        if (Feature.AUTO in features) {
            val enabled = attach.automatic
            add(SetupStep(AccessCopy.Auto.text(), AccessCopy.AutoDetail.text(),
                if (enabled) AccessCopy.AutoOn.text() else AccessCopy.AppOptionOff.text(), enabled, SetupAction.AUTO.takeUnless { enabled }))
        }
        if (Feature.MUSIC in features) {
            val allowed = host.hasMediaSessionAccess()
            val serviceName = LanguagePreference.resourcesFor(host).getString(R.string.media_access)
            add(SetupStep(AccessCopy.PlayerControls.text(), "${DeckAccess.Media.disclosure} ${AccessCopy.MediaInstructions.text(serviceName)}",
                if (allowed) AccessCopy.MediaAllowed.text() else AccessCopy.BasicMediaKeys.text(), allowed, SetupAction.MEDIA.takeUnless { allowed }))
        }
        if (Feature.STUDIO in features) {
            val enabled = CameraControlsAccess(host).enabled
            val ready = enabled && navigationAllowed()
            add(SetupStep(AccessCopy.StudioControls.text(), CameraControlsDisclosure,
                when { ready -> AccessCopy.KeypadControlsEnabled; enabled -> AccessCopy.NavigationOff; else -> AccessCopy.ViewfinderOnly }.text(),
                ready, SetupAction.CAMERA))
        }
        if (Feature.DIRECT in features) {
            val allowed = Settings.canDrawOverlays(host)
            val enabled = shortcuts.enabled
            add(SetupStep(AccessCopy.DirectOpening.text(), DirectShortcutDisclosure,
                when { enabled -> AccessCopy.DirectOn; allowed -> AccessCopy.AccessAllowedOptionOff; else -> AccessCopy.OverlayOff }.text(),
                enabled, when { enabled -> null; allowed -> SetupAction.DIRECT; else -> SetupAction.OVERLAY }))
        }
        if (Feature.NAVIGATION in features) {
            val allowed = navigationAllowed()
            val serviceName = LanguagePreference.resourcesFor(host).getString(R.string.navigation_access)
            add(SetupStep(AccessCopy.CrossAppNavigation.text(), "${DeckAccess.Navigation.disclosure} ${AccessCopy.NavigationInstructions.text(serviceName)}",
                if (allowed) AccessCopy.NavigationEnabled.text() else AccessCopy.InAppNavigation.text(),
                allowed, SetupAction.NAVIGATION.takeUnless { allowed }))
        }
    }

    fun settings(action: SetupAction): Intent = when (action) {
        SetupAction.APP_NOTICES -> Intent(Settings.ACTION_APP_NOTIFICATION_SETTINGS).putExtra(Settings.EXTRA_APP_PACKAGE, host.packageName)
        SetupAction.CHANNEL -> Intent(Settings.ACTION_CHANNEL_NOTIFICATION_SETTINGS)
            .putExtra(Settings.EXTRA_APP_PACKAGE, host.packageName).putExtra(Settings.EXTRA_CHANNEL_ID, KeypadService.CHANNEL)
        SetupAction.CAMERA -> Intent(host, CameraControlsActivity::class.java)
        SetupAction.MEDIA, SetupAction.NAVIGATION -> DeckAccessActivity.settings(action == SetupAction.MEDIA)
        SetupAction.OVERLAY -> Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION, packageUri)
        else -> error("Not a settings action: $action")
    }
}
