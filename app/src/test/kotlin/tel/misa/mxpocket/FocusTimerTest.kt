package tel.misa.mxpocket

import org.junit.Assert.*
import org.junit.Test

class FocusTimerTest {
    @Test fun pauseResumeAndCompletionUseMonotonicDeadline() {
        val timer = FocusTimer()
        timer.select(1)
        timer.toggle(1000)
        assertEquals("00:30", timer.label(31_000))
        timer.toggle(31_000)
        assertEquals(30_000L, timer.remainingMillis(500_000))
        timer.toggle(500_000)
        assertFalse(timer.tick(529_999))
        assertTrue(timer.tick(530_000))
        assertFalse(timer.running)
        assertEquals("00:00", timer.label(600_000))
        assertFalse(timer.tick(600_001))
    }

    @Test fun durationChangesResetTimerAndStayWithinSafeLimits() {
        val timer = FocusTimer()
        timer.toggle(0)
        timer.adjust(-1000)
        assertEquals(60_000L, timer.durationMillis)
        assertFalse(timer.running)
        timer.adjust(1000)
        assertEquals(120 * 60_000L, timer.durationMillis)
        assertEquals(timer.durationMillis, timer.remainingMillis(1_000_000))
    }
}
