package tel.misa.mxpocket.driver

data class KeyEvent(val controlId: Int, val pressed: Boolean, val synthetic: Boolean = false)

/** Snapshot-based input: repeated reports do not duplicate presses, and groups remain independent. */
class KeyInput(private val pageFeature: Int) {
    private var grid = emptySet<Int>()
    private var pages = emptySet<Int>()

    @Synchronized
    fun accept(report: ByteArray): List<KeyEvent> {
        if (report.size < 4 || report.u8(1) != 0xff || report.u8(3) != 0) return emptyList()
        return when {
            report.u8(0) == 0x13 && report.u8(2) == KeypadProtocol.LCD_FEATURE -> {
                if (report.size < 7 || report.u8(5) != 1) return emptyList()
                val current = report.drop(6).takeWhile { it != 0.toByte() }
                    .map { it.toInt() and 255 }.filter { it in 1..9 }.toSet()
                transitions(grid, current).also { grid = current }
            }
            report.u8(0) == 0x11 && report.u8(2) == pageFeature -> {
                if (report.size < 6 || (report.size - 4) % 2 != 0) return emptyList()
                val current = (4 until report.size step 2).map { report.u16(it) }
                    .takeWhile { it != 0 }.filter { it == 0x01a1 || it == 0x01a2 }.toSet()
                transitions(pages, current).also { pages = current }
            }
            else -> emptyList()
        }
    }

    @Synchronized
    fun releaseAll(): List<KeyEvent> {
        val events = (grid + pages).sorted().map { KeyEvent(it, false, synthetic = true) }
        grid = emptySet()
        pages = emptySet()
        return events
    }

    private fun transitions(previous: Set<Int>, current: Set<Int>): List<KeyEvent> =
        (previous - current).sorted().map { KeyEvent(it, false) } +
            (current - previous).sorted().map { KeyEvent(it, true) }
}
