package com.lumixmonitor.lumix

import java.nio.ByteBuffer
import java.nio.ByteOrder

/** GH5 camera-side MF Assist. The camera sends the magnified sensor region over USB. */
object NativeFocusAssist {
    const val START = 0x03000051
    const val END = 0x03000054

    fun control(enabled: Boolean): Int = if (enabled) START else END

    fun payload(enabled: Boolean, x: Int = 500, y: Int = 500): ByteArray {
        require(x in 0..1000 && y in 0..1000)
        val size = if (enabled) 10 else 0
        return ByteBuffer.allocate(8 + size).order(ByteOrder.LITTLE_ENDIAN).apply {
            putInt(control(enabled))
            putInt(size)
            if (enabled) {
                putShort(1) // Full-screen magnification, rather than the area-selection frame.
                putShort(0)
                // The chosen sensor point belongs in START. An immediate MOVE after
                // START may be acknowledged before magnification is ready and ignored.
                putShort(x.toShort())
                putShort(y.toShort())
                putShort(0)
            }
        }.array()
    }
}
