package tel.misa.mxpocket.driver

import org.junit.Assert.*
import org.junit.Test

class KeyInputTest {
    private fun grid(vararg keys: Int) = ByteArray(32).apply {
        this[0] = 0x13; this[1] = -1; this[2] = 2; this[5] = 1
        keys.forEachIndexed { i, key -> this[6 + i] = key.toByte() }
    }
    private fun page(vararg ids: Int) = ByteArray(20).apply {
        this[0] = 0x11; this[1] = -1; this[2] = 0x0b
        ids.forEachIndexed { i, id ->
            this[4 + 2 * i] = (id shr 8).toByte(); this[5 + 2 * i] = id.toByte()
        }
    }

    @Test fun simultaneousKeysRepeatAndRelease() {
        val decoder = KeyInput(0x0b)
        assertEquals(listOf(KeyEvent(1, true), KeyEvent(9, true)), decoder.accept(grid(1, 9)))
        assertTrue(decoder.accept(grid(1, 9)).isEmpty())
        assertEquals(listOf(KeyEvent(1, false), KeyEvent(2, true)), decoder.accept(grid(2, 9)))
        assertEquals(listOf(KeyEvent(2, false), KeyEvent(9, false)), decoder.accept(grid()))
    }

    @Test fun pageAndGridStatesDoNotReleaseEachOther() {
        val decoder = KeyInput(0x0b)
        decoder.accept(grid(3))
        assertEquals(listOf(KeyEvent(0x01a1, true), KeyEvent(0x01a2, true)), decoder.accept(page(0x01a1, 0x01a2)))
        assertEquals(listOf(KeyEvent(0x01a1, false)), decoder.accept(page(0x01a2)))
        assertEquals(listOf(KeyEvent(3, false, true), KeyEvent(0x01a2, false, true)), decoder.releaseAll())
        assertTrue(decoder.releaseAll().isEmpty())
        assertEquals(listOf(KeyEvent(3, true)), decoder.accept(grid(3)))
    }

    @Test fun ignoresAcknowledgementsAndTruncatedReports() {
        val decoder = KeyInput(0x0b)
        decoder.accept(grid(1))
        decoder.accept(page(0x01a2))
        assertTrue(decoder.accept(grid().apply { this[3] = 0x2b }).isEmpty())
        for (length in 0 until 32) assertTrue(decoder.accept(grid(7).copyOf(length)).isEmpty())
        for (length in 0 until 20) assertTrue(decoder.accept(page(0x01a1).copyOf(length)).isEmpty())
        assertEquals(listOf(KeyEvent(0x01a2, false)), decoder.accept(page()))
        assertEquals(listOf(KeyEvent(1, false)), decoder.accept(grid()))
    }
}
