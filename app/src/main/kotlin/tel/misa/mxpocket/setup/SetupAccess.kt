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
import tel.misa.mxpocket.UsbAccess
import tel.misa.mxpocket.decks.CameraControlsAccess
import tel.misa.mxpocket.decks.DeckAccessActivity
import tel.misa.mxpocket.decks.ListenerAccess
import tel.misa.mxpocket.decks.NavigationService
import tel.misa.mxpocket.decks.TeamsAccess
import tel.misa.mxpocket.decks.TeamsEvents
import tel.misa.mxpocket.decks.TeamsToolbarService
import tel.misa.mxpocket.decks.hasMediaSessionAccess
import tel.misa.mxpocket.driver.UsbDiagnostics
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
                "Call status needs Android 14 or later. You can still open Call tools manually."
            feature == Feature.TEAMS_CONTROLS && !teams.singleProfile ->
                "With multiple Android profiles, only read-only Teams call notices are available."
            else -> null
        })
    }

    fun existingFeatures() = buildSet {
        if (attach.automatic) add(Feature.AUTO)
        if (host.hasMediaSessionAccess()) add(Feature.MUSIC)
        if (camera.enabled) add(Feature.STUDIO)
        if (shortcuts.optedIn) add(Feature.DIRECT)
        if (accessibilityAllowed(NavigationService::class.java)) add(Feature.NAVIGATION)
        if (session.detectForegroundApps.value) add(Feature.PROFILES)
        if (teams.granted && teams.enabled) add(Feature.TEAMS)
        if (teams.granted && teams.enabled && accessibilityAllowed(TeamsToolbarService::class.java)) add(Feature.TEAMS_CONTROLS)
    }

    fun steps(features: Set<Feature>, pendingUsb: String? = null): List<SetupStep> = buildList {
        fun setting(feature: Feature, title: String, detail: String, ready: Boolean, action: Action,
                    on: String = "On in MX Pocket", off: String = "Off in MX Pocket", actionable: Boolean = true) {
            add(SetupStep(feature, title, detail, if (ready) on else off, ready, action.takeIf { !ready && actionable }))
        }
        fun plugin(feature: Feature, name: String, id: String, action: Action) {
            setting(feature, "$name plugin", "", id !in session.disabledPlugins.value, action)
        }

        val devices = keypads()
        val device = devices.singleOrNull()
        val allowed = device != null && usb.hasPermission(device)
        val waiting = device != null && device.deviceName == pendingUsb
        add(SetupStep(Feature.USB, "Physical keypad", "Use a USB data cable. Approval applies only to this attachment.", when {
            devices.isEmpty() -> "No supported keypad attached"
            device == null -> "Attach only one keypad to check access"
            allowed -> "This keypad is authorized"
            waiting -> "Waiting for USB approval"
            else -> "This keypad needs approval"
        }, allowed, Action.USB.takeIf { device != null && !allowed && !waiting }, applicable = device != null))

        val post = Manifest.permission.POST_NOTIFICATIONS
        val notice = when {
            Build.VERSION.SDK_INT >= 33 && host.checkSelfPermission(post) != PackageManager.PERMISSION_GRANTED ->
                if (choices.asked(post) && !host.shouldShowRequestPermissionRationale(post)) Action.APP_NOTICES else Action.POST
            !notifications.areNotificationsEnabled() -> Action.APP_NOTICES
            else -> null
        }
        add(SetupStep(Feature.USB, "Session notification",
            "Show a notification with a Disconnect button while the keypad is connected. This does not allow reading notifications.",
            when (notice) {
                null -> "Notifications allowed"
                Action.POST -> "Notification permission is off"
                else -> "App notifications are off"
            }, notice == null, notice))
        if (notifications.getNotificationChannel(KeypadService.CHANNEL) != null) {
            setting(Feature.USB, "Keypad session channel", "Android can turn off this notification channel separately.",
                !KeypadService.sessionChannelBlocked(notifications), Action.CHANNEL, "Channel on", "Keypad session channel is off")
        }

        if (Feature.AUTO in features) setting(Feature.AUTO, "USB plug-in handling",
            "Let Android offer MX Pocket when the keypad is plugged in. Replug to choose a handler, or connect manually from Home.",
            attach.automatic, Action.AUTO)

        if (Feature.MUSIC in features) {
            plugin(Feature.MUSIC, "Audio", "audio", Action.AUDIO_PLUGIN)
            setting(Feature.MUSIC, "Media session access", DeckAccess.Media.disclosure + " " + MediaListenerHint,
                host.hasMediaSessionAccess(), Action.MEDIA, "Media session access allowed", "Basic media keys only")
        }
        if (Feature.STUDIO in features) {
            plugin(Feature.STUDIO, "Studio", "studio", Action.STUDIO_PLUGIN)
            setting(Feature.STUDIO, "Keypad camera controls", CameraControlsDisclosure, camera.enabled, Action.CAMERA)
        }
        if (Feature.NAVIGATION in features) plugin(Feature.NAVIGATION, "Navigation", "navigation", Action.NAV_PLUGIN)
        if (Feature.NAVIGATION in features || Feature.STUDIO in features) {
            val group = if (Feature.NAVIGATION in features) Feature.NAVIGATION else Feature.STUDIO
            setting(group, "Navigation accessibility access", DeckAccess.Navigation.disclosure + " Select MX Pocket navigation in Accessibility.",
                accessibilityAllowed(NavigationService::class.java), Action.NAVIGATION, "Allowed in Android", "Not allowed in Android")
        }
        if (Feature.DIRECT in features) {
            val overlay = Settings.canDrawOverlays(host)
            setting(Feature.DIRECT, "Display over other apps", DirectShortcutDisclosure, overlay, Action.OVERLAY,
                "Allowed in Android", "Not allowed in Android")
            setting(Feature.DIRECT, "One-press shortcuts", "Without this option, tap a notification to open the shortcut.",
                shortcuts.optedIn, Action.DIRECT, "One-press shortcuts on",
                if (overlay) "Off in MX Pocket" else "Allow display over other apps before enabling shortcuts", actionable = overlay)
        }
        if (Feature.PROFILES in features) {
            setting(Feature.PROFILES, "Usage access", "Let MX Pocket check for mapped apps while the phone is unlocked. No usage history is saved or sent.",
                host.hasUsageAccess(), Action.USAGE, "Allowed in Android", "Not allowed in Android")
            setting(Feature.PROFILES, "Detect foreground apps", "Use the app on screen to find a matching profile.",
                session.detectForegroundApps.value, Action.DETECTION)
            setting(Feature.PROFILES, "Automatic profile switching", "Allow matching rules to select a layout. Manage app mappings, pins and individual profiles in Profiles.",
                session.deck.state.value.automatic, Action.SWITCHING)
        }
        val controls = Feature.TEAMS_CONTROLS in features && teams.singleProfile
        if (Build.VERSION.SDK_INT >= 34 && (Feature.TEAMS in features || controls)) {
            val group = if (Feature.TEAMS in features) Feature.TEAMS else Feature.TEAMS_CONTROLS
            plugin(group, "Teams", "teams", Action.TEAMS_PLUGIN)
            setting(group, "Teams call status", "Show call notices from Teams in this Android profile while a wired keypad is connected.",
                teams.enabled, Action.TEAMS)
            setting(group, "Teams notification access", "$TeamsListenerDisclosure $TeamsListenerHint",
                teams.granted, Action.TEAMS_NOTICES, "Allowed in Android", "Not allowed in Android")
            if (controls) setting(Feature.TEAMS_CONTROLS, "Teams toolbar access", TeamsToolbarDisclosure,
                accessibilityAllowed(TeamsToolbarService::class.java), Action.TOOLBAR, "Allowed in Android", "Not allowed in Android")
        }
    }

    fun perform(action: Action) {
        when (action) {
            Action.AUTO -> attach.automatic = true
            Action.DIRECT -> shortcuts.enabled = true
            Action.CAMERA -> camera.enabled = true
            Action.TEAMS -> { teams.enabled = true; TeamsEvents.refresh?.invoke() }
            Action.SWITCHING -> session.deck.setAutomatic(true).getOrThrow()
            Action.DETECTION -> session.setDetectForegroundApps(true)
            Action.AUDIO_PLUGIN -> session.setPluginEnabled("audio", true)
            Action.STUDIO_PLUGIN -> session.setPluginEnabled("studio", true)
            Action.NAV_PLUGIN -> session.setPluginEnabled("navigation", true)
            Action.TEAMS_PLUGIN -> session.setPluginEnabled("teams", true)
            Action.MEDIA -> ListenerAccess.MEDIA.open(host)
            Action.TEAMS_NOTICES -> ListenerAccess.TEAMS.open(host)
            Action.APP_NOTICES -> host.startActivity(Intent(Settings.ACTION_APP_NOTIFICATION_SETTINGS).putExtra(Settings.EXTRA_APP_PACKAGE, host.packageName))
            Action.CHANNEL -> host.startActivity(Intent(Settings.ACTION_CHANNEL_NOTIFICATION_SETTINGS)
                .putExtra(Settings.EXTRA_APP_PACKAGE, host.packageName).putExtra(Settings.EXTRA_CHANNEL_ID, KeypadService.CHANNEL))
            Action.NAVIGATION, Action.TOOLBAR -> host.startActivity(DeckAccessActivity.accessibilitySettings())
            Action.OVERLAY -> host.startActivity(Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION, Uri.fromParts("package", host.packageName, null)))
            Action.USAGE -> host.startActivity(Intent(Settings.ACTION_USAGE_ACCESS_SETTINGS))
            Action.USB, Action.POST -> error("Runtime request belongs to the visible setup activity")
        }
    }
}
