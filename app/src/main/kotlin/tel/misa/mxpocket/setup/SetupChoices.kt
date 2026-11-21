package tel.misa.mxpocket.setup

import android.content.Context
import android.content.Intent

internal enum class Feature(val title: String, val summary: String) {
    USB("Use a USB keypad", "Keep the keys active when MX Pocket is in the background."),
    AUTO("Connect on plug-in", "Let Android offer MX Pocket when you attach the keypad."),
    MUSIC("Show player controls and artwork", "Use track details and controls supplied by the active player."),
    STUDIO("Control the phone camera", "Open the native viewfinder. Optionally use the physical Studio keys for shutter, record and stop."),
    DIRECT("Open apps with one press", "Open a supported shortcut from your physical keypad without a notification tap."),
    NAVIGATION("Navigate other apps", "Use keypad buttons for Back, Home, scrolling and focus.")
}

/** Feature choices describe the guide, not Android grants or plugin switches. */
internal class SetupChoices(private val context: Context) {
    private val prefs = context.getSharedPreferences("feature-setup", Context.MODE_PRIVATE)

    fun takeLauncherWelcome(intent: Intent): Boolean {
        if (intent.action != Intent.ACTION_MAIN || !intent.hasCategory(Intent.CATEGORY_LAUNCHER) ||
            intent.data != null || intent.hasExtra(Intent.EXTRA_SHORTCUT_ID) ||
            intent.flags and Intent.FLAG_ACTIVITY_LAUNCHED_FROM_HISTORY != 0 || prefs.getBoolean("seen", false)) return false
        prefs.edit().putBoolean("seen", true).apply()
        @Suppress("DEPRECATION")
        val info = context.packageManager.getPackageInfo(context.packageName, 0)
        return info.firstInstallTime == info.lastUpdateTime
    }

    fun load(initial: () -> Set<Feature>): Set<Feature> =
        prefs.getStringSet("features", null)?.let(::decode) ?: initial()

    fun save(features: Set<Feature>) {
        prefs.edit().putStringSet("features", features.map { it.name }.toSet()).putBoolean("seen", true).apply()
    }

    fun asked(permission: String) = prefs.getBoolean(permission, false)
    fun markAsked(permission: String) { prefs.edit().putBoolean(permission, true).apply() }

    companion object {
        fun decode(names: Collection<String>) = Feature.entries.filterTo(mutableSetOf()) { it.name in names }
    }
}
