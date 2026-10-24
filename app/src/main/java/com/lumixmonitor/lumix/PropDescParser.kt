package com.lumixmonitor.lumix

import java.nio.ByteBuffer
import java.nio.ByteOrder

/** Panasonic ListProperty format, as parsed by libgphoto2's Panasonic driver. */
internal object PropDescParser {
    fun parse(payload: ByteArray, valueSize: Int): PropDesc? {
        if (PanasonicData.u32(payload, 4) == 20L) {
            val p = CameraPropertyParser.parse(payload).values.firstOrNull { it.valueSize == valueSize } ?: return null
            return PropDesc(p.current, p.choices.toIntArray())
        }
        if (valueSize !in listOf(1, 2, 4) || payload.size < 8) return null
        val bb = ByteBuffer.wrap(payload).order(ByteOrder.LITTLE_ENDIAN)
        // Use Long for untrusted lengths, including multiplication, before narrowing.
        val headerWords = bb.getInt(4).toLong() and 0xffffffffL
        val offset = headerWords * 4L + 8L
        if (offset + valueSize + 4L > payload.size) return null
        bb.position(offset.toInt())
        val current = readValue(bb, valueSize)
        // The count is always UINT32, including when the values are UINT16.
        val count = bb.int.toLong() and 0xffffffffL
        if (count > 512L || count * valueSize > bb.remaining()) return null
        // Composite replies can contain further property records after this list.
        // Never interpret that tail as more choices, even if current is absent.
        return PropDesc(current, IntArray(count.toInt()) { readValue(bb, valueSize) })
    }

    private fun readValue(bb: ByteBuffer, size: Int): Int = when (size) {
        1 -> bb.get().toInt() and 0xff
        2 -> bb.short.toInt() and 0xffff
        else -> bb.int
    }
}
