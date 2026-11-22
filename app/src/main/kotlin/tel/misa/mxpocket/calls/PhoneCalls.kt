package tel.misa.mxpocket.calls

import android.Manifest
import android.app.Notification
import android.app.PendingIntent
import android.app.Person
import android.content.Context
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Rect
import android.graphics.Paint
import android.graphics.drawable.BitmapDrawable
import android.graphics.drawable.Icon
import android.os.Build
import android.os.Process
import android.service.notification.StatusBarNotification
import android.telephony.SubscriptionManager
import android.telephony.TelephonyManager
import androidx.annotation.RequiresApi
import tel.misa.mxpocket.NotificationAccess
import tel.misa.mxpocket.PocketNotificationListener

internal enum class LineState { UNKNOWN, IDLE, RINGING, OFF_HOOK, MULTIPLE }

internal class PhoneCallAccess(private val context: Context) {
    private val prefs = context.getSharedPreferences("phone-call-view", Context.MODE_PRIVATE)
    private val notifications = NotificationAccess(context)
    var enabled: Boolean
        get() = prefs.getBoolean("enabled", false)
        set(value) { prefs.edit().putBoolean("enabled", value).apply() }
    var details: Boolean
        get() = notifications.dialer
        set(value) { notifications.dialer = value }
    val supported get() = Build.VERSION.SDK_INT >= 31 && context.packageManager.hasSystemFeature(PackageManager.FEATURE_TELEPHONY)
    val phoneGranted get() = context.checkSelfPermission(Manifest.permission.READ_PHONE_STATE) == PackageManager.PERMISSION_GRANTED
    val notificationGranted get() = notifications.granted

    fun notice(state: LineState, expectedKey: String? = null) = if (enabled && details && notificationGranted)
        PocketNotificationListener.current?.phoneSnapshot(state, expectedKey) else null

    fun state(): LineState {
        if (!enabled || !phoneGranted || Build.VERSION.SDK_INT < 31) return LineState.UNKNOWN
        return try {
            val subs = context.getSystemService(SubscriptionManager::class.java)?.activeSubscriptionInfoList
            if (subs.isNullOrEmpty()) return LineState.UNKNOWN
            val telephony = context.getSystemService(TelephonyManager::class.java) ?: return LineState.UNKNOWN
            val active = subs.map { telephony.createForSubscriptionId(it.subscriptionId).callStateForSubscription }
                .filterNot { it == TelephonyManager.CALL_STATE_IDLE }
            when {
                active.isEmpty() -> LineState.IDLE
                active.size > 1 -> LineState.MULTIPLE
                active.single() == TelephonyManager.CALL_STATE_RINGING -> LineState.RINGING
                active.single() == TelephonyManager.CALL_STATE_OFFHOOK -> LineState.OFF_HOOK
                else -> LineState.UNKNOWN
            }
        } catch (_: SecurityException) { LineState.UNKNOWN }
        catch (_: UnsupportedOperationException) { LineState.UNKNOWN }
        catch (_: IllegalStateException) { LineState.UNKNOWN }
    }
}

/** These references never enter profile, workflow, diagnostic or preview state. */
internal data class CallStamp(val key: String, val posted: Long, val boundary: Long,
    val accept: PendingIntent?, val decline: PendingIntent?, val publisher: String)
internal class DialerNotice(val stamp: CallStamp, val name: String?, val icon: Icon?)

@RequiresApi(34)
internal fun dialerNotice(notes: Array<StatusBarNotification>, dialer: String, state: LineState, boundary: Long): DialerNotice? {
    val sbn = notes.filter {
        it.packageName == dialer && it.user == Process.myUserHandle() && it.opPkg == dialer && it.notification.let { n ->
            n.category == Notification.CATEGORY_CALL || n.extras.getString(Notification.EXTRA_TEMPLATE) == Notification.CallStyle::class.java.name
        }
    }.singleOrNull() ?: return null
    val extras = sbn.notification.extras
    if (extras.getString(Notification.EXTRA_TEMPLATE) != Notification.CallStyle::class.java.name) return null
    val ringing = state == LineState.RINGING
    val type = extras.getInt(Notification.EXTRA_CALL_TYPE)
    if (!(ringing && type == Notification.CallStyle.CALL_TYPE_INCOMING ||
            state == LineState.OFF_HOOK && type == Notification.CallStyle.CALL_TYPE_ONGOING)) return null
    fun action(field: String) = if (!ringing) null else extras.getParcelable(field, PendingIntent::class.java)
        ?.takeIf { it.creatorPackage == dialer && it.creatorUid == sbn.uid }
    val accept = action(Notification.EXTRA_ANSWER_INTENT)
    val decline = action(Notification.EXTRA_DECLINE_INTENT)
    val person = extras.getParcelable(Notification.EXTRA_CALL_PERSON, Person::class.java)
    // Only the dialer's display label; never infer identity from a phone URI, People or contacts.
    val name = person?.name?.take(128)?.toString()?.filterNot { it.isISOControl() }?.trim()?.takeIf { it.isNotEmpty() }
    val controls = accept != null && decline != null && accept != decline
    return DialerNotice(CallStamp(sbn.key, sbn.postTime, boundary, accept.takeIf { controls },
        decline.takeIf { controls }, dialer), name, person?.icon?.takeIf {
        name != null && it.type in setOf(Icon.TYPE_BITMAP, Icon.TYPE_ADAPTIVE_BITMAP)
    })
}

internal fun DialerNotice.photo(context: Context): Bitmap? {
    val drawable = icon?.loadDrawable(context) ?: return null
    // Embedded hardware bitmaps cannot be drawn to the LCD's software canvas.
    val source = (drawable as? BitmapDrawable)?.bitmap
    if (source?.isRecycled == true) return null
    val bitmap = if (source?.config == Bitmap.Config.HARDWARE) {
        if (source.width.toLong() * source.height > 4 * 1024 * 1024) return null
        source.copy(Bitmap.Config.ARGB_8888, false) ?: return null
    } else source
    return Bitmap.createBitmap(118, 118, Bitmap.Config.ARGB_8888).also {
        val canvas = Canvas(it)
        if (bitmap != null) {
            val side = minOf(bitmap.width, bitmap.height)
            val x = (bitmap.width - side) / 2
            val y = (bitmap.height - side) / 2
            canvas.drawBitmap(bitmap, Rect(x, y, x + side, y + side), Rect(0, 0, it.width, it.height), Paint(Paint.FILTER_BITMAP_FLAG))
        } else {
            drawable.setBounds(0, 0, it.width, it.height)
            try { drawable.draw(canvas) } catch (_: IllegalArgumentException) { return null }
        }
    }
}
