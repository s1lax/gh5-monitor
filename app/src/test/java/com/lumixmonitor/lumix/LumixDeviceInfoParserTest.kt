package com.lumixmonitor.lumix

import java.io.ByteArrayOutputStream
import org.junit.Assert.*
import org.junit.Test

class LumixDeviceInfoParserTest {
    @Test fun gh5DeviceStringsDoNotKeepPtpNullTerminators() {
        val bytes = ByteArrayOutputStream()
        fun u16(value: Int) { bytes.write(value and 255); bytes.write((value ushr 8) and 255) }
        fun u32(value: Int) { u16(value); u16(value ushr 16) }
        fun text(value: String) {
            bytes.write(value.length + 1)
            bytes.write(value.toByteArray(Charsets.UTF_16LE))
            u16(0)
        }
        u16(100); u32(0x1c); u16(100); text(""); u16(0)
        u32(2); u16(0x1001); u16(0x1002)
        repeat(4) { u32(0) }
        text("Panasonic"); text("DC-GH5"); text("2.6"); text("test")
        val info = LumixDeviceInfoParser.parse(bytes.toByteArray())!!
        assertEquals("Panasonic DC-GH5", info.displayName)
        assertEquals("2.6", info.deviceVersion)
        assertArrayEquals(intArrayOf(0x1001, 0x1002), info.operationsSupported)
    }
}
