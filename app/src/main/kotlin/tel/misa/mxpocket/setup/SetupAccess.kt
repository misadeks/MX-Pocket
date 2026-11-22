package tel.misa.mxpocket.setup

import android.Manifest
import android.app.Activity
import android.app.NotificationManager
import android.content.ComponentName
import android.content.Intent
import android.content.pm.PackageManager
import android.hardware.usb.UsbManager
import android.net.Uri
import android.os.Build
import android.provider.Settings
import tel.misa.mxpocket.DirectShortcuts
import tel.misa.mxpocket.KeypadService
import tel.misa.mxpocket.PocketApplication
import tel.misa.mxpocket.R
import tel.misa.mxpocket.UsbAccess
import tel.misa.mxpocket.decks.CameraControlsAccess
import tel.misa.mxpocket.decks.DeckAccessActivity
import tel.misa.mxpocket.NotificationAccess
import tel.misa.mxpocket.decks.NavigationService
import tel.misa.mxpocket.decks.OneNoteAccessService
import tel.misa.mxpocket.decks.TeamsAccess
import tel.misa.mxpocket.decks.TeamsEvents
import tel.misa.mxpocket.decks.TeamsToolbarService
import tel.misa.mxpocket.driver.UsbDiagnostics
import tel.misa.mxpocket.i18n.AccessCopy
import tel.misa.mxpocket.i18n.OneNoteCopy
import tel.misa.mxpocket.i18n.builtInPluginTitle
import tel.misa.mxpocket.ui.LanguagePreference
import tel.misa.mxpocket.hasUsageAccess
import tel.misa.mxpocket.shared.ui.*
import tel.misa.mxpocket.shared.ui.SetupAction as Action
import tel.misa.mxpocket.shared.ui.SetupFeature as Feature

internal class SetupAccess(private val host: Activity, private val choices: SetupChoices) {
    val usb: UsbManager = host.getSystemService(UsbManager::class.java)
    private val attach = UsbAccess(host)
    private val shortcuts = DirectShortcuts(host)
    private val camera = CameraControlsAccess(host)
    private val teams = TeamsAccess(host)
    private val listener = NotificationAccess(host)
    private val session get() = (host.application as PocketApplication).session
    private val notifications = host.getSystemService(NotificationManager::class.java)
    fun keypads() = usb.deviceList.values.filter(UsbDiagnostics::isKeypad)

    // AccessibilityManager's service list can lag until binding. Setup asks about the
    // user's setting, not whether Android has currently connected the service.
    private fun accessibilityAllowed(service: Class<*>): Boolean {
        val component = ComponentName(host, service)
        return Settings.Secure.getString(host.contentResolver, Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES)
            .orEmpty().split(':').any { ComponentName.unflattenFromString(it) == component }
    }

    fun options() = Feature.entries.filter { it != Feature.USB }.map { feature ->
        SetupOption(feature, unavailableReason = when {
            feature in setOf(Feature.TEAMS, Feature.TEAMS_CONTROLS) && Build.VERSION.SDK_INT < 34 ->
                AccessCopy.UnsupportedCallStatus.text()
            feature == Feature.TEAMS_CONTROLS && !teams.toolbarProfileEligible ->
                AccessCopy.TeamsProfileNotice.text()
            else -> null
        })
    }

    fun existingFeatures() = buildSet {
        if (attach.automatic) add(Feature.AUTO)
        if (listener.music && listener.granted) add(Feature.MUSIC)
        if (camera.enabled) add(Feature.STUDIO)
        if (shortcuts.optedIn) add(Feature.DIRECT)
        if (accessibilityAllowed(NavigationService::class.java)) add(Feature.NAVIGATION)
        if (session.detectForegroundApps.value) add(Feature.PROFILES)
        if (accessibilityAllowed(OneNoteAccessService::class.java)) add(Feature.ONENOTE)
        if (teams.granted && teams.enabled) add(Feature.TEAMS)
        if (teams.granted && teams.enabled && accessibilityAllowed(TeamsToolbarService::class.java)) add(Feature.TEAMS_CONTROLS)
    }

    fun steps(features: Set<Feature>, pendingUsb: String? = null): List<SetupStep> = buildList {
        fun setting(feature: Feature, title: String, detail: String, ready: Boolean, action: Action,
                    on: String = AccessCopy.OnInApp.text(), off: String = AccessCopy.OffInApp.text(), actionable: Boolean = true) {
            add(SetupStep(feature, title, detail, if (ready) on else off, ready, action.takeIf { !ready && actionable }))
        }
        fun plugin(feature: Feature, name: String, id: String, action: Action) {
            setting(feature, AccessCopy.PluginName.text(builtInPluginTitle(id, name)), "", id !in session.disabledPlugins.value, action)
        }

        val devices = keypads()
        val device = devices.singleOrNull()
        val allowed = device != null && usb.hasPermission(device)
        val waiting = device != null && device.deviceName == pendingUsb
        add(SetupStep(Feature.USB, AccessCopy.PhysicalKeypad.text(), AccessCopy.UsbInstructions.text(), when {
            devices.isEmpty() -> AccessCopy.NoKeypad.text()
            device == null -> AccessCopy.MultipleKeypads.text()
            allowed -> AccessCopy.KeypadAuthorized.text()
            waiting -> AccessCopy.WaitingUsb.text()
            else -> AccessCopy.KeypadNeedsApproval.text()
        }, allowed, Action.USB.takeIf { device != null && !allowed && !waiting }, applicable = device != null))

        val post = Manifest.permission.POST_NOTIFICATIONS
        val notice = when {
            Build.VERSION.SDK_INT >= 33 && host.checkSelfPermission(post) != PackageManager.PERMISSION_GRANTED ->
                if (choices.asked(post) && !host.shouldShowRequestPermissionRationale(post)) Action.APP_NOTICES else Action.POST
            !notifications.areNotificationsEnabled() -> Action.APP_NOTICES
            else -> null
        }
        add(SetupStep(Feature.USB, AccessCopy.SessionNotice.text(),
            AccessCopy.SessionNoticeDetail.text(),
            when (notice) {
                null -> AccessCopy.NoticesAllowed.text()
                Action.POST -> AccessCopy.NoticePermissionOff.text()
                else -> AccessCopy.AppNoticesOff.text()
            }, notice == null, notice))
        if (notifications.getNotificationChannel(KeypadService.CHANNEL) != null) {
            setting(Feature.USB, AccessCopy.SessionChannel.text(), AccessCopy.SessionChannelDetail.text(),
                !KeypadService.sessionChannelBlocked(notifications), Action.CHANNEL, AccessCopy.ChannelOn.text(), AccessCopy.ChannelOff.text())
        }

        if (Feature.AUTO in features) setting(Feature.AUTO, AccessCopy.UsbHandling.text(),
            AccessCopy.AutoDetail.text(),
            attach.automatic, Action.AUTO)

        val controls = Feature.TEAMS_CONTROLS in features && teams.toolbarProfileEligible
        val teamsGroup = if (Build.VERSION.SDK_INT >= 34) when {
            Feature.TEAMS in features -> Feature.TEAMS
            controls -> Feature.TEAMS_CONTROLS
            else -> null
        } else null
        (Feature.MUSIC.takeIf { it in features } ?: teamsGroup)?.let { group ->
            setting(group, AccessCopy.NotificationAccess.text(), AccessCopy.SharedListenerHint.text(),
                listener.granted, Action.NOTIFICATION_ACCESS, AccessCopy.AndroidAllowed.text(), AccessCopy.AndroidNotAllowed.text())
        }
        if (Feature.MUSIC in features) {
            plugin(Feature.MUSIC, "Audio", "audio", Action.AUDIO_PLUGIN)
            setting(Feature.MUSIC, AccessCopy.MediaAccess.text(), DeckAccess.Media.disclosure, listener.music, Action.MUSIC)
        }
        if (Feature.STUDIO in features) {
            plugin(Feature.STUDIO, "Studio", "studio", Action.STUDIO_PLUGIN)
            setting(Feature.STUDIO, AccessCopy.CameraKeys.text(), CameraControlsDisclosure, camera.enabled, Action.CAMERA)
        }
        if (Feature.NAVIGATION in features) plugin(Feature.NAVIGATION, "Navigation", "navigation", Action.NAV_PLUGIN)
        if (Feature.NAVIGATION in features || Feature.STUDIO in features) {
            val group = if (Feature.NAVIGATION in features) Feature.NAVIGATION else Feature.STUDIO
            setting(group, AccessCopy.NavigationPermission.text(), DeckAccess.Navigation.disclosure + " " +
                AccessCopy.NavigationInstructions.text(LanguagePreference.resourcesFor(host).getString(R.string.navigation_access)),
                accessibilityAllowed(NavigationService::class.java), Action.NAVIGATION, AccessCopy.AndroidAllowed.text(), AccessCopy.AndroidNotAllowed.text())
        }
        if (Feature.DIRECT in features) {
            val overlay = Settings.canDrawOverlays(host)
            setting(Feature.DIRECT, AccessCopy.Overlay.text(), DirectShortcutDisclosure, overlay, Action.OVERLAY,
                AccessCopy.AndroidAllowed.text(), AccessCopy.AndroidNotAllowed.text())
            setting(Feature.DIRECT, AccessCopy.OnePress.text(), AccessCopy.OnePressDetail.text(),
                shortcuts.optedIn, Action.DIRECT, AccessCopy.OnePressOn.text(),
                if (overlay) AccessCopy.OffInApp.text() else AccessCopy.AllowOverlay.text(), actionable = overlay)
        }
        if (Feature.PROFILES in features) {
            setting(Feature.PROFILES, AccessCopy.UsageAccess.text(), AccessCopy.UsageDetail.text(),
                host.hasUsageAccess(), Action.USAGE, AccessCopy.AndroidAllowed.text(), AccessCopy.AndroidNotAllowed.text())
            setting(Feature.PROFILES, AccessCopy.DetectApps.text(), AccessCopy.DetectAppsDetail.text(),
                session.detectForegroundApps.value, Action.DETECTION)
        }
        if (Feature.ONENOTE in features) {
            plugin(Feature.ONENOTE, "OneNote", "onenote", Action.ONENOTE_PLUGIN)
            val resources = LanguagePreference.resourcesFor(host)
            setting(Feature.ONENOTE, OneNoteCopy.AccessTitle.text(), resources.getString(R.string.onenote_access_description) +
                " " + AccessCopy.NavigationInstructions.text(resources.getString(R.string.onenote_access)),
                accessibilityAllowed(OneNoteAccessService::class.java), Action.ONENOTE_ACCESS,
                AccessCopy.AndroidAllowed.text(), AccessCopy.AndroidNotAllowed.text())
        }
        teamsGroup?.let { group ->
            plugin(group, "Teams", "teams", Action.TEAMS_PLUGIN)
            setting(group, AccessCopy.TeamsStatus.text(), AccessCopy.TeamsStatusDetail.text() + " " + AccessCopy.TeamsDisclosure.text(),
                teams.enabled, Action.TEAMS)
            if (controls) setting(Feature.TEAMS_CONTROLS, AccessCopy.TeamsToolbarAccess.text(), TeamsToolbarDisclosure,
                accessibilityAllowed(TeamsToolbarService::class.java), Action.TOOLBAR, AccessCopy.AndroidAllowed.text(), AccessCopy.AndroidNotAllowed.text())
        }
    }

    fun perform(action: Action) {
        when (action) {
            Action.AUTO -> attach.automatic = true
            Action.DIRECT -> shortcuts.enabled = true
            Action.CAMERA -> camera.enabled = true
            Action.TEAMS -> { teams.enabled = true; TeamsEvents.refresh?.invoke() }
            Action.DETECTION -> session.setDetectForegroundApps(true)
            Action.AUDIO_PLUGIN -> session.setPluginEnabled("audio", true)
            Action.STUDIO_PLUGIN -> session.setPluginEnabled("studio", true)
            Action.NAV_PLUGIN -> session.setPluginEnabled("navigation", true)
            Action.TEAMS_PLUGIN -> session.setPluginEnabled("teams", true)
            Action.ONENOTE_PLUGIN -> session.setPluginEnabled("onenote", true)
            Action.MUSIC -> listener.music = true
            Action.NOTIFICATION_ACCESS -> listener.open(host)
            Action.APP_NOTICES -> host.startActivity(Intent(Settings.ACTION_APP_NOTIFICATION_SETTINGS).putExtra(Settings.EXTRA_APP_PACKAGE, host.packageName))
            Action.CHANNEL -> host.startActivity(Intent(Settings.ACTION_CHANNEL_NOTIFICATION_SETTINGS)
                .putExtra(Settings.EXTRA_APP_PACKAGE, host.packageName).putExtra(Settings.EXTRA_CHANNEL_ID, KeypadService.CHANNEL))
            Action.NAVIGATION, Action.TOOLBAR, Action.ONENOTE_ACCESS -> host.startActivity(DeckAccessActivity.accessibilitySettings())
            Action.OVERLAY -> host.startActivity(Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION, Uri.fromParts("package", host.packageName, null)))
            Action.USAGE -> host.startActivity(Intent(Settings.ACTION_USAGE_ACCESS_SETTINGS))
            Action.USB, Action.POST -> error("Runtime request belongs to the visible setup activity")
        }
    }
}
