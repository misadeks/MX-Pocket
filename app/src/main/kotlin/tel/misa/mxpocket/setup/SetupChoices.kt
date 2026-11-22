package tel.misa.mxpocket.setup

import android.content.Context
import android.content.Intent
import java.io.File
import tel.misa.mxpocket.shared.ui.SetupFeature as Feature

/** Feature choices describe the guide, not Android grants or plugin switches. */
internal class SetupChoices(private val context: Context) {
    private val prefs = context.getSharedPreferences("feature-setup", Context.MODE_PRIVATE)

    fun takeLauncherWelcome(intent: Intent): Boolean {
        if (intent.action != Intent.ACTION_MAIN || !intent.hasCategory(Intent.CATEGORY_LAUNCHER) ||
            intent.data != null || intent.hasExtra(Intent.EXTRA_SHORTCUT_ID) ||
            intent.flags and Intent.FLAG_ACTIVITY_LAUNCHED_FROM_HISTORY != 0) return false
        // Never consult the old, transferable "seen" preference. The OS removes this file on
        // uninstall and excludes it from backup and device transfer, but keeps it on update.
        val seen = File(context.noBackupFilesDir, "feature-setup-shown")
        if (seen.exists()) return false
        @Suppress("DEPRECATION")
        val info = context.packageManager.getPackageInfo(context.packageName, 0)
        return info.firstInstallTime == info.lastUpdateTime && seen.createNewFile()
    }

    fun load(initial: () -> Set<Feature>): Set<Feature> =
        prefs.getStringSet("features", null)?.let(::decode) ?: initial()

    fun save(features: Set<Feature>) {
        prefs.edit().putStringSet("features", features.map { it.name }.toSet()).apply()
    }

    // A restored denial must not send a new installation straight to Settings.
    fun asked(permission: String) = File(context.noBackupFilesDir, "asked-$permission").exists()
    fun markAsked(permission: String) { File(context.noBackupFilesDir, "asked-$permission").createNewFile() }

    companion object {
        fun decode(names: Collection<String>) = Feature.entries.filterTo(mutableSetOf()) { it.name in names }
    }
}
