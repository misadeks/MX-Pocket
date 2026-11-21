package tel.misa.mxpocket

import android.app.AppOpsManager
import android.app.usage.UsageEvents.Event
import android.app.usage.UsageStatsManager
import android.content.Context
import android.os.Build

internal const val FOREGROUND_APP = "foregroundApp"
internal const val APP_RULE_WEIGHT = 5 // Stock media scores 3; changing a positive incumbent needs a lead of 2.

@Suppress("DEPRECATION")
internal fun Context.hasUsageAccess(): Boolean {
    val ops = getSystemService(AppOpsManager::class.java)
    return try {
        val mode = if (Build.VERSION.SDK_INT >= 29)
            ops.unsafeCheckOpNoThrow(AppOpsManager.OPSTR_GET_USAGE_STATS, applicationInfo.uid, packageName)
        else ops.checkOpNoThrow(AppOpsManager.OPSTR_GET_USAGE_STATS, applicationInfo.uid, packageName)
        mode == AppOpsManager.MODE_ALLOWED
    } catch (_: SecurityException) { false }
}

/** One unlocked, granted observation interval. Keep only currently resumed activities, never a usage log. */
internal class ForegroundApps(private val usage: UsageStatsManager, private val since: Long) {
    private val resumed = mutableMapOf<Pair<String, String?>, Long>()
    private var lastPoll = since
    private var earliest = since

    @Suppress("DEPRECATION") // MOVE_TO_FOREGROUND/BACKGROUND are the API 28 names for RESUMED/PAUSED.
    fun match(configured: Set<String>, now: Long): String? {
        if (now < lastPoll || now - lastPoll > FRESH_MS) {
            resumed.clear()
            earliest = now
        }
        // Overlap adjacent reads: lifecycle events can arrive just after a poll. Replaying set/remove is idempotent.
        val from = maxOf(earliest, lastPoll - 5_000L).coerceAtMost(now)
        lastPoll = now
        val events = try { usage.queryEvents(from, now) } catch (_: SecurityException) { null }
        if (events == null) {
            resumed.clear()
            earliest = now
            return null
        }
        val event = Event()
        while (events.getNextEvent(event)) {
            when (event.eventType) {
                Event.MOVE_TO_FOREGROUND -> {
                    val pkg = event.packageName
                    if (pkg == null) {
                        resumed.clear()
                        return null
                    }
                    resumed[pkg to event.className] = event.timeStamp
                }
                Event.MOVE_TO_BACKGROUND, Event.ACTIVITY_STOPPED -> {
                    val pkg = event.packageName
                    if (event.className == null) resumed.keys.removeAll { it.first == pkg }
                    else resumed.remove(pkg to event.className)
                }
                Event.KEYGUARD_SHOWN, Event.KEYGUARD_HIDDEN, Event.SCREEN_NON_INTERACTIVE,
                Event.SCREEN_INTERACTIVE, Event.DEVICE_SHUTDOWN, Event.DEVICE_STARTUP -> resumed.clear()
            }
        }
        // A second resumed package (e.g. split screen) is not evidence of input focus.
        val pkg = resumed.keys.firstOrNull()?.first ?: return null
        if (pkg !in configured || resumed.keys.any { it.first != pkg }) return null
        return pkg.takeIf { resumed.values.any { now - it in 0..FRESH_MS } }
    }

    companion object { const val FRESH_MS = 10 * 60_000L }
}
