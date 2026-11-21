package tel.misa.mxpocket.setup

import android.content.Context
import android.content.Intent
import tel.misa.mxpocket.shared.ui.SetupFeature as Feature

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
