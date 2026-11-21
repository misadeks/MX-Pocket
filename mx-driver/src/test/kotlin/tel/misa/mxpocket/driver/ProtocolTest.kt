package tel.misa.mxpocket.driver

import java.io.ByteArrayOutputStream
import java.io.IOException
import org.junit.Assert.*
import org.junit.Test

class ProtocolTest {
    private fun jpeg(size: Int) = ByteArray(size) { (it % 251).toByte() }.apply {
        this[0] = 0xff.toByte(); this[1] = 0xd8.toByte()
        this[size - 2] = 0xff.toByte(); this[size - 1] = 0xd9.toByte()
    }

    @Test fun singleFrameMatchesPrimarySourceVector() {
        val reports = KeypadProtocol.imageReports(KeypadProtocol.keyRegion(0), jpeg(100))
        assertEquals(1, reports.size)
        assertEquals(listOf(0x14, 0xff, 2, 0x2b, 0xe1, 1, 0, 1, 0, 0, 23, 0, 6, 0, 118, 0, 118, 0, 0, 100),
            reports.single().take(20).map { it.toInt() and 255 })
        assertEquals(0, reports.single().u8(120))
    }

    @Test fun fragmentBoundariesPreserveAllImageBytesAndGeometry() {
        for (size in listOf(4075, 4076, 8165, 8166, 10000, 65535)) {
            val image = jpeg(size)
            val reports = KeypadProtocol.imageReports(KeypadProtocol.keyRegion(8), image)
            assertTrue(reports.all { it.size == 4095 })
            assertEquals(339, reports.first().u16(9))
            assertEquals(322, reports.first().u16(11))
            assertEquals(size, reports.first().u16(18))
            val recovered = ByteArrayOutputStream()
            var remaining = size
            reports.forEachIndexed { index, report ->
                val header = if (index == 0) 20 else 5
                val count = minOf(remaining, 4095 - header)
                recovered.write(report, header, count)
                remaining -= count
                assertEquals(index == 0, report.u8(4) and 0x80 != 0)
                assertEquals(index == reports.lastIndex, report.u8(4) and 0x40 != 0)
            }
            assertArrayEquals(image, recovered.toByteArray())
        }
        val parts = KeypadProtocol.imageReports(KeypadProtocol.keyRegion(0), jpeg(10000))
        assertEquals(listOf(0xa1, 0x22, 0x63), parts.map { it.u8(4) })
        val maximum = KeypadProtocol.imageReports(KeypadProtocol.keyRegion(0), jpeg(65535))
        assertEquals(0x30, maximum[15].u8(4))
        assertEquals(0x71, maximum[16].u8(4))
    }

    @Test fun rejectsInvalidPayloadsAndBounds() {
        for (action in listOf<() -> Unit>(
            { KeypadProtocol.imageReports(KeypadProtocol.keyRegion(0), jpeg(65536)) },
            { KeypadProtocol.imageReports(KeypadProtocol.keyRegion(0), ByteArray(10)) },
            { KeypadProtocol.imageReports(KeypadProtocol.Region(479, 0, 2, 2), jpeg(10)) },
            { KeypadProtocol.imageReports(KeypadProtocol.Region(Int.MAX_VALUE, 0, 2, 2), jpeg(10)) },
            { KeypadProtocol.keyRegion(9) },
            { KeypadProtocol.command(0, 16) },
        )) {
            assertThrows(IllegalArgumentException::class.java, action)
        }
    }

    @Test fun acknowledgementFlagsAreNotDataFrameFlags() {
        val answer = byteArrayOf(0x13, -1, 2, 0x2b, 0xc1.toByte(), 0, 1).copyOf(32)
        assertEquals(0, KeypadProtocol.imageStatus(answer))
        assertThrows(IOException::class.java) { KeypadProtocol.imageStatus(answer.copyOf(31)) }
        answer[5] = 0xfb.toByte()
        assertEquals(0xfb, KeypadProtocol.imageStatus(answer))
        assertThrows(IOException::class.java) {
            KeypadProtocol.imageStatus(answer.apply { this[5] = 5 })
        }
    }
}
