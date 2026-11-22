package tel.misa.mxpocket.ui

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import tel.misa.mxpocket.shared.ui.parseTileColor

class LayoutKeyEditorTest {
    @Test fun colorEntryPreservesTransparentColorsAndRejectsPartialOrOversizedInput() {
        assertEquals(0xff245cb5.toInt(), parseTileColor(" #245cb5 "))
        assertEquals(0x00245566, parseTileColor("00245566"))
        listOf("", "#", "1", "#AB CDEF", "-100FF", "0123456789", "0x11ef").forEach { assertNull(parseTileColor(it)) }
    }
}
