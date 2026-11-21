package tel.misa.mxpocket.driver

import java.io.IOException
import org.junit.Assert.*
import org.junit.Test

class HidDescriptorTest {
    @Test fun reportIdsCanShareOneUsbInterfaceAndLengthsIncludeId() {
        val descriptor = bytes(
            0x06, 0x43, 0xff, 0x85, 0x11, 0x75, 8, 0x95, 19, 0x81, 2, 0x91, 2,
            0xa4, 0x85, 0x14, 0x96, 0xfe, 0x0f, 0x91, 2, 0xb4,
            0x85, 0x13, 0x95, 31, 0x81, 2,
        )
        val layout = HidDescriptor.parse(descriptor)
        assertEquals(mapOf(0x11 to 20, 0x14 to 4095), layout.output)
        assertEquals(mapOf(0x11 to 20, 0x13 to 32), layout.input)
        assertEquals(setOf(0xff43), layout.usagePages)
    }

    @Test fun descriptorLookupUsesInterfaceNumberNotOrdinal() {
        val descriptors = bytes(
            9, 4, 7, 0, 2, 3, 0, 0, 0,
            9, 0x21, 0x11, 1, 0, 1, 0x22, 0x34, 0x12,
            9, 4, 9, 0, 1, 3, 0, 0, 0,
            9, 0x21, 0x11, 1, 0, 1, 0x22, 0x42, 0,
        )
        assertEquals(0x1234, HidDescriptor.reportDescriptorLength(descriptors, 7, 0))
        assertEquals(0x42, HidDescriptor.reportDescriptorLength(descriptors, 9, 0))
        assertNull(HidDescriptor.reportDescriptorLength(descriptors, 0, 0))
    }

    @Test fun rejectsMalformedDescriptorAndUnbalancedGlobals() {
        for (descriptor in listOf(bytes(0x96, 1), bytes(0xb4), bytes(0xfe, 10, 0, 1))) {
            assertThrows(IOException::class.java) { HidDescriptor.parse(descriptor) }
        }
        assertThrows(IOException::class.java) {
            HidDescriptor.reportDescriptorLength(bytes(0, 4), 0, 0)
        }
    }

    private fun bytes(vararg values: Int) = values.map(Int::toByte).toByteArray()
}
