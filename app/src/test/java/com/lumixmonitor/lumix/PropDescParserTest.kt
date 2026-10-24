package com.lumixmonitor.lumix

import java.nio.ByteBuffer
import java.nio.ByteOrder
import org.junit.Assert.*
import org.junit.Test

class PropDescParserTest {
    private fun descriptor(size: Int, current: Int, values: IntArray): ByteArray {
        val bb = ByteBuffer.allocate(12 + size + 4 + values.size * size + 16)
            .order(ByteOrder.LITTLE_ENDIAN)
        bb.putInt(LumixProp.ISO).putInt(1).putInt(0)
        fun put(value: Int) { if (size == 2) bb.putShort(value.toShort()) else bb.putInt(value) }
        put(current)
        bb.putInt(values.size)
        values.forEach { put(it) }
        // Actual observed failure: the next record's header leaked into ISO choices.
        bb.putInt(0x02000022).putInt(20).putInt(20).putInt(65537)
        return bb.array()
    }

    @Test fun `uint32 count excludes following record from ISO choices`() {
        val values = intArrayOf(-1, 100, 200, 800, 1600, 25600)
        val desc = PropDescParser.parse(descriptor(4, -1, values), 4)!!
        assertEquals(-1, desc.current)
        assertArrayEquals(values, desc.allowed)
    }

    @Test fun `uint16 values still use uint32 count`() {
        val values = intArrayOf(35, 40, 56, 80)
        assertArrayEquals(values, PropDescParser.parse(descriptor(2, 35, values), 2)!!.allowed)
    }

    @Test fun `current absent from choices does not enable tail fallback`() {
        assertArrayEquals(intArrayOf(100, 200),
            PropDescParser.parse(descriptor(4, 0, intArrayOf(100, 200)), 4)!!.allowed)
    }

    @Test fun `empty list keeps current without treating tail as choices`() {
        val desc = PropDescParser.parse(descriptor(4, 1600, intArrayOf()), 4)!!
        assertEquals(1600, desc.current)
        assertTrue(desc.allowed.isEmpty())
    }

    @Test fun `invalid lengths are rejected without overflow or allocation`() {
        val bytes = descriptor(4, 1600, intArrayOf(1600))
        assertNull(PropDescParser.parse(bytes.copyOf(15), 4))
        ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN).putInt(16, -1)
        assertNull(PropDescParser.parse(bytes, 4))
        ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN).putInt(4, Int.MAX_VALUE)
        assertNull(PropDescParser.parse(bytes, 4))
        assertNull(PropDescParser.parse(bytes, 3))
    }
}
