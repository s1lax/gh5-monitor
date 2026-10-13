package com.lumixmonitor.lumix

/**
 * Информация об устройстве из стандартного запроса PTP GetDeviceInfo (0x1001).
 *
 * Полезна двумя: подтверждает, что канал действительно работает, и даёт
 * настоящее имя модели («DC-S5»), тогда как дескриптор USB несёт общее
 * название вроде «Panasonic Digital Camera».
 */
class LumixDeviceInfo(
    val manufacturer: String,
    val model: String,
    val deviceVersion: String,
    val serialNumber: String,
    val operationsSupported: IntArray,
) {
    /** Читаемая подпись для интерфейса. */
    val displayName: String
        get() = when {
            model.isNotBlank() && manufacturer.isNotBlank() -> "$manufacturer $model"
            model.isNotBlank() -> model
            else -> "Lumix"
        }
}

/**
 * Разбор ответа GetDeviceInfo.
 *
 * Структура (PIMA 15740):
 *   uint16 StandardVersion
 *   uint32 VendorExtensionID
 *   uint16 VendorExtensionVersion
 *   string VendorExtensionDesc
 *   uint16 FunctionalMode
 *   uint32[] OperationsSupported
 *   uint32[] EventsSupported
 *   uint32[] DevicePropertiesSupported
 *   uint32[] CaptureFormats
 *   uint32[] ImageFormats
 *   string Manufacturer
 *   string Model
 *   string DeviceVersion
 *   string SerialNumber
 *
 * Строка PTP — это один байт длины (в символах) и затем UTF-16LE без
 * завершающего нуля. Массив — это uint32 с количеством элементов, затем сами
 * элементы по uint16.
 */
object LumixDeviceInfoParser {

    fun parse(data: ByteArray): LumixDeviceInfo? {
        return try {
            val reader = PtpReader(data)
            reader.u16()                 // StandardVersion
            reader.u32()                 // VendorExtensionID
            reader.u16()                 // VendorExtensionVersion
            reader.string()              // VendorExtensionDesc
            reader.u16()                 // FunctionalMode
            val operations = reader.u16Array()
            reader.u16Array()            // EventsSupported
            reader.u16Array()            // DevicePropertiesSupported
            reader.u16Array()            // CaptureFormats
            reader.u16Array()            // ImageFormats
            val manufacturer = reader.string()
            val model = reader.string()
            val deviceVersion = reader.string()
            val serialNumber = reader.string()

            LumixDeviceInfo(
                manufacturer = manufacturer,
                model = model,
                deviceVersion = deviceVersion,
                serialNumber = serialNumber,
                operationsSupported = operations,
            )
        } catch (t: Throwable) {
            // Разбор информационный: при несовпадении структуры просто
            // остаёмся без имени модели, а не ломаем подключение.
            null
        }
    }
}

/** Последовательное чтение little-endian значений с проверкой границ. */
private class PtpReader(private val data: ByteArray) {

    private var position = 0

    private fun requireBytes(count: Int) {
        if (count < 0 || position + count > data.size) {
            throw IllegalStateException("данные закончились на позиции $position")
        }
    }

    fun u8(): Int {
        requireBytes(1)
        return data[position++].toInt() and 0xFF
    }

    fun u16(): Int {
        requireBytes(2)
        val value = (data[position].toInt() and 0xFF) or
            ((data[position + 1].toInt() and 0xFF) shl 8)
        position += 2
        return value
    }

    fun u32(): Int {
        requireBytes(4)
        val value = (data[position].toInt() and 0xFF) or
            ((data[position + 1].toInt() and 0xFF) shl 8) or
            ((data[position + 2].toInt() and 0xFF) shl 16) or
            ((data[position + 3].toInt() and 0xFF) shl 24)
        position += 4
        return value
    }

    /** Строка PTP: длина в символах, затем UTF-16LE. */
    fun string(): String {
        val characters = u8()
        requireBytes(characters * 2)
        val text = String(data, position, characters * 2, Charsets.UTF_16LE)
        position += characters * 2
        return text
    }

    /** Массив: uint32 с количеством, затем элементы по uint16. */
    fun u16Array(): IntArray {
        val count = u32()
        if (count <= 0 || count > MAX_ARRAY_ELEMENTS) return IntArray(0)
        requireBytes(count * 2)
        return IntArray(count) { u16() }
    }

    companion object {
        private const val MAX_ARRAY_ELEMENTS = 4096
    }
}
