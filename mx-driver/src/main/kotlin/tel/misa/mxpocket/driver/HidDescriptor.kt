package tel.misa.mxpocket.driver

import java.io.IOException

data class HidReportLayout(
    val input: Map<Int, Int>,
    val output: Map<Int, Int>,
    val feature: Map<Int, Int>,
    val usagePages: Set<Int>,
)

/** Parse report lengths (including IDs) rather than assuming HID collections equal USB interfaces. */
object HidDescriptor {
    fun parse(bytes: ByteArray): HidReportLayout {
        data class Globals(val size: Int = 0, val count: Int = 0, val id: Int = 0, val page: Int = 0)
        var globals = Globals()
        val stack = ArrayDeque<Globals>()
        val input = mutableMapOf<Int, Int>()
        val output = mutableMapOf<Int, Int>()
        val feature = mutableMapOf<Int, Int>()
        val pages = mutableSetOf<Int>()
        var offset = 0
        while (offset < bytes.size) {
            val prefix = bytes.u8(offset++)
            if (prefix == 0xfe) {
                if (offset + 2 > bytes.size) throw IOException("Truncated HID long item")
                val length = bytes.u8(offset)
                offset += 2 + length
                if (offset > bytes.size) throw IOException("Truncated HID long item payload")
                continue
            }
            val size = when (prefix and 3) { 3 -> 4; else -> prefix and 3 }
            if (offset + size > bytes.size) throw IOException("Truncated HID item")
            var value = 0L
            repeat(size) { value = value or (bytes.u8(offset + it).toLong() shl (8 * it)) }
            offset += size
            val type = (prefix shr 2) and 3
            val tag = prefix shr 4
            if (type == 1) {
                if (value > Int.MAX_VALUE) throw IOException("Unsupported HID global value")
                val v = value.toInt()
                globals = when (tag) {
                    0 -> globals.copy(page = v).also { pages += v }
                    7 -> globals.copy(size = v)
                    8 -> {
                        if (v !in 1..255) throw IOException("Invalid HID report ID")
                        globals.copy(id = v)
                    }
                    9 -> globals.copy(count = v)
                    10 -> globals.also { stack.addLast(it) }
                    11 -> stack.removeLastOrNull() ?: throw IOException("Unbalanced HID POP")
                    else -> globals
                }
            } else if (type == 0 && tag in setOf(8, 9, 11)) {
                val target = when (tag) { 8 -> input; 9 -> output; else -> feature }
                val bits = globals.size.toLong() * globals.count + (target[globals.id] ?: 0)
                if (bits > 65535L * 8) throw IOException("HID report too large")
                target[globals.id] = bits.toInt()
            }
        }
        fun lengths(bits: Map<Int, Int>) = bits.mapValues { (id, bitCount) ->
            (bitCount + 7) / 8 + if (id == 0) 0 else 1
        }
        return HidReportLayout(lengths(input), lengths(output), lengths(feature), pages)
    }

    /** HID descriptor 0x21 embeds the length for report descriptor 0x22. */
    fun reportDescriptorLength(raw: ByteArray, interfaceId: Int, alternate: Int): Int? {
        var offset = 0
        var selected = false
        while (offset + 2 <= raw.size) {
            val length = raw.u8(offset)
            if (length < 2 || offset + length > raw.size) throw IOException("Malformed USB descriptor")
            when (raw.u8(offset + 1)) {
                4 -> {
                    if (length < 9) throw IOException("Short USB interface descriptor")
                    selected = raw.u8(offset + 2) == interfaceId && raw.u8(offset + 3) == alternate
                }
                0x21 -> if (selected) {
                    if (length < 6) throw IOException("Short HID descriptor")
                    val count = raw.u8(offset + 5)
                    if (6 + count * 3 > length) throw IOException("Short HID descriptor table")
                    repeat(count) { i ->
                        val start = offset + 6 + i * 3
                        if (raw.u8(start) == 0x22) {
                            return raw.u8(start + 1) or (raw.u8(start + 2) shl 8)
                        }
                    }
                }
            }
            offset += length
        }
        return null
    }
}
