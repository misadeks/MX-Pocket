package tel.misa.mxpocket.plugins.clipstudio

import org.junit.Assert.*
import org.junit.Test
import tel.misa.mxpocket.plugins.clipstudio.ClipStudioCommand.Field
import tel.misa.mxpocket.plugins.clipstudio.ClipStudioCommand.Tool

class ClipStudioCommandTest {
    @Test fun toolCommandsRoundTrip() {
        for (tool in Tool.entries) {
            val command = ClipStudioCommand.tool(tool)
            assertEquals("clipstudio.tool.${tool.wire}", command)
            assertEquals(ClipStudioCommand.Parsed.SelectTool(tool), ClipStudioCommand.parse(command))
        }
    }

    @Test fun stepCommandsRoundTripWithSignedDelta() {
        assertEquals("clipstudio.size.step:+2", ClipStudioCommand.step(Field.SIZE, 2))
        assertEquals("clipstudio.opacity.step:-5", ClipStudioCommand.step(Field.OPACITY, -5))
        assertEquals("clipstudio.extra.step:+10", ClipStudioCommand.step(Field.EXTRA, 10))
        assertEquals(ClipStudioCommand.Parsed.Step(Field.SIZE, 2), ClipStudioCommand.parse("clipstudio.size.step:+2"))
        assertEquals(ClipStudioCommand.Parsed.Step(Field.OPACITY, -5), ClipStudioCommand.parse("clipstudio.opacity.step:-5"))
        assertEquals(ClipStudioCommand.Parsed.Step(Field.EXTRA, 10), ClipStudioCommand.parse("clipstudio.extra.step:10"))
    }

    @Test fun rejectsForeignMalformedOrZeroCommands() {
        assertNull(ClipStudioCommand.parse("focus.start"))
        assertNull(ClipStudioCommand.parse("clipstudio."))
        assertNull(ClipStudioCommand.parse("clipstudio.tool.chisel"))
        assertNull(ClipStudioCommand.parse("clipstudio.size.set:20"))
        assertNull(ClipStudioCommand.parse("clipstudio.size.step:abc"))
        assertNull(ClipStudioCommand.parse("clipstudio.size.step:0"))
        assertNull(ClipStudioCommand.parse("clipstudio.unknown.step:+1"))
        assertThrows(IllegalArgumentException::class.java) { ClipStudioCommand.step(Field.SIZE, 0) }
    }
}
