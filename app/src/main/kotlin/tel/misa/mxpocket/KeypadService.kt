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
import androidx.core.content.ContextCompat

class KeypadService : Service() {
    private val session get() = (application as PocketApplication).session
    private val usb by lazy { getSystemService(UsbManager::class.java) }
    private var owner: PocketSession.Request? = null
    private val detach = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            if (intent.action != UsbManager.ACTION_USB_DEVICE_DETACHED) return
            val name = intent.usbDevice()?.deviceName ?: return
            if (usb.deviceList.containsKey(name)) return
            UsbAccess(context).detached(name)
            if (session.request?.name == name) session.disconnect("Keypad detached.")
        }
    }

    override fun onCreate() {
        super.onCreate()
        ContextCompat.registerReceiver(this, detach, IntentFilter(UsbManager.ACTION_USB_DEVICE_DETACHED),
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
            stopForeground(STOP_FOREGROUND_REMOVE)
            stopSelf()
        }, handoff = { app ->
            getSystemService(NotificationManager::class.java).notify(NOTICE,
                notification(ticket, "Unlock and open MX Pocket, then tap the ${app.name} key."))
        })
        return START_NOT_STICKY
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
        unregisterReceiver(detach)
        if (owner != null && session.request == owner) session.disconnect("Keypad service stopped.")
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    companion object {
        private const val STOP = "tel.misa.mxpocket.STOP"
        private const val CHANNEL = "keypad-session"
        private const val NOTICE = 1
        internal fun command(context: Context, ticket: PocketSession.Request): Intent =
            Intent(context, KeypadService::class.java).setData(Uri.parse("mxpocket://session/${ticket.token}"))

        internal fun notificationsVisible(context: Context): Boolean {
            val manager = context.getSystemService(NotificationManager::class.java)
            manager.createNotificationChannel(NotificationChannel(CHANNEL, context.getString(R.string.session_title),
                NotificationManager.IMPORTANCE_LOW))
            return manager.areNotificationsEnabled() && manager.getNotificationChannel(CHANNEL).importance != NotificationManager.IMPORTANCE_NONE
        }
    }
}
