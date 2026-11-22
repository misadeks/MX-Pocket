package tel.misa.mxpocket

import android.app.Notification
import android.app.NotificationManager
import android.app.admin.DevicePolicyManager
import android.os.BadParcelableException
import android.os.Build
import android.os.Process
import android.provider.Settings
import android.service.notification.NotificationListenerService
import android.service.notification.StatusBarNotification
import android.telecom.TelecomManager
import androidx.annotation.RequiresApi
import kotlinx.coroutines.flow.update
import tel.misa.mxpocket.calls.DialerNotice
import tel.misa.mxpocket.calls.LineState
import tel.misa.mxpocket.calls.CallerDetails
import tel.misa.mxpocket.calls.embeddedCaller
import tel.misa.mxpocket.calls.dialerNotice
import tel.misa.mxpocket.calls.isFromDialer
import tel.misa.mxpocket.decks.TEAMS_PACKAGE
import tel.misa.mxpocket.decks.TeamsEvents
import tel.misa.mxpocket.decks.TeamsObservation
import tel.misa.mxpocket.decks.teamsObservation

class PocketNotificationListener : NotificationListenerService() {
    companion object {
        internal var current: PocketNotificationListener? = null
            private set
        internal var refreshPhone: (() -> Unit)? = null
        // Stamps from two service instances must not compare equal after a rebind.
        private var phoneBoundary = 0L
    }

    private val access by lazy { NotificationAccess(this) }
    internal var teamsBoundary = 0L
        private set
    internal var teamsRevision = 0L
        private set
    private var observedTeamsKey: String? = null
    private fun dialer() = getSystemService(TelecomManager::class.java)?.defaultDialerPackage

    override fun onListenerConnected() {
        current = this
        connectionChanged()
    }
    override fun onListenerDisconnected() = clear()
    override fun onDestroy() { clear(); super.onDestroy() }
    private fun clear() {
        if (current !== this) return
        current = null
        connectionChanged()
    }
    private fun connectionChanged() {
        forgetTeams()
        teamsBoundary++
        phoneBoundary++
        NotificationAccess.mediaChanges.update { it + 1 }
        if (access.teams) TeamsEvents.refresh?.invoke()
        if (access.dialer) refreshPhone?.invoke()
    }

    override fun onNotificationPosted(sbn: StatusBarNotification) = changed(sbn, removed = false)
    override fun onNotificationRemoved(sbn: StatusBarNotification) = changed(sbn, removed = true)
    override fun onNotificationRankingUpdate(rankingMap: RankingMap) {
        if (current === this && access.dialer && !isUnlockedAndInteractive()) refreshPhone?.invoke()
    }
    private fun changed(sbn: StatusBarNotification, removed: Boolean) {
        if (current !== this || Build.VERSION.SDK_INT < 34) return
        val teams = TeamsEvents.refresh?.takeIf { access.teams }
        val phone = refreshPhone?.takeIf { access.dialer }
        // Music only needs the component token. Do not even inspect the event in that mode.
        if (teams == null && phone == null) return
        if (sbn.user != Process.myUserHandle() || sbn.opPkg != sbn.packageName || !access.granted) return
        if (teams != null && sbn.packageName == TEAMS_PACKAGE) {
            if (sbn.key == observedTeamsKey) {
                // Even a same-key, same-millisecond replacement invalidates a painted mic key.
                teamsRevision++
                if (removed) teamsBoundary++
            }
            teams()
        }
        if (phone != null && sbn.isFromDialer(dialer(), packageManager)) {
            // The publisher may replace the very same key in the same millisecond.
            phoneBoundary++
            phone()
        }
    }

    internal fun forgetTeams() { observedTeamsKey = null }
    internal fun teamsSnapshot(): TeamsObservation {
        val observation = if (Build.VERSION.SDK_INT < 34 || current !== this || TeamsEvents.refresh == null ||
            !access.teams || !access.granted) TeamsObservation.Unavailable
        else try {
            activeNotifications?.let(::teamsObservation) ?: TeamsObservation.Unavailable
        } catch (_: SecurityException) { TeamsObservation.Unavailable }
        catch (_: BadParcelableException) { TeamsObservation.Unavailable }
        observedTeamsKey = when (observation) {
            is TeamsObservation.Notice -> observation.key
            is TeamsObservation.Incoming -> observation.key
            else -> null
        }
        return observation
    }

    internal fun teamsCaller(expected: TeamsObservation.Incoming): CallerDetails? {
        if (Build.VERSION.SDK_INT < 34 || !expected.allowsDetails || current !== this || TeamsEvents.refresh == null ||
            !access.teamsCaller || !access.granted || !isUnlockedAndInteractive()) return null
        return try {
            val notes = activeNotifications ?: return null
            if (teamsObservation(notes) != expected) return null
            notes.singleOrNull { it.key == expected.key }?.notification?.embeddedCaller()
        } catch (_: BadParcelableException) { null }
        catch (_: SecurityException) { null }
    }

    internal fun phoneSnapshot(state: LineState, expectedKey: String?): DialerNotice? {
        if (Build.VERSION.SDK_INT < 34 || current !== this || refreshPhone == null || !access.dialer || !access.granted) return null
        return try {
            val pkg = dialer() ?: return null
            val notes = activeNotifications ?: return null
            dialerNotice(notes, pkg, packageManager, state, phoneBoundary, !isUnlockedAndInteractive(), ::visibleOnLockScreen)
                ?.takeIf { expectedKey == null || it.stamp.key == expectedKey }
        } catch (_: SecurityException) { null }
        catch (_: BadParcelableException) { null }
    }

    @RequiresApi(34)
    private fun visibleOnLockScreen(sbn: StatusBarNotification): Boolean {
        val rank = Ranking()
        if (currentRanking?.getRanking(sbn.key, rank) != true || rank.isSuspended ||
            rank.importance <= NotificationManager.IMPORTANCE_MIN || !rank.matchesInterruptionFilter()) return false
        val channel = rank.channel ?: return false
        if (listOf(rank.lockscreenVisibilityOverride, channel.lockscreenVisibility).any {
                it != Ranking.VISIBILITY_NO_OVERRIDE && it != Notification.VISIBILITY_PUBLIC }) return false
        val policy = getSystemService(DevicePolicyManager::class.java)?.getKeyguardDisabledFeatures(null) ?: return false
        if (policy and DevicePolicyManager.KEYGUARD_DISABLE_SECURE_NOTIFICATIONS != 0 ||
            Settings.Secure.getInt(contentResolver, "lock_screen_show_notifications", 0) != 1) return false
        return when (sbn.notification.visibility) {
            Notification.VISIBILITY_PUBLIC -> true
            Notification.VISIBILITY_PRIVATE -> policy and DevicePolicyManager.KEYGUARD_DISABLE_UNREDACTED_NOTIFICATIONS == 0 &&
                Settings.Secure.getInt(contentResolver, "lock_screen_allow_private_notifications", 0) == 1
            else -> false
        }
    }
}
