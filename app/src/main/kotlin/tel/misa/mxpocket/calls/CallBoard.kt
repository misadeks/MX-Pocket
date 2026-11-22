package tel.misa.mxpocket.calls

import android.app.PendingIntent
import android.content.Context
import android.graphics.Bitmap
import tel.misa.mxpocket.deck.DeckRepository
import tel.misa.mxpocket.deck.PHONE_CALL_VIEW
import tel.misa.mxpocket.plugins.*

/** Main-thread owner. The repository sees only call presence, never identity or Android capabilities. */
internal class CallBoard(
    private val context: Context,
    private val deck: DeckRepository,
    private val readState: () -> LineState,
    private val readNotice: (LineState, String?) -> DialerNotice?,
    private val invalidatePixels: () -> Unit,
) {
    private var stamp: CallStamp? = null
    private var name: String? = null
    private var line: PhoneLine? = null
    private var spent = false
    private var generation = 0L
    private var idleAt = Long.MIN_VALUE
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
            else -> null
        }
        if (state == LineState.IDLE) {
            spent = false
            idleAt = System.currentTimeMillis()
        }
        deck.showPhoneCall(if (state == LineState.UNKNOWN) null else next != null)
        if (!visible || next == null) { forget(); return }
        val notice = if (next != PhoneLine.MULTIPLE) readNotice(state, null)?.takeIf { it.stamp.posted > idleAt } else null
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

    /** Only invoked by PocketSession's fresh USB-down path, never by a binding or a workflow. */
    fun press(index: Int, authorized: () -> Boolean): Boolean {
        val expected = stamp ?: return false
        if (spent || line != PhoneLine.RINGING || !visible || !authorized()) return false
        if (readState() != LineState.RINGING) return false
        val live = readNotice(LineState.RINGING, expected.key) ?: return false
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
