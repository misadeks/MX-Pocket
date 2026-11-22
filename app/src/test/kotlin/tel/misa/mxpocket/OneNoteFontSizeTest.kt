package tel.misa.mxpocket

import org.junit.Assert.*
import org.junit.Test
import tel.misa.mxpocket.decks.fontSizeOptions

class OneNoteFontSizeTest {
    @Test fun adjacentVisibleSizesAndBoundariesComeFromOneNote() {
        val values = listOf("8", "9", "10", "11", "12", "14", "16")
        val middle = fontSizeOptions(values, "12")
        assertEquals("size:down:11", middle[0].first)
        assertEquals("12", middle[1].second)
        assertEquals("size:up:14", middle[2].first)
        assertTrue(fontSizeOptions(values, "8")[0].first.isEmpty())
        assertTrue(fontSizeOptions(values, "16")[2].first.isEmpty())
        assertTrue(fontSizeOptions(values, "13")[0].first.isEmpty())
        assertTrue(fontSizeOptions(values, "13")[2].first.isEmpty())
        assertEquals("size:cancel", middle[7].first)
    }
}
