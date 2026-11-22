package tel.misa.mxpocket.driver

/** Access under the connection's event lock, including delivery. A callback may itself close. */
internal class ButtonEvents(private val emit: (KeyEvent) -> Unit) {
    private val held = sortedSetOf<Int>()
    var closed = false
        private set

    fun deliver(event: KeyEvent) {
        if (closed) return
        val changed = if (event.pressed) held.add(event.controlId) else held.remove(event.controlId)
        if (changed) emit(event)
    }

    fun close() {
        if (closed) return
        closed = true
        val releases = held.toList()
        held.clear()
        releases.forEach { emit(KeyEvent(it, pressed = false, synthetic = true)) }
    }
}
