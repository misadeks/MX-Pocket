package tel.misa.mxpocket.calls

import android.Manifest
import android.app.Notification
import android.app.PendingIntent
import android.app.Person
import android.content.Context
import android.content.pm.ApplicationInfo
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
import android.telecom.TelecomManager
import android.telephony.SubscriptionManager
import android.telephony.TelephonyManager
import androidx.annotation.RequiresApi
import tel.misa.mxpocket.NotificationAccess
import tel.misa.mxpocket.PocketNotificationListener

internal enum class LineState { UNKNOWN, IDLE, RINGING, OFF_HOOK, MULTIPLE, CALL }

/** Only these fixed codes, never a notice, may enter session diagnostics. */
internal enum class RingReason {
    LISTENER_UNAVAILABLE, NO_DEFAULT_DIALER, NO_TRUSTED_CALL, AMBIGUOUS_CALL, HIDDEN,
    NO_CALL_STYLE, NO_CALL_STYLE_WITH_CALL_PERSON, CALL_TYPE_MISMATCH, STALE_NOTICE, NO_CALLER_LABEL, EARLY_LABEL_ONLY, LABEL_AVAILABLE
}
internal data class NoticeRead(val reason: RingReason, val notice: DialerNotice? = null)

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

    fun notice(state: LineState, expectedKey: String?): NoticeRead {
        if (enabled && details && notificationGranted)
            PocketNotificationListener.current?.let { return it.phoneSnapshot(state, expectedKey) }
        return NoticeRead(RingReason.LISTENER_UNAVAILABLE)
    }

    fun state(): LineState {
        if (!enabled || !phoneGranted || Build.VERSION.SDK_INT < 31) return LineState.UNKNOWN
        val carrier = carrierState()
        if (carrier != LineState.IDLE && carrier != LineState.UNKNOWN) return carrier
        // Telecom can include self-managed calls. It does not identify their source or direction.
        val inCall = try { context.getSystemService(TelecomManager::class.java)?.isInCall }
            catch (_: SecurityException) { null }
            catch (_: IllegalStateException) { null }
            catch (_: UnsupportedOperationException) { null }
        return when (inCall) {
            true -> LineState.CALL
            false -> LineState.IDLE
            null -> LineState.UNKNOWN // A failed Telecom read is not proof the whole phone is idle.
        }
    }

    @RequiresApi(31)
    private fun carrierState(): LineState {
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
    val accept: PendingIntent?, val decline: PendingIntent?, val publisher: String, val restricted: Boolean = false)
internal class DialerNotice(val stamp: CallStamp, val name: String?, val icon: Icon?)

@RequiresApi(34)
internal fun StatusBarNotification.isFromDialer(dialer: String?, packages: PackageManager): Boolean {
    if (user != Process.myUserHandle() || opPkg != packageName) return false
    if (packageName == dialer) return true
    if (dialer != "com.samsung.android.dialer" || packageName != "com.samsung.android.incallui") return false

    // Samsung Phone posts calls from a separate system app, not on behalf of the dialer.
    val flags = PackageManager.ApplicationInfoFlags.of(0)
    val system = ApplicationInfo.FLAG_SYSTEM or ApplicationInfo.FLAG_UPDATED_SYSTEM_APP
    return try {
        val publisher = packages.getApplicationInfo(packageName, flags)
        publisher.uid == uid && publisher.flags and system != 0 &&
            packages.getApplicationInfo(dialer, flags).flags and system != 0 &&
            packages.checkSignatures(dialer, packageName) == PackageManager.SIGNATURE_MATCH
    } catch (_: PackageManager.NameNotFoundException) { false }
    catch (_: SecurityException) { false }
}

@RequiresApi(34)
internal fun dialerNotice(notes: Array<StatusBarNotification>, dialer: String, packages: PackageManager, state: LineState, boundary: Long,
    restricted: Boolean = false, visibleOnLockScreen: (StatusBarNotification) -> Boolean = { false }): NoticeRead {
    val candidates = notes.filter {
        it.isFromDialer(dialer, packages) && it.notification.let { n ->
            n.category == Notification.CATEGORY_CALL || n.extras.getString(Notification.EXTRA_TEMPLATE) == Notification.CallStyle::class.java.name
        }
    }
    if (candidates.isEmpty()) return NoticeRead(RingReason.NO_TRUSTED_CALL)
    val sbn = candidates.singleOrNull() ?: return NoticeRead(RingReason.AMBIGUOUS_CALL)
    if (restricted && !visibleOnLockScreen(sbn)) return NoticeRead(RingReason.HIDDEN)
    val extras = sbn.notification.extras
    if (extras.getString(Notification.EXTRA_TEMPLATE) != Notification.CallStyle::class.java.name) {
        val hasCallPerson = extras.getParcelable(Notification.EXTRA_CALL_PERSON, Person::class.java) != null
        return NoticeRead(if (hasCallPerson) RingReason.NO_CALL_STYLE_WITH_CALL_PERSON else RingReason.NO_CALL_STYLE)
    }
    val ringing = state == LineState.RINGING
    val type = extras.getInt(Notification.EXTRA_CALL_TYPE)
    if (!(ringing && type == Notification.CallStyle.CALL_TYPE_INCOMING ||
            state == LineState.OFF_HOOK && type == Notification.CallStyle.CALL_TYPE_ONGOING)) return NoticeRead(RingReason.CALL_TYPE_MISMATCH)
    fun action(field: String) = if (!ringing) null else extras.getParcelable(field, PendingIntent::class.java)
        ?.takeIf { intent -> intent.creatorPackage == sbn.packageName && intent.creatorUid == sbn.uid &&
            (!restricted || sbn.notification.actions.orEmpty().filter { it.actionIntent == intent }
                .singleOrNull()?.isAuthenticationRequired == false) }
    val accept = action(Notification.EXTRA_ANSWER_INTENT)
    val decline = action(Notification.EXTRA_DECLINE_INTENT)
    val person = extras.getParcelable(Notification.EXTRA_CALL_PERSON, Person::class.java)
    // Only the dialer's display label; never infer identity from a phone URI, People or contacts.
    val name = person?.name?.take(128)?.toString()?.filterNot { it.isISOControl() }?.trim()?.takeIf { it.isNotEmpty() }
    val controls = accept != null && decline != null && accept != decline
    fun embedded(icon: Icon?) = icon?.takeIf { it.type == Icon.TYPE_BITMAP || it.type == Icon.TYPE_ADAPTIVE_BITMAP }
    val notice = DialerNotice(CallStamp(sbn.key, sbn.postTime, boundary, accept.takeIf { controls },
        decline.takeIf { controls }, sbn.packageName, restricted), name, embedded(person?.icon) ?: embedded(sbn.notification.getLargeIcon()))
    return NoticeRead(if (name == null) RingReason.NO_CALLER_LABEL else RingReason.LABEL_AVAILABLE, notice)
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
