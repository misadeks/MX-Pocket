package tel.misa.mxpocket.driver

import java.io.IOException

/** Source-supported MX Keypad profile; geometry is not queried from the hardware. */
object KeypadProtocol {
    const val VENDOR_ID = 0x046d
    const val PRODUCT_ID = 0xc354
    const val REPORT_BYTES = 4095
    const val MAX_JPEG_BYTES = 65535
    const val LCD_FEATURE = 0x02
    const val SOFTWARE_ID = 0x0b

    data class Region(val x: Int, val y: Int, val width: Int, val height: Int)

    fun keyRegion(index: Int): Region {
        require(index in 0..8) { "LCD key index must be 0..8" }
        return Region(23 + index % 3 * 158, 6 + index / 3 * 158, 118, 118)
    }

    fun command(feature: Int, function: Int, vararg params: Int): ByteArray {
        require(feature in 0..255 && function in 0..15 && params.size <= 16)
        require(params.all { it in 0..255 })
        return ByteArray(20).apply {
            this[0] = 0x11
            this[1] = 0xff.toByte()
            this[2] = feature.toByte()
            this[3] = ((function shl 4) or SOFTWARE_ID).toByte()
            params.forEachIndexed { index, value -> this[index + 4] = value.toByte() }
        }
    }

    /** Full HID reports, including report ID. A fragment must be acknowledged before the next. */
    fun imageReports(region: Region, jpeg: ByteArray): List<ByteArray> {
        require(jpeg.size in 4..MAX_JPEG_BYTES) { "JPEG must be 4..65535 bytes" }
        require(jpeg.u8(0) == 0xff && jpeg.u8(1) == 0xd8 &&
            jpeg.u8(jpeg.lastIndex - 1) == 0xff && jpeg.u8(jpeg.lastIndex) == 0xd9) {
            "Expected a complete JPEG bitstream"
        }
        require(region.x >= 0 && region.y >= 0 && region.width > 0 && region.height > 0 &&
            region.x + region.width <= 480 && region.y + region.height <= 480) {
            "Region must fit the 480x480 panel"
        }
        val reports = mutableListOf<ByteArray>()
        var offset = 0
        var part = 1
        while (offset < jpeg.size) {
            val first = part == 1
            val header = if (first) 20 else 5
            val count = minOf(jpeg.size - offset, REPORT_BYTES - header)
            val last = offset + count == jpeg.size
            val report = ByteArray(REPORT_BYTES)
            report[0] = 0x14
            report[1] = 0xff.toByte()
            report[2] = LCD_FEATURE.toByte()
            report[3] = 0x2b
            // Deliberately mirrors primary/native sources: part 16 is 0x30, not masked to zero.
            report[4] = (part or 0x20 or (if (first) 0x80 else 0) or
                (if (last) 0x40 else 0)).toByte()
            if (first) {
                report[5] = 1
                report[7] = 1
                report.put16(9, region.x)
                report.put16(11, region.y)
                report.put16(13, region.width)
                report.put16(15, region.height)
                report.put16(18, jpeg.size)
            }
            jpeg.copyInto(report, header, offset, offset + count)
            reports += report
            offset += count
            part++
        }
        return reports
    }

    fun imageStatus(report: ByteArray): Int {
        if (report.size < 6 || report.u8(1) != 0xff || report.u8(2) != LCD_FEATURE ||
            report.u8(3) != 0x2b) {
            throw IOException("Malformed image acknowledgement")
        }
        return when (val status = report.u8(5)) {
            0, 0xfb -> status
            else -> throw IOException("LCD image rejected: status 0x${status.toString(16)}")
        }
    }
}

internal fun ByteArray.u8(index: Int): Int = this[index].toInt() and 255
internal fun ByteArray.u16(index: Int): Int = (u8(index) shl 8) or u8(index + 1)
private fun ByteArray.put16(index: Int, value: Int) {
    this[index] = (value shr 8).toByte()
    this[index + 1] = value.toByte()
}
