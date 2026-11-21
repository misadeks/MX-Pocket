package tel.misa.mxpocket

/** Monotonic caller-provided time makes pause/resume independent of wall-clock changes. */
class FocusTimer {
    var durationMillis = 25 * 60_000L
        private set
    private var remaining = durationMillis
    private var deadline: Long? = null
    val running: Boolean get() = deadline != null

    fun remainingMillis(now: Long): Long = deadline?.let { (it - now).coerceAtLeast(0) } ?: remaining

    fun toggle(now: Long) {
        if (running) {
            remaining = remainingMillis(now)
            deadline = null
        } else {
            if (remaining == 0L) remaining = durationMillis
            deadline = now + remaining
        }
    }

    fun reset() {
        deadline = null
        remaining = durationMillis
    }

    fun adjust(minutes: Int) {
        durationMillis = (durationMillis + minutes * 60_000L).coerceIn(60_000L, 120 * 60_000L)
        reset()
    }

    fun select(minutes: Int) {
        require(minutes in 1..120)
        durationMillis = minutes * 60_000L
        reset()
    }

    fun tick(now: Long): Boolean {
        if (running && remainingMillis(now) == 0L) {
            deadline = null
            remaining = 0
            return true
        }
        return false
    }

    fun label(now: Long): String {
        val seconds = (remainingMillis(now) + 999) / 1000
        return "%02d:%02d".format(seconds / 60, seconds % 60)
    }
}
