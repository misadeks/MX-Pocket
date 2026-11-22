package tel.misa.mxpocket

import android.os.BadParcelableException
import android.os.Build
import android.os.Process
import android.service.notification.NotificationListenerService
import android.service.notification.StatusBarNotification
import android.telecom.TelecomManager
import kotlinx.coroutines.flow.update
import tel.misa.mxpocket.calls.DialerNotice
import tel.misa.mxpocket.calls.LineState
import tel.misa.mxpocket.calls.dialerNotice
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
    private fun changed(sbn: StatusBarNotification, removed: Boolean) {
        if (current !== this || Build.VERSION.SDK_INT < 34) return
        val teams = TeamsEvents.refresh?.takeIf { access.teams }
        val phone = refreshPhone?.takeIf { access.dialer }
        // Music only needs the component token. Do not even inspect the event in that mode.
        if (teams == null && phone == null) return
        if (sbn.user != Process.myUserHandle() || sbn.opPkg != sbn.packageName || !access.granted) return
        if (teams != null && sbn.packageName == TEAMS_PACKAGE) {
            if (removed && sbn.key == observedTeamsKey) teamsBoundary++
            teams()
        }
        if (phone != null && sbn.packageName == dialer()) {
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
        observedTeamsKey = (observation as? TeamsObservation.Ongoing)?.key
        return observation
    }

    internal fun phoneSnapshot(state: LineState, expectedKey: String?): DialerNotice? {
        if (Build.VERSION.SDK_INT < 34 || current !== this || refreshPhone == null || !access.dialer || !access.granted) return null
        return try {
            val pkg = dialer() ?: return null
            val notes = activeNotifications ?: return null
            dialerNotice(notes, pkg, state, phoneBoundary)?.takeIf { expectedKey == null || it.stamp.key == expectedKey }
        } catch (_: SecurityException) { null }
        catch (_: BadParcelableException) { null }
    }
}
