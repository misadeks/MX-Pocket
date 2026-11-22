package tel.misa.mxpocket

import tel.misa.mxpocket.ui.DeviceCopy as D
import tel.misa.mxpocket.ui.LanguagePreference

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.ServiceInfo
import android.hardware.usb.UsbManager
import android.net.Uri
import android.os.Build
import android.os.IBinder
import android.os.SystemClock
import androidx.core.content.ContextCompat
import androidx.compose.runtime.snapshotFlow
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import tel.misa.mxpocket.i18n.AppLocale
import tel.misa.mxpocket.decks.DeckResult
import tel.misa.mxpocket.decks.ok
import tel.misa.mxpocket.decks.unavailable
import tel.misa.mxpocket.plugins.PhoneApp
import tel.misa.mxpocket.plugins.displayName

class KeypadService : Service() {
    private val strings get() = LanguagePreference.resourcesFor(this)
    private val session get() = (application as PocketApplication).session
    private val usb by lazy { getSystemService(UsbManager::class.java) }
    private val notifications by lazy { getSystemService(NotificationManager::class.java) }
    private val serviceScope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private var owner: PocketSession.Request? = null
    private val sessionEvents = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            if (intent.action == Intent.ACTION_SCREEN_OFF || intent.action == Intent.ACTION_SCREEN_ON ||
                intent.action == Intent.ACTION_USER_PRESENT) {
                session.cancelPhysicalGesture()
                return
            }
            if (intent.action != UsbManager.ACTION_USB_DEVICE_DETACHED) return
            val name = intent.usbDevice()?.deviceName ?: return
            if (usb.deviceList.containsKey(name)) return
            UsbAccess(context).detached(name)
            if (session.request?.name == name) session.disconnect(D.Detached.text())
        }
    }

    override fun onCreate() {
        super.onCreate()
        // Legacy Android has no app-locale configuration event; refresh an ongoing notification
        // without restarting the service or claiming the keypad again.
        serviceScope.launch {
            snapshotFlow { AppLocale.current }.collect {
                owner?.let { ticket ->
                    if (notificationsVisible(this@KeypadService))
                        notifications.notify(NOTICE, notification(ticket, strings.getString(R.string.session_note)))
                }
            }
        }
        ContextCompat.registerReceiver(this, sessionEvents, IntentFilter(UsbManager.ACTION_USB_DEVICE_DETACHED).apply {
            addAction(Intent.ACTION_SCREEN_OFF); addAction(Intent.ACTION_SCREEN_ON); addAction(Intent.ACTION_USER_PRESENT)
        },
            ContextCompat.RECEIVER_NOT_EXPORTED)
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val ticket = session.request
        if (ticket == null || ticket.token != intent?.data?.lastPathSegment) {
            if (ticket == null) stopSelf(startId)
            return START_NOT_STICKY
        }
        if (intent.action == STOP) {
            session.disconnect()
            stopSelf(startId)
            return START_NOT_STICKY
        }
        if (owner == ticket) return START_NOT_STICKY
        val device = usb.keypad(ticket.name)
        if (device == null || !usb.hasPermission(device) || !notificationsVisible(this)) {
            session.disconnect(D.ServiceMissing.text())
            stopSelf(startId)
            return START_NOT_STICKY
        }
        try {
            val note = notification(ticket, strings.getString(R.string.session_note))
            if (Build.VERSION.SDK_INT >= 29)
                startForeground(NOTICE, note, ServiceInfo.FOREGROUND_SERVICE_TYPE_CONNECTED_DEVICE)
            else startForeground(NOTICE, note)
        } catch (e: RuntimeException) {
            // Includes a foreground-start restriction if the app lost visibility, or a USB grant race.
            session.disconnect()
            session.fail(D.ServiceDenied.text(), e)
            stopSelf(startId)
            return START_NOT_STICKY
        }
        owner = ticket
        session.connect(ticket, usb, device, end = {
            owner = null
            notifications.cancel(PHONE_NOTICE)
            stopForeground(STOP_FOREGROUND_REMOVE)
            stopSelf()
        }, handoff = ::offerShortcut)
        return START_NOT_STICKY
    }

    private fun offerShortcut(action: PocketSession.PhoneAction): DeckResult {
        if (owner != action.ticket || !hasKeypadAccess(action.ticket)) {
            session.disconnect(strings.getString(R.string.phone_action_stale))
            return unavailable(strings.getString(R.string.phone_action_stale))
        }
        val age = SystemClock.elapsedRealtime() - action.pressedAt
        if (!session.isCurrentShortcut(action) || age !in 0 until PocketSession.PHONE_ACTION_TTL)
            return unavailable(strings.getString(R.string.phone_action_stale))
        // Android provides no launch-success callback: retain a quiet tap-to-retry on OEM builds.
        val direct = age < 2_000 && DirectShortcuts(this).enabled && isUnlockedAndInteractive()
        val launch = if (direct) requestDirectLaunch(action.target) else null
        launch?.let { session.log(it.message) }
        notifications.createNotificationChannel(NotificationChannel(PHONE_CHANNEL,
            strings.getString(R.string.phone_action_title), NotificationManager.IMPORTANCE_HIGH))
        if (!notifications.areNotificationsEnabled() ||
            notifications.getNotificationChannel(PHONE_CHANNEL).importance == NotificationManager.IMPORTANCE_NONE) {
            session.takePhoneAction(action.id)
            if (launch?.success == true) return launch
            val message = (launch?.let { "${it.message} " } ?: "") +
                D.ShortcutNotifications.text()
            if (notificationsVisible(this)) notifications.notify(NOTICE, notification(action.ticket, message))
            return unavailable(message)
        }
        val open = Intent(this, PhoneActionActivity::class.java)
            .setData(Uri.parse("mxpocket://shortcut/${action.id}"))
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        val tap = PendingIntent.getActivity(this, 0, open, PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_ONE_SHOT)
        val label = when (val target = action.target) {
            PocketSession.Target.Teams -> "Teams"
            is PocketSession.Target.Phone -> when (target.app) {
                PhoneApp.CAMERA -> D.Camera.text()
                PhoneApp.SETTINGS -> D.Settings.text()
                PhoneApp.DIALER -> D.Dialer.text()
                PhoneApp.WEB -> target.app.displayName()
                PhoneApp.ALARMS -> D.Alarms.text()
                PhoneApp.ONENOTE -> "OneNote"
            }
            is PocketSession.Target.FavoriteApp -> D.Favorite.text()
            is PocketSession.Target.Studio -> if (target.mode == tel.misa.mxpocket.decks.CameraMode.PHOTO) D.Camera.text() else D.Video.text()
            is PocketSession.Target.Uri -> target.uri
            is PocketSession.Target.Share -> "OneNote"
        }
        val title = strings.getString(R.string.phone_action_open, label)
        val note = Notification.Builder(this, PHONE_CHANNEL)
            .apply { if (launch?.success == true) setGroup("shortcut-retry").setGroupAlertBehavior(Notification.GROUP_ALERT_SUMMARY) }
            .setSmallIcon(R.drawable.icon_foreground).setContentTitle(title)
            .setContentText(strings.getString(when (launch?.success) {
                true -> R.string.phone_action_retry
                false -> R.string.phone_action_failed
                null -> R.string.phone_action_note
            }))
            .setVisibility(Notification.VISIBILITY_PRIVATE)
            .setTimeoutAfter(PocketSession.PHONE_ACTION_TTL - age).setAutoCancel(true).setContentIntent(tap)
            .build()
        notifications.notify(PHONE_NOTICE, note)
        val tapMessage = D.TapNotification.text(title)
        return ok(when (launch?.success) {
            true -> D.DirectRequested.text(label)
            false -> "${launch.message} $tapMessage"
            null -> tapMessage
        })
    }

    private fun requestDirectLaunch(target: PocketSession.Target): DeckResult? {
        val eligible = when (target) {
            PocketSession.Target.Teams -> false // Existing direct-launch consent does not include Teams.
            is PocketSession.Target.Phone -> target.app == PhoneApp.CAMERA || session.isPhoneShortcutPage
            is PocketSession.Target.FavoriteApp, is PocketSession.Target.Studio -> true
            is PocketSession.Target.Uri, is PocketSession.Target.Share -> false
        }
        if (!eligible) return null
        return launchShortcut(target)?.let(::unavailable) ?: ok(D.LaunchRequested.text())
    }
    private fun notification(ticket: PocketSession.Request, text: String): Notification {
        val open = PendingIntent.getActivity(this, 0, Intent(this, MainActivity::class.java)
            .addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP), PendingIntent.FLAG_IMMUTABLE)
        val stop = PendingIntent.getService(this, 0, command(this, ticket).setAction(STOP), PendingIntent.FLAG_IMMUTABLE)
        return Notification.Builder(this, CHANNEL)
            .setSmallIcon(R.drawable.icon_foreground).setContentTitle(strings.getString(R.string.session_title))
            .setContentText(text).setStyle(Notification.BigTextStyle().bigText(text)).setContentIntent(open)
            .setOngoing(true).setOnlyAlertOnce(true).setCategory(Notification.CATEGORY_SERVICE)
            .addAction(Notification.Action.Builder(null, strings.getString(R.string.disconnect), stop).build())
            .apply { if (Build.VERSION.SDK_INT >= 31) setForegroundServiceBehavior(Notification.FOREGROUND_SERVICE_IMMEDIATE) }
            .build()
    }

    override fun onDestroy() {
        serviceScope.cancel()
        unregisterReceiver(sessionEvents)
        if (owner != null && session.request == owner) session.disconnect(D.Stopped.text())
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    companion object {
        private const val STOP = "tel.misa.mxpocket.STOP"
        internal const val CHANNEL = "keypad-session"
        private const val PHONE_CHANNEL = "keypad-shortcuts"
        private const val NOTICE = 1
        internal fun command(context: Context, ticket: PocketSession.Request): Intent =
            Intent(context, KeypadService::class.java).setData(Uri.parse("mxpocket://session/${ticket.token}"))

        internal fun notificationsVisible(context: Context): Boolean {
            val manager = context.getSystemService(NotificationManager::class.java)
            manager.createNotificationChannel(NotificationChannel(CHANNEL, LanguagePreference.resourcesFor(context).getString(R.string.session_title),
                NotificationManager.IMPORTANCE_LOW))
            return manager.areNotificationsEnabled() && !sessionChannelBlocked(manager)
        }

        internal fun sessionChannelBlocked(manager: NotificationManager) =
            manager.getNotificationChannel(CHANNEL)?.importance == NotificationManager.IMPORTANCE_NONE
    }
}
