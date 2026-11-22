package tel.misa.mxpocket

import android.os.Handler
import android.os.SystemClock
import tel.misa.mxpocket.plugins.KeyChord

/** Main-thread physical LCD input. A chord gets first refusal before a single enters Tap/Hold. */
internal class PhysicalChordInput(private val main: Handler) {
    private val down = mutableSetOf<Int>()
    data class Partner(val chord: KeyChord, val receipt: Long)
    private data class Candidate(val index: Int, val time: Long, val receipt: Long, val partners: Map<Int, Partner>,
        val current: () -> Boolean, val single: () -> Unit)
    private var pending: Candidate? = null
    private val timeout = Runnable { flush() }
    private class Hold(val deadline: Long, val finish: (Boolean) -> Unit) : Runnable {
        override fun run() = finish(true)
    }
    private val holds = mutableMapOf<Int, Hold>()

    fun begin(index: Int) = down.add(index)
    fun available(index: Int) = index !in down || index == pending?.index
    val hasPending get() = pending != null

    fun press(index: Int, time: Long, receipt: Long, partners: Map<Int, Partner>, current: () -> Boolean,
        single: (deferred: Boolean) -> Unit, chord: (KeyChord) -> Unit) {
        val first = pending
        if (first != null) {
            clearChord()
            if (first.current()) {
                val partner = first.partners[index]
                if (partner != null && partner.receipt == receipt &&
                    partners[first.index] == Partner(partner.chord, first.receipt) && current() &&
                    time - first.time in 0 until WINDOW_MS && SystemClock.elapsedRealtime() - first.time in 0 until WINDOW_MS) {
                    chord(partner.chord)
                    return
                }
                first.single() // Commit the older down before the new one; it may change the page.
            }
        }
        if (!current()) return
        if (partners.isEmpty()) {
            single(false)
            return
        }
        val remaining = WINDOW_MS - (SystemClock.elapsedRealtime() - time)
        if (remaining <= 0) single(true)
        else {
            pending = Candidate(index, time, receipt, partners, current) { single(true) }
            main.postDelayed(timeout, remaining)
        }
    }

    fun awaitHold(index: Int, deadline: Long, dispatch: (hold: Boolean) -> Unit) {
        val decision = Hold(deadline) { hold ->
            holds.remove(index)?.let { main.removeCallbacks(it); dispatch(hold) }
        }
        holds[index] = decision
        // Even an overdue timer queues behind any up already posted by the USB reader.
        main.postDelayed(decision, (deadline - SystemClock.elapsedRealtime()).coerceAtLeast(0))
    }

    fun release(index: Int, synthetic: Boolean, arrivedAt: Long): Boolean {
        if (synthetic) cancel() else {
            if (pending?.index == index) flush()
            holds[index]?.let { it.finish(arrivedAt >= it.deadline) }
        }
        return down.remove(index)
    }

    private fun flush() {
        val key = pending ?: return
        clearChord()
        if (key.current()) key.single()
    }

    private fun clearChord() {
        if (pending == null) return
        main.removeCallbacks(timeout)
        pending = null
    }

    fun cancel() {
        clearChord()
        holds.values.forEach { main.removeCallbacks(it) }
        holds.clear()
    }

    fun disconnect() { cancel(); down.clear() }

    companion object { const val WINDOW_MS = 150L }
}
