package tel.misa.mxpocket

import android.app.AppOpsManager
import android.app.usage.UsageEvents.Event
import android.app.usage.UsageStatsManager
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.Build
import androidx.core.content.ContextCompat

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

internal enum class ForegroundBoundary { SCREEN, UNLOCK, ACCESS }

/** Registered only for a live, eligible mapping. Deliver all invalidations on the session thread. */
internal fun Context.watchForegroundBoundaries(reset: (ForegroundBoundary) -> Unit): AutoCloseable {
    var active = true
    val receiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            if (active) reset(if (intent.action == Intent.ACTION_USER_PRESENT) ForegroundBoundary.UNLOCK else ForegroundBoundary.SCREEN)
        }
    }
    val ops = getSystemService(AppOpsManager::class.java)
    val listener = AppOpsManager.OnOpChangedListener { _, _ ->
        // Even a revoke/regrant wholly between two polls starts a new permission interval.
        mainExecutor.execute { if (active) reset(ForegroundBoundary.ACCESS) }
    }
    ops.startWatchingMode(AppOpsManager.OPSTR_GET_USAGE_STATS, packageName, listener)
    try {
        ContextCompat.registerReceiver(this, receiver, IntentFilter().apply {
            addAction(Intent.ACTION_SCREEN_OFF); addAction(Intent.ACTION_SCREEN_ON); addAction(Intent.ACTION_USER_PRESENT)
        }, ContextCompat.RECEIVER_NOT_EXPORTED)
    } catch (e: RuntimeException) {
        ops.stopWatchingMode(listener)
        throw e
    }
    return AutoCloseable {
        active = false
        ops.stopWatchingMode(listener)
        unregisterReceiver(receiver)
    }
}

/** Only an uninterrupted grant can recover events between a locked poll and the first unlocked one. */
internal class ForegroundApps(private val usage: UsageStatsManager, since: Long, lastLockedPoll: Long? = null) {
    private val resumed = mutableMapOf<Pair<String, String?>, Long>()
    private var lastPoll = since
    private var earliest = lastLockedPoll?.coerceIn((since - 5_000L)..since) ?: since
    private val firstUnlockedPoll = since
    private val freshInterval = lastLockedPoll == null

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
        var pastUnlock = false
        while (events.getNextEvent(event)) {
            // Usage timestamps have millisecond precision: a resume in the same millisecond
            // as a new consent interval cannot establish that the app resumed after the grant.
            if (freshInterval && event.timeStamp == firstUnlockedPoll && event.eventType == Event.MOVE_TO_FOREGROUND) continue
            // Before the first unlocked sample, require Android's unlock boundary. An activity
            // can resume over the lock screen, so a resume (or screen-on) alone is not enough.
            if (event.timeStamp < firstUnlockedPoll) {
                when (event.eventType) {
                    Event.KEYGUARD_HIDDEN -> pastUnlock = true
                    Event.KEYGUARD_SHOWN, Event.SCREEN_NON_INTERACTIVE,
                    Event.DEVICE_SHUTDOWN, Event.DEVICE_STARTUP -> pastUnlock = false
                }
                if (!pastUnlock && event.eventType == Event.MOVE_TO_FOREGROUND) continue
            }
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
