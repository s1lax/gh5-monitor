package com.lumixmonitor.lumix

/**
 * Разбор кадра liveview, который камера возвращает на opcode LIVEVIEW_IMAGE (0x9706).
 *
 * Ответ — не чистый JPEG, а контейнер с блоками метаданных. Первые 180 байт
 * (а фактически — цепочка блоков) содержат JPEG, гистограмму, уровень и ориентацию.
 *
 * Структура блока (выведена из рабочей реализации Tethr):
 *
 *   uint32 id
 *   uint32 dataSize        длина полезной нагрузки, начиная с sessionID
 *   uint32 sessionId       первый элемент полезной нагрузки
 *   ...                    значение и прочие данные
 *
 * Следующий блок начинается со смещения (начало блока + 8 + dataSize).
 * Значение блока читается по смещению (начало блока + 12), то есть сразу
 * после sessionId.
 *
 * ВАЖНО: смещения выведены из референса и не проверены на живой камере.
 * Парсер написан устойчиво: при несовпадении структуры он не падает,
 * а возвращает кадр без метаданных либо null.
 */
class LiveviewFrame(
    /** JPEG-кадр. */
    val jpeg: ByteArray,
    /** Данные гистограммы от камеры, если пришли. */
    val histogram: ByteArray? = null,
    /** Крен в градусах (электронный уровень). */
    val rollDegrees: Float? = null,
    /** Тангаж в градусах. */
    val pitchDegrees: Float? = null,
    /** Ориентация камеры. */
    val posture: Int? = null,
) {
    val hasLevel: Boolean get() = rollDegrees != null && pitchDegrees != null
}

object LiveviewFrameParser {

    /** До какого смещения сканируем блоки метаданных. */
    private const val BLOCK_SCAN_LIMIT = 180

    /** Если блок со смещением JPEG не найден — считаем, что кадр начинается здесь. */
    private const val FALLBACK_JPEG_OFFSET = 180

    const val ID_JPEG_OFFSET = 0x17000001
    const val ID_HISTOGRAM = 0x17000003
    const val ID_POSTURE = 0x17000004
    const val ID_LEVEL_GAUGE = 0x17000005

    private const val JPEG_SOI_0 = 0xFF.toByte()
    private const val JPEG_SOI_1 = 0xD8.toByte()

    /**
     * Разбирает ответ камеры.
     * @return кадр или null, если JPEG извлечь не удалось.
     */
    fun parse(data: ByteArray): LiveviewFrame? {
        if (data.size < 4) return null

        var jpegOffset = FALLBACK_JPEG_OFFSET
        var histogram: ByteArray? = null
        var roll: Float? = null
        var pitch: Float? = null
        var posture: Int? = null

        var offset = 0
        while (offset + 8 <= BLOCK_SCAN_LIMIT && offset + 8 <= data.size) {
            val id = readU32(data, offset)
            val dataSize = readU32(data, offset + 4)

            // Защита от мусора: длина не может быть отрицательной или выходить за буфер.
            // Compare remaining bytes before adding an untrusted 32-bit length:
            // offset + 8 + dataSize can overflow into a negative array index.
            if (dataSize < 0 || dataSize > data.size - offset - 8) break

            val valueAt = offset + 12

            when (id) {
                ID_JPEG_OFFSET -> {
                    if (valueAt + 4 <= data.size) jpegOffset = readU32(data, valueAt)
                }
                ID_HISTOGRAM -> {
                    if (valueAt + 12 <= data.size) {
                        val samples = readU32(data, valueAt + 4)
                        val start = valueAt + 12
                        if (samples > 0 && samples <= data.size - start) {
                            histogram = data.copyOfRange(start, start + samples)
                        }
                    }
                }
                ID_POSTURE -> {
                    if (valueAt + 2 <= data.size) posture = readU16(data, valueAt)
                }
                ID_LEVEL_GAUGE -> {
                    if (valueAt + 4 <= data.size) {
                        roll = readI16(data, valueAt) / 10f
                        pitch = readI16(data, valueAt + 2) / 10f
                    }
                }
            }

            offset += 8 + dataSize
        }

        val jpeg = extractJpeg(data, jpegOffset) ?: return null
        return LiveviewFrame(
            jpeg = jpeg,
            histogram = histogram,
            rollDegrees = roll,
            pitchDegrees = pitch,
            posture = posture,
        )
    }

    /**
     * Извлекает JPEG, начиная с указанного смещения.
     * Если по смещению маркера SOI нет — ищет его в пределах разумного окна,
     * чтобы не отдать на декодирование мусор.
     */
    private fun extractJpeg(data: ByteArray, offset: Int): ByteArray? {
        if (offset <= 0 || offset >= data.size - 1) return null

        if (data[offset] == JPEG_SOI_0 && data[offset + 1] == JPEG_SOI_1) {
            return data.copyOfRange(offset, data.size)
        }

        // Резервный поиск маркера начала JPEG рядом с ожидаемым смещением.
        val searchEnd = minOf(data.size - 1, offset + 64)
        for (i in offset until searchEnd) {
            if (data[i] == JPEG_SOI_0 && data[i + 1] == JPEG_SOI_1) {
                return data.copyOfRange(i, data.size)
            }
        }
        return null
    }

    private fun readU32(data: ByteArray, at: Int): Int {
        if (at + 4 > data.size) return 0
        return (data[at].toInt() and 0xFF) or
            ((data[at + 1].toInt() and 0xFF) shl 8) or
            ((data[at + 2].toInt() and 0xFF) shl 16) or
            ((data[at + 3].toInt() and 0xFF) shl 24)
    }

    private fun readU16(data: ByteArray, at: Int): Int {
        if (at + 2 > data.size) return 0
        return (data[at].toInt() and 0xFF) or ((data[at + 1].toInt() and 0xFF) shl 8)
    }

    private fun readI16(data: ByteArray, at: Int): Int {
        val raw = readU16(data, at)
        return if (raw >= 0x8000) raw - 0x10000 else raw
    }
}
