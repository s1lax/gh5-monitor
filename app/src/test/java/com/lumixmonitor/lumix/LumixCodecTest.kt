package com.lumixmonitor.lumix

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * Проверка кодировок Panasonic. Схемы неочевидны (выдержка кодируется
 * миллисекундами со знаковым битом, экспокоррекция — шагами по 1/3 EV),
 * поэтому они зафиксированы тестами.
 */
class LumixCodecTest {

    // ------------------------------------------------------------ выдержка

    @Test
    fun `дробная выдержка кодируется как знаменатель на тысячу`() {
        assertEquals(500_000, LumixCodec.encodeShutterSpeed("1/500"))
        assertEquals(50_000, LumixCodec.encodeShutterSpeed("1/50"))
        assertEquals(1_000_000, LumixCodec.encodeShutterSpeed("1/1000"))
    }

    @Test
    fun `дробная выдержка декодируется обратно`() {
        assertEquals("1/500", LumixCodec.decodeShutterSpeed(500_000))
        assertEquals("1/50", LumixCodec.decodeShutterSpeed(50_000))
    }

    @Test
    fun `выдержка длиннее секунды кодируется со старшим битом`() {
        val encoded = LumixCodec.encodeShutterSpeed("2.5")
        assertEquals(2500 or 0x80000000.toInt(), encoded)
        assertEquals("2.5", LumixCodec.decodeShutterSpeed(encoded!!))
    }

    @Test
    fun `bulb и auto кодируются специальными значениями`() {
        assertEquals(0xFFFFFFFF.toInt(), LumixCodec.encodeShutterSpeed("bulb"))
        assertEquals("bulb", LumixCodec.decodeShutterSpeed(0xFFFFFFFF.toInt()))
        assertEquals("auto", LumixCodec.decodeShutterSpeed(0x0FFFFFFF))
    }

    @Test
    fun `некорректная выдержка не кодируется`() {
        assertNull(LumixCodec.encodeShutterSpeed("abc"))
        assertNull(LumixCodec.encodeShutterSpeed("1/0"))
    }

    // ------------------------------------------------------- экспокоррекция

    @Test
    fun `экспокоррекция кодируется шагами по трети ступени`() {
        assertEquals(0x0000, LumixCodec.encodeExposureComp("0"))
        assertEquals(0x0001, LumixCodec.encodeExposureComp("+1/3"))
        assertEquals(0x0002, LumixCodec.encodeExposureComp("+2/3"))
        assertEquals(0x0003, LumixCodec.encodeExposureComp("+1"))
        assertEquals(0x0004, LumixCodec.encodeExposureComp("+1 1/3"))
    }

    @Test
    fun `отрицательная экспокоррекция ставит старший бит`() {
        assertEquals(0x8002, LumixCodec.encodeExposureComp("-2/3"))
        assertEquals(0x8001, LumixCodec.encodeExposureComp("-1/3"))
    }

    @Test
    fun `экспокоррекция декодируется обратно`() {
        assertEquals("0", LumixCodec.decodeExposureComp(0x0000))
        assertEquals("+1/3", LumixCodec.decodeExposureComp(0x0001))
        assertEquals("+1", LumixCodec.decodeExposureComp(0x0003))
        assertEquals("+1 1/3", LumixCodec.decodeExposureComp(0x0004))
        assertEquals("-2/3", LumixCodec.decodeExposureComp(0x8002))
    }

    @Test
    fun `перебор экспокоррекции туда и обратно совпадает`() {
        listOf("0", "+1/3", "-1/3", "+2", "-2 1/3", "+1 2/3").forEach { text ->
            val encoded = LumixCodec.encodeExposureComp(text)
            assertEquals("не сошлось для $text", text, LumixCodec.decodeExposureComp(encoded!!))
        }
    }

    // ------------------------------------------------------------ диафрагма

    @Test
    fun `диафрагма кодируется десятыми долями`() {
        assertEquals(28, LumixCodec.encodeAperture(2.8))
        assertEquals(2.8, LumixCodec.decodeAperture(28), 0.001)
        assertEquals(11.0, LumixCodec.decodeAperture(110), 0.001)
    }

    @Test
    fun `диафрагма форматируется для интерфейса`() {
        assertEquals("f/2.8", LumixCodec.formatAperture(2.8))
        assertEquals("f/11", LumixCodec.formatAperture(11.0))
    }

    // ------------------------------------------------------------------ ISO

    @Test
    fun `ISO auto распознаётся`() {
        assertEquals("auto", LumixCodec.decodeIso(0xFFFFFFFF.toInt()))
        assertEquals("auto (i-ISO)", LumixCodec.decodeIso(0xFFFFFFFE.toInt()))
        assertEquals("800", LumixCodec.decodeIso(800))
    }

    // ------------------------------------------------------------------ таблицы

    @Test
    fun `таблицы переводят коды в названия и обратно`() {
        assertEquals("V-Log", LumixTables.nameOf(LumixTables.photoStyle, 40))
        assertEquals(40, LumixTables.codeOf(LumixTables.photoStyle, "V-Log"))
        assertEquals("M", LumixTables.nameOf(LumixTables.exposureMode, 3))
        assertEquals("daylight", LumixTables.nameOf(LumixTables.whiteBalance, 0x0004))
        assertNull(LumixTables.nameOf(LumixTables.photoStyle, 9999))
    }
}
