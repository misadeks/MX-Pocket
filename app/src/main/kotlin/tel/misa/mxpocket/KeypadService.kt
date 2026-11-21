package tel.misa.mxpocket

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
import tel.misa.mxpocket.decks.DeckResult
import tel.misa.mxpocket.decks.ok
import tel.misa.mxpocket.decks.unavailable
import tel.misa.mxpocket.plugins.PhoneApp

class KeypadService : Service() {
    private val session get() = (application as PocketApplication).session
    private val usb by lazy { getSystemService(UsbManager::class.java) }
    private val notifications by lazy { getSystemService(NotificationManager::class.java) }
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
            if (session.request?.name == name) session.disconnect("Keypad detached.")
        }
    }

    override fun onCreate() {
        super.onCreate()
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
            session.disconnect("Keypad unavailable, or USB/notification permission missing. Tap Connect to retry.")
            stopSelf(startId)
            return START_NOT_STICKY
        }
        try {
            val note = notification(ticket, getString(R.string.session_note))
            if (Build.VERSION.SDK_INT >= 29)
                startForeground(NOTICE, note, ServiceInfo.FOREGROUND_SERVICE_TYPE_CONNECTED_DEVICE)
            else startForeground(NOTICE, note)
        } catch (e: RuntimeException) {
            // Includes a foreground-start restriction if the app lost visibility, or a USB grant race.
            session.disconnect()
            session.fail("Android did not allow the keypad foreground service", e)
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
            session.disconnect(getString(R.string.phone_action_stale))
            return unavailable(getString(R.string.phone_action_stale))
        }
        val age = SystemClock.elapsedRealtime() - action.pressedAt
        if (!session.isCurrentShortcut(action) || age !in 0 until PocketSession.PHONE_ACTION_TTL)
            return unavailable(getString(R.string.phone_action_stale))
        // Android provides no launch-success callback: retain a quiet tap-to-retry on OEM builds.
        val direct = age < 2_000 && DirectShortcuts(this).enabled && isUnlockedAndInteractive()
        val launch = if (direct) requestDirectLaunch(action.target) else null
        launch?.let { session.log(it.message) }
        notifications.createNotificationChannel(NotificationChannel(PHONE_CHANNEL,
            getString(R.string.phone_action_title), NotificationManager.IMPORTANCE_HIGH))
        if (!notifications.areNotificationsEnabled() ||
            notifications.getNotificationChannel(PHONE_CHANNEL).importance == NotificationManager.IMPORTANCE_NONE) {
            session.takePhoneAction(action.id)
            if (launch?.success == true) return launch
            val message = (launch?.let { "${it.message} " } ?: "") +
                "Enable Keypad app shortcuts notifications in Android settings to open this shortcut."
            if (notificationsVisible(this)) notifications.notify(NOTICE, notification(action.ticket, message))
            return unavailable(message)
        }
        val open = Intent(this, PhoneActionActivity::class.java)
            .setData(Uri.parse("mxpocket://shortcut/${action.id}"))
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        val tap = PendingIntent.getActivity(this, 0, open, PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_ONE_SHOT)
        val label = when (val target = action.target) {
            is PocketSession.Target.Phone -> target.app.name.lowercase().replaceFirstChar(Char::uppercaseChar)
            is PocketSession.Target.FavoriteApp -> "favorite app"
            PocketSession.Target.Studio -> "Pocket Studio"
        }
        val title = getString(R.string.phone_action_open, label)
        val note = Notification.Builder(this, PHONE_CHANNEL)
            .apply { if (launch?.success == true) setGroup("shortcut-retry").setGroupAlertBehavior(Notification.GROUP_ALERT_SUMMARY) }
            .setSmallIcon(R.drawable.icon_foreground).setContentTitle(title)
            .setContentText(getString(when (launch?.success) {
                true -> R.string.phone_action_retry
                false -> R.string.phone_action_failed
                null -> R.string.phone_action_note
            }))
            .setVisibility(Notification.VISIBILITY_PRIVATE)
            .setTimeoutAfter(PocketSession.PHONE_ACTION_TTL - age).setAutoCancel(true).setContentIntent(tap)
            .build()
        notifications.notify(PHONE_NOTICE, note)
        val tapMessage = "Tap the \"$title\" notification within one minute."
        return ok(when (launch?.success) {
            true -> "Direct launch requested: $label. Tap the notification only if it did not open."
            false -> "${launch.message} $tapMessage"
            null -> tapMessage
        })
    }

    private fun requestDirectLaunch(target: PocketSession.Target): DeckResult? {
        val eligible = when (target) {
            is PocketSession.Target.Phone -> target.app == PhoneApp.CAMERA || session.isPhoneShortcutPage
            is PocketSession.Target.FavoriteApp, PocketSession.Target.Studio -> true
        }
        if (!eligible) return null
        return launchShortcut(target)?.let(::unavailable) ?: ok("Direct launch requested.")
    }
    private fun notification(ticket: PocketSession.Request, text: String): Notification {
        val open = PendingIntent.getActivity(this, 0, Intent(this, MainActivity::class.java)
            .addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP), PendingIntent.FLAG_IMMUTABLE)
        val stop = PendingIntent.getService(this, 0, command(this, ticket).setAction(STOP), PendingIntent.FLAG_IMMUTABLE)
        return Notification.Builder(this, CHANNEL)
            .setSmallIcon(R.drawable.icon_foreground).setContentTitle(getString(R.string.session_title))
            .setContentText(text).setStyle(Notification.BigTextStyle().bigText(text)).setContentIntent(open)
            .setOngoing(true).setOnlyAlertOnce(true).setCategory(Notification.CATEGORY_SERVICE)
            .addAction(Notification.Action.Builder(null, getString(R.string.disconnect), stop).build())
            .apply { if (Build.VERSION.SDK_INT >= 31) setForegroundServiceBehavior(Notification.FOREGROUND_SERVICE_IMMEDIATE) }
            .build()
    }

    override fun onDestroy() {
        unregisterReceiver(sessionEvents)
        if (owner != null && session.request == owner) session.disconnect("Keypad service stopped.")
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
            manager.createNotificationChannel(NotificationChannel(CHANNEL, context.getString(R.string.session_title),
                NotificationManager.IMPORTANCE_LOW))
            return manager.areNotificationsEnabled() && !sessionChannelBlocked(manager)
        }

        internal fun sessionChannelBlocked(manager: NotificationManager) =
            manager.getNotificationChannel(CHANNEL)?.importance == NotificationManager.IMPORTANCE_NONE
    }
}
