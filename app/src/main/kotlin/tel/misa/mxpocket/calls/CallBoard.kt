package tel.misa.mxpocket.calls

import android.app.PendingIntent
import android.content.Context
import android.graphics.Bitmap
import tel.misa.mxpocket.deck.DeckRepository
import tel.misa.mxpocket.deck.PHONE_CALL_VIEW
import tel.misa.mxpocket.plugins.*

private const val EARLY_NOTICE_WINDOW_MS = 2_000L // Two connected-session polling intervals.

/** Main-thread owner. The repository sees only call presence, never identity or Android capabilities. */
internal class CallBoard(
    private val context: Context,
    private val deck: DeckRepository,
    private val readState: () -> LineState,
    private val readNotice: (LineState, String?) -> NoticeRead,
    private val invalidatePixels: () -> Unit,
    private val now: () -> Long = System::currentTimeMillis,
) {
    private var stamp: CallStamp? = null
    private var name: String? = null
    private var line: PhoneLine? = null
    private var spent = false
    private var generation = 0L
    private var idleAt = Long.MIN_VALUE
    private var idleSince = Long.MAX_VALUE
    private var previous = LineState.UNKNOWN
    private var ringStarted = 0L
    var lastRingReason: RingReason? = null
        private set
    var photo: Bitmap? = null
        private set
    val visible get() = deck.operatingState.value.activeProfileId == PHONE_CALL_VIEW
    fun frame() = phoneCallFrame(line ?: PhoneLine.MULTIPLE, name, !spent && stamp?.accept != null, generation)
    fun homeFrame() = phoneCallHomeFrame(line ?: PhoneLine.MULTIPLE, !spent && stamp?.accept != null, generation)

    fun refresh(state: LineState) {
        val next = when (state) {
            LineState.RINGING -> PhoneLine.RINGING
            LineState.OFF_HOOK -> PhoneLine.OFF_HOOK
            LineState.MULTIPLE -> PhoneLine.MULTIPLE
            LineState.CALL -> PhoneLine.CALL
            else -> null
        }
        val newRing = state == LineState.RINGING && previous != LineState.RINGING
        if (state == LineState.IDLE) {
            spent = false
            idleAt = now()
            if (previous != LineState.IDLE) idleSince = idleAt
        } else if (newRing) {
            ringStarted = now()
            if (previous != LineState.IDLE) idleSince = Long.MAX_VALUE
            lastRingReason = null
        }
        previous = state
        // Ringing opens the view. Off-hook, multiple lines or isInCall alone can only sustain it.
        deck.showPhoneCall(when (state) {
            LineState.UNKNOWN -> null
            LineState.IDLE -> false
            LineState.RINGING -> true
            else -> true.takeIf { visible }
        })
        if (!visible || next == null) { forget(); return }
        val read = if (next == PhoneLine.RINGING || next == PhoneLine.OFF_HOOK) readNotice(state, null) else null
        var notice = read?.notice
        var reason = read?.reason
        if (notice != null && notice.stamp.posted <= idleAt) {
            val posted = notice.stamp.posted
            // A Phone notification callback can precede the phone-state broadcast. Between
            // polls, recover identity only, never an action or a notice from the last call.
            if (next == PhoneLine.RINGING && posted > idleSince && posted in (ringStarted - EARLY_NOTICE_WINDOW_MS)..ringStarted) {
                notice = DialerNotice(notice.stamp.copy(accept = null, decline = null), notice.name, notice.icon)
                if (notice.name != null) reason = RingReason.EARLY_LABEL_ONLY
            } else {
                notice = null
                reason = RingReason.STALE_NOTICE
            }
        }
        // A removal just before IDLE shouldn't erase the reason the candidate was rejected.
        if (next == PhoneLine.RINGING && (lastRingReason == null || reason != RingReason.NO_TRUSTED_CALL))
            lastRingReason = reason
        if (next == line && notice?.stamp == stamp && notice?.name == name) return
        line = next
        stamp = notice?.stamp
        name = notice?.name
        photo = notice?.photo(context)
        generation++
        invalidatePixels()
    }

    fun forget() {
        if (line == null) return
        line = null
        stamp = null
        name = null
        photo = null
        generation++
        invalidatePixels()
    }

    fun clearDiagnostic() { lastRingReason = null }

    /** Only invoked by PocketSession's fresh USB-down path, never by a binding or a workflow. */
    fun press(index: Int, authorized: () -> Boolean): Boolean {
        val expected = stamp ?: return false
        if (spent || line != PhoneLine.RINGING || !visible || !authorized()) return false
        if (readState() != LineState.RINGING) return false
        val live = readNotice(LineState.RINGING, expected.key).notice ?: return false
        if (live.stamp != expected || live.name != name || !authorized()) return false
        val action = when (index) {
            CALL_ACCEPT -> live.stamp.accept
            CALL_DECLINE -> live.stamp.decline
            else -> null
        } ?: return false
        spent = true // One request per ringing episode, even if the publisher is slow or cancels it.
        generation++
        invalidatePixels()
        return try { action.send(); true }
        catch (_: PendingIntent.CanceledException) { false }
        catch (_: SecurityException) { false }
    }
}
