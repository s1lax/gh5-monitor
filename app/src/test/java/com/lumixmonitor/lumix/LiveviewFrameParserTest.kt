package com.lumixmonitor.lumix

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * Проверка разбора кадра liveview на синтетическом буфере.
 *
 * Структура блоков выведена из референса, поэтому тест фиксирует именно её:
 * блок = id(4) + dataSize(4) + payload(dataSize), где первые 4 байта payload —
 * sessionId, а значение начинается сразу за ним.
 */
class LiveviewFrameParserTest {

    private val jpegOffsetBlockId = 0x17000001
    private val levelGaugeBlockId = 0x17000005
    private val histogramBlockId = 0x17000003

    @Test
    fun `кадр без метаданных всё равно извлекается по резервному смещению`() {
        val data = ByteArray(200)
        // Маркеры JPEG по резервному смещению 180.
        data[180] = 0xFF.toByte()
        data[181] = 0xD8.toByte()
        data[190] = 0xFF.toByte()
        data[191] = 0xD9.toByte()

        val frame = LiveviewFrameParser.parse(data)
        assertNotNull(frame)
        assertEquals(20, frame!!.jpeg.size)
        assertNull(frame.rollDegrees)
    }

    @Test
    fun `смещение JPEG берётся из блока метаданных`() {
        // Буфер заканчивается сразу за маркерами JPEG, иначе кадром
        // окажется всё от смещения до конца буфера.
        val data = ByteArray(36)
        val bb = ByteBuffer.wrap(data).order(ByteOrder.LITTLE_ENDIAN)

        // Блок 1: смещение JPEG.
        bb.position(0)
        bb.putInt(jpegOffsetBlockId)
        bb.putInt(8)
        bb.putInt(0)          // sessionId
        bb.putInt(32)         // значение: смещение JPEG
        // Блок 2: уровень.
        bb.putInt(levelGaugeBlockId)
        bb.putInt(8)
        bb.putInt(0)          // sessionId
        bb.putShort(25)       // roll = 2.5 градуса
        bb.putShort((-30).toShort()) // pitch = -3.0 градуса

        data[32] = 0xFF.toByte()
        data[33] = 0xD8.toByte()
        data[34] = 0xFF.toByte()
        data[35] = 0xD9.toByte()

        val frame = LiveviewFrameParser.parse(data)
        assertNotNull(frame)
        assertEquals(4, frame!!.jpeg.size)
        assertEquals(0xFF.toByte(), frame.jpeg[0])
        assertEquals(2.5f, frame.rollDegrees!!, 0.001f)
        assertEquals(-3.0f, frame.pitchDegrees!!, 0.001f)
        assertTrue(frame.hasLevel)
    }

    @Test
    fun `гистограмма от камеры разбирается`() {
        val data = ByteArray(96)
        val bb = ByteBuffer.wrap(data).order(ByteOrder.LITTLE_ENDIAN)

        bb.position(0)
        bb.putInt(jpegOffsetBlockId)
        bb.putInt(8)
        bb.putInt(0)
        bb.putInt(64)

        // Блок гистограммы: valid, samples, elems, затем данные.
        bb.putInt(histogramBlockId)
        bb.putInt(16)         // dataSize = sessionId(4) + 12 байт заголовка
        bb.putInt(0)          // sessionId
        bb.putInt(1)          // valid
        bb.putInt(4)          // samples
        bb.putInt(1)          // elems
        bb.put(byteArrayOf(1, 2, 3, 4))

        data[64] = 0xFF.toByte()
        data[65] = 0xD8.toByte()

        val frame = LiveviewFrameParser.parse(data)
        assertNotNull(frame)
        assertNotNull(frame!!.histogram)
        assertEquals(4, frame.histogram!!.size)
        assertEquals(1.toByte(), frame.histogram!![0])
        assertEquals(4.toByte(), frame.histogram!![3])
    }

    @Test
    fun `мусорные данные не приводят к исключению`() {
        val data = ByteArray(200) { 0x7F }
        assertNull(LiveviewFrameParser.parse(data))
    }

    @Test
    fun `слишком короткий буфер не разбирается`() {
        assertNull(LiveviewFrameParser.parse(ByteArray(2)))
    }

    @Test
    fun `максимальная длина блока не переполняет смещение`() {
        val data = ByteArray(200)
        ByteBuffer.wrap(data).order(ByteOrder.LITTLE_ENDIAN)
            .putInt(levelGaugeBlockId).putInt(Int.MAX_VALUE)
        assertNull(LiveviewFrameParser.parse(data))
    }

    @Test
    fun `переполненная длина гистограммы пропускается но JPEG сохраняется`() {
        val data = ByteArray(200)
        val bb = ByteBuffer.wrap(data).order(ByteOrder.LITTLE_ENDIAN)
        bb.putInt(histogramBlockId).putInt(16).putInt(0)
            .putInt(1).putInt(Int.MAX_VALUE).putInt(1)
        data[180] = 0xFF.toByte()
        data[181] = 0xD8.toByte()
        val frame = LiveviewFrameParser.parse(data)
        assertNotNull(frame)
        assertNull(frame!!.histogram)
        assertEquals(20, frame.jpeg.size)
    }

    @Test
    fun `смещение JPEG на последний байт не читает за концом буфера`() {
        val data = ByteArray(200)
        ByteBuffer.wrap(data).order(ByteOrder.LITTLE_ENDIAN)
            .putInt(jpegOffsetBlockId).putInt(8).putInt(0).putInt(199)
        data[199] = 0xFF.toByte()
        assertNull(LiveviewFrameParser.parse(data))
    }
}
