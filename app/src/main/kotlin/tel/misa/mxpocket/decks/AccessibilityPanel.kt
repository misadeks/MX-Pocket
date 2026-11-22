package tel.misa.mxpocket.decks

import android.content.ActivityNotFoundException
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.provider.Settings

/** TalkBack has no public service-specific Settings intent. Never guess an OEM component. */
internal enum class AccessibilityPanel(val command: DeckCommand, private vararg val routes: String) {
    TALKBACK(DeckCommand.TALKBACK_SETTINGS, Settings.ACTION_ACCESSIBILITY_SETTINGS),
    CAPTIONS(DeckCommand.CAPTION_SETTINGS, Settings.ACTION_CAPTIONING_SETTINGS, Settings.ACTION_ACCESSIBILITY_SETTINGS),
    NIGHT_LIGHT(DeckCommand.NIGHT_LIGHT_SETTINGS, Settings.ACTION_NIGHT_DISPLAY_SETTINGS, Settings.ACTION_DISPLAY_SETTINGS);

    enum class Result { OPENED, UNAVAILABLE, RESTRICTED }

    @Suppress("DEPRECATION")
    fun open(context: Context): Result {
        for (route in routes.asList() + Settings.ACTION_SETTINGS) {
            val intent = Intent(route).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            try {
                val handler = context.packageManager.queryIntentActivities(intent,
                    PackageManager.MATCH_DEFAULT_ONLY or PackageManager.MATCH_SYSTEM_ONLY)
                    .firstOrNull { it.activityInfo.exported && it.activityInfo.enabled }?.activityInfo ?: continue
                context.startActivity(intent.setComponent(ComponentName(handler.packageName, handler.name)))
                return Result.OPENED
            } catch (_: ActivityNotFoundException) { /* OEM panel removed or disabled after resolution. */ }
            catch (_: SecurityException) { return Result.RESTRICTED }
        }
        return Result.UNAVAILABLE
    }

    companion object {
        fun forCommand(command: DeckCommand) = entries.find { it.command == command }
    }
}
