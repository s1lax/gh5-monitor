package com.lumixmonitor.lumix

import android.util.Log
import com.lumixmonitor.ptp.PtpException
import com.lumixmonitor.ptp.PtpLog
import com.lumixmonitor.ptp.PtpOp
import com.lumixmonitor.ptp.PtpResponseCode
import com.lumixmonitor.ptp.PtpUsbTransport
import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * Драйвер камеры Panasonic Lumix (проверен на S5).
 *
 * Важная особенность протокола: камера **игнорирует вендорские команды, пока не
 * открыта стандартная PTP-сессия**. Поэтому подключение идёт в два шага —
 * сначала стандартная OpenSession (0x1002), затем вендорская Panasonic (0x9102).
 *
 * Все вызовы синхронные и блокирующие — вызывать только из фонового потока.
 */
class LumixCamera(private val transport: PtpUsbTransport) {

    /** Уведомление об изменении свойства на камере (в т.ч. дисками вручную). */
    var onDevicePropChanged: ((Int) -> Unit)? = null

    /** Уведомление о появлении нового файла. */
    var onObjectAdded: ((Int) -> Unit)? = null

    private var sessionOpen = false

    val isSessionOpen: Boolean get() = sessionOpen

    /** Информация о камере, полученная стандартным запросом GetDeviceInfo. */
    var deviceInfo: LumixDeviceInfo? = null
        private set

    /** Имя модели, если камера его сообщила. */
    val modelName: String? get() = deviceInfo?.model?.takeIf { it.isNotBlank() }

    // ------------------------------------------------------------ жизненный цикл

    fun open() {
        transport.eventListener = { container ->
            when (container.code) {
                LumixEvent.DEVICE_PROP_CHANGED -> {
                    PtpLog.add("свойство изменилось: 0x%08X".format(container.firstParameter()))
                    onDevicePropChanged?.invoke(container.firstParameter())
                }
                LumixEvent.OBJECT_ADDED -> {
                    PtpLog.add("новый файл: ${container.firstParameter()}")
                    onObjectAdded?.invoke(container.firstParameter())
                }
                else -> PtpLog.add("событие 0x%04X".format(container.code))
            }
        }

        // Шаг 1: стандартная PTP-сессия. Без неё камера не отвечает на
        // вендорские команды.
        openSessionWithRetry(
            "OpenSession (0x1002)",
            PtpOp.OPEN_SESSION,
            intArrayOf(STANDARD_SESSION_ID),
        )

        // Шаг 2: сведения о камере. Порядок как в рабочем референсе:
        // GetDeviceInfo идёт между стандартной и вендорской сессиями.
        readDeviceInfo()

        // Шаг 3: вендорская сессия Panasonic.
        openSessionWithRetry(
            "OpenSession (0x9102)",
            LumixOp.OPEN_SESSION,
            intArrayOf(LumixParam.SESSION_ID),
        )

        sessionOpen = true
    }

    /**
     * Открывает сессию, повторяя команду при молчании камеры.
     *
     * Первая команда после захвата USB-интерфейса может потеряться: камера ещё
     * не готова её принять. Молчание на первую команду — не приговор, поэтому
     * она повторяется с паузой. Повтор безопасен: если сессия всё-таки
     * открылась, камера ответит SessionAlreadyOpen, и это принимается как успех.
     */
    private fun openSessionWithRetry(label: String, opcode: Int, parameters: IntArray) {
        var lastError: Throwable? = null

        for (attempt in 1..OPEN_SESSION_ATTEMPTS) {
            try {
                val response = transport.sendCommand(
                    opcode = opcode,
                    parameters = parameters,
                    expected = intArrayOf(
                        PtpResponseCode.OK,
                        PtpResponseCode.SESSION_ALREADY_OPEN,
                    ),
                    timeoutMs = SESSION_TIMEOUT_MS,
                )
                PtpLog.add(
                    "$label -> ${PtpResponseCode.name(response.resCode)} " +
                        "(попытка $attempt из $OPEN_SESSION_ATTEMPTS)",
                )
                if (response.resCode != PtpResponseCode.OK &&
                    response.resCode != PtpResponseCode.SESSION_ALREADY_OPEN
                ) {
                    throw PtpException("$label: ${PtpResponseCode.name(response.resCode)}")
                }
                return
            } catch (t: Throwable) {
                lastError = t
                PtpLog.add(
                    "$label: попытка $attempt из $OPEN_SESSION_ATTEMPTS " +
                        "не удалась — ${t.message}",
                )
                if (attempt < OPEN_SESSION_ATTEMPTS) {
                    try {
                        Thread.sleep(OPEN_SESSION_RETRY_DELAY_MS)
                    } catch (_: InterruptedException) {
                        Thread.currentThread().interrupt()
                        break
                    }
                }
            }
        }

        throw PtpException(
            "$label: камера не ответила после $OPEN_SESSION_ATTEMPTS попыток",
            lastError,
        )
    }

    private fun readDeviceInfo() {
        try {
            val response = transport.receiveData(
                opcode = PtpOp.GET_DEVICE_INFO,
                maxBytes = DEVICE_INFO_MAX_BYTES,
            )
            if (response.resCode != PtpResponseCode.OK) {
                PtpLog.add("GetDeviceInfo -> ${PtpResponseCode.name(response.resCode)}")
                return
            }
            val info = LumixDeviceInfoParser.parse(response.data)
            deviceInfo = info
            if (info != null) {
                PtpLog.add(
                    "камера: ${info.displayName}, прошивка ${info.deviceVersion}, " +
                        "операций ${info.operationsSupported.size}",
                )
            } else {
                PtpLog.add("GetDeviceInfo получен, но не разобран (${response.data.size} байт)")
            }
        } catch (t: Throwable) {
            PtpLog.add("GetDeviceInfo не удался: ${t.message}")
        }
    }

    fun close() {
        if (!sessionOpen) return
        try {
            transport.sendCommand(
                opcode = LumixOp.CLOSE_SESSION,
                parameters = intArrayOf(LumixParam.SESSION_ID),
                expected = intArrayOf(
                    PtpResponseCode.OK,
                    PtpResponseCode.SESSION_NOT_OPEN,
                ),
            )
        } catch (t: Throwable) {
            PtpLog.add("CloseSession не удался: ${t.message}")
        } finally {
            sessionOpen = false
        }
    }

    // ------------------------------------------------------------------ liveview

    /** Включает передачу liveview на камере. Кадры затем забираются getLiveviewImage(). */
    fun startLiveview() {
        val response = transport.sendCommand(
            opcode = LumixOp.LIVEVIEW,
            parameters = intArrayOf(LumixParam.LIVEVIEW_START),
        )
        requireOk(response.resCode, "включение liveview")
    }

    fun stopLiveview() {
        try {
            transport.sendCommand(
                opcode = LumixOp.LIVEVIEW,
                parameters = intArrayOf(LumixParam.LIVEVIEW_STOP),
                expected = intArrayOf(
                    PtpResponseCode.OK,
                    PtpResponseCode.DEVICE_BUSY,
                ),
            )
        } catch (t: Throwable) {
            PtpLog.add("выключение liveview: ${t.message}")
        }
    }

    /**
     * Забирает один кадр liveview.
     *
     * Ответ DeviceBusy — нормальная ситуация (камера занята, например при
     * автофокусе), а не ошибка: в этом случае возвращается null.
     */
    fun getLiveviewImage(timeoutMs: Int = LIVEVIEW_TIMEOUT_MS): LiveviewFrame? {
        val response = transport.receiveData(
            opcode = LumixOp.LIVEVIEW_IMAGE,
            expected = intArrayOf(
                PtpResponseCode.OK,
                PtpResponseCode.DEVICE_BUSY,
            ),
            maxBytes = LIVEVIEW_MAX_BYTES,
            timeoutMs = timeoutMs,
        )

        if (response.resCode != PtpResponseCode.OK) return null
        if (response.data.isEmpty()) return null
        return LiveviewFrameParser.parse(response.data)
    }

    /** Список режимов liveview, которые рекомендует камера. */
    fun getRecommendedLiveviewSettings(): List<LiveviewSetting> {
        val response = transport.receiveData(
            opcode = LumixOp.GET_LIVEVIEW_SETTINGS,
            parameters = intArrayOf(LumixParam.LIVEVIEW_RECOM_IMG),
            maxBytes = SETTINGS_MAX_BYTES,
        )
        requireOk(response.resCode, "GetLiveviewSettings (список режимов)")
        val payload = response.data
        if (payload.size < 12) return emptyList()

        val bb = ByteBuffer.wrap(payload).order(ByteOrder.LITTLE_ENDIAN)
        bb.int // propCode
        bb.int // dataSize
        val count = bb.short.toInt() and 0xFFFF
        bb.short // structSize

        val result = ArrayList<LiveviewSetting>(count)
        repeat(count) {
            if (bb.remaining() < 8) return@repeat
            val height = bb.short.toInt() and 0xFFFF
            val width = bb.short.toInt() and 0xFFFF
            val frameSize = bb.short.toInt() and 0xFFFF
            val fps = bb.short.toInt() and 0xFFFF
            result.add(LiveviewSetting(width, height, frameSize, fps))
        }
        PtpLog.add(
            "режимы liveview: " + result.joinToString {
                "${it.width}x${it.height}@${it.fps}fps fs=${it.frameSize}"
            },
        )
        return result
    }

    /** Текущий активный режим liveview. */
    fun getCurrentLiveviewSetting(): LiveviewSetting? {
        val response = transport.receiveData(
            opcode = LumixOp.GET_LIVEVIEW_SETTINGS,
            parameters = intArrayOf(LumixParam.LIVEVIEW_TRANS_IMG),
            maxBytes = SETTINGS_MAX_BYTES,
        )
        requireOk(response.resCode, "GetLiveviewSettings (текущий режим)")
        val payload = response.data
        if (payload.size < 16) return null

        val bb = ByteBuffer.wrap(payload).order(ByteOrder.LITTLE_ENDIAN)
        bb.int // propCode
        bb.int // dataSize
        val height = bb.short.toInt() and 0xFFFF
        val width = bb.short.toInt() and 0xFFFF
        val frameSize = bb.short.toInt() and 0xFFFF
        val fps = bb.short.toInt() and 0xFFFF
        return LiveviewSetting(width, height, frameSize, fps)
    }

    /** Устанавливает режим liveview. Значение должно быть из списка рекомендованных. */
    fun setLiveviewSetting(setting: LiveviewSetting): Boolean {
        val payload = ByteBuffer.allocate(16).order(ByteOrder.LITTLE_ENDIAN)
        payload.putInt(LumixParam.LIVEVIEW_TRANS_IMG)
        payload.putInt(8)
        payload.putShort(setting.height.toShort())
        payload.putShort(setting.width.toShort())
        payload.putShort(setting.frameSize.toShort())
        payload.putShort(setting.fps.toShort())

        val response = transport.sendData(
            opcode = LumixOp.SET_LIVEVIEW_SETTINGS,
            parameters = intArrayOf(LumixParam.LIVEVIEW_TRANS_IMG),
            payload = payload.array(),
        )
        PtpLog.add(
            "SetLiveviewSettings ${setting.width}x${setting.height} fs=${setting.frameSize} " +
                "fps=${setting.fps} -> ${PtpResponseCode.name(response.resCode)}",
        )
        return response.resCode == PtpResponseCode.OK
    }

    // ---------------------------------------------------------------- свойства

    /**
     * Сырое описание свойства, без разбора.
     *
     * Нужно для нестандартных структур: свойство автофокуса камера отдаёт
     * не обычным списком значений, и без сырых байт его не разобрать.
     */
    fun getDevicePropDescRaw(propCode: Int): ByteArray? {
        val response = transport.receiveData(
            opcode = LumixOp.GET_DEVICE_PROP_DESC,
            parameters = intArrayOf(propCode),
            maxBytes = PROP_DESC_MAX_BYTES,
        )
        if (response.resCode != PtpResponseCode.OK) return null
        return response.data
    }

    /**
     * Читает описание свойства: текущее значение и список допустимых.
     * @return описание или null, если камера его не отдала.
     */
    fun getDevicePropDesc(propCode: Int, valueSize: Int): PropDesc? {
        val response = transport.receiveData(
            opcode = LumixOp.GET_DEVICE_PROP_DESC,
            parameters = intArrayOf(propCode),
            maxBytes = PROP_DESC_MAX_BYTES,
        )
        if (response.resCode != PtpResponseCode.OK) {
            PtpLog.add(
                "GetDevicePropDesc 0x%08X -> %s".format(
                    propCode, PtpResponseCode.name(response.resCode),
                ),
            )
            return null
        }
        val payload = response.data
        if (payload.size < 12) return null

        val bb = ByteBuffer.wrap(payload).order(ByteOrder.LITTLE_ENDIAN)
        bb.int // devicePropCode
        val headerLength = bb.int

        // Смещение значения: headerLength выражен в 32-битных словах,
        // плюс два слова заголовка (propCode и headerLength).
        val valueOffset = headerLength * 4 + 8
        if (valueOffset + valueSize > payload.size) return null

        bb.position(valueOffset)
        val current = readUnsigned(bb, valueSize)

        // Дальше идёт счётчик значений, и только потом сам список. Без учёта
        // счётчика в список попадал хвост структуры: у ISO после 51200 шли
        // мусорные 33554466, 20, 20, 65537 — и перебор уезжал на них.
        val countPosition = bb.position()
        val count = if (bb.remaining() >= 2) readUnsigned(bb, 2) else 0
        val fits = count in 1..MAX_ALLOWED_VALUES && count * valueSize <= bb.remaining()

        if (fits) {
            val allowed = IntArray(count) { readUnsigned(bb, valueSize) }
            // Текущее значение обязано быть в списке. Если его там нет —
            // формат понят неверно, и лучше прочитать по-старому.
            if (allowed.contains(current)) return PropDesc(current = current, allowed = allowed)
        }

        bb.position(countPosition)
        val fallback = IntArray(bb.remaining() / valueSize) { readUnsigned(bb, valueSize) }
        return PropDesc(current = current, allowed = fallback)
    }

    /** Устанавливает значение свойства. */
    fun setDevicePropValue(propCode: Int, valueSize: Int, value: Int): Boolean {        val payload = ByteBuffer.allocate(8 + valueSize).order(ByteOrder.LITTLE_ENDIAN)
        payload.putInt(propCode)
        payload.putInt(valueSize)
        when (valueSize) {
            1 -> payload.put(value.toByte())
            2 -> payload.putShort(value.toShort())
            4 -> payload.putInt(value)
            else -> throw IllegalArgumentException("неподдерживаемый размер значения: $valueSize")
        }

        val response = transport.sendData(
            opcode = LumixOp.SET_DEVICE_PROP_VALUE,
            parameters = intArrayOf(propCode),
            payload = payload.array(),
            expected = intArrayOf(
                PtpResponseCode.OK,
                PtpResponseCode.DEVICE_BUSY,
            ),
        )
        if (response.resCode != PtpResponseCode.OK) {
            PtpLog.add(
                "SetDevicePropValue 0x%08X=%d -> %s".format(
                    propCode, value, PtpResponseCode.name(response.resCode),
                ),
            )
            return false
        }
        return true
    }

    private fun readUnsigned(bb: ByteBuffer, valueSize: Int): Int = when (valueSize) {
        1 -> bb.get().toInt() and 0xFF
        2 -> bb.short.toInt() and 0xFFFF
        4 -> bb.int
        else -> 0
    }

    // ------------------------------------------------- удобные установки

    fun setIso(iso: Int): Boolean = setDevicePropValue(LumixProp.ISO_PARAM, 4, iso)

    fun setIsoAuto(): Boolean =
        setDevicePropValue(LumixProp.ISO_PARAM, 4, LumixCodec.ISO_AUTO)

    fun setShutterSpeed(text: String): Boolean {
        val encoded = LumixCodec.encodeShutterSpeed(text) ?: return false
        return setDevicePropValue(LumixProp.SHUTTER_SPEED_PARAM, 4, encoded)
    }

    fun setAperture(fNumber: Double): Boolean =
        setDevicePropValue(LumixProp.APERTURE_PARAM, 2, LumixCodec.encodeAperture(fNumber))

    fun setExposureCompensation(text: String): Boolean {
        val encoded = LumixCodec.encodeExposureComp(text) ?: return false
        return setDevicePropValue(LumixProp.EXPOSURE_PARAM, 2, encoded)
    }

    fun setWhiteBalance(code: Int): Boolean =
        setDevicePropValue(LumixProp.WHITE_BALANCE_PARAM, 2, code)

    fun setColorTemperature(kelvin: Int): Boolean =
        setDevicePropValue(LumixProp.WHITE_BALANCE_K_SET, 2, kelvin)

    fun setPhotoStyle(code: Int): Boolean =
        setDevicePropValue(LumixProp.PHOTO_STYLE_PARAM, 2, code)

    fun setImageAspect(code: Int): Boolean =
        setDevicePropValue(LumixProp.IMAGE_MODE_ASPECT, 2, code)

    // ----------------------------------------------------------------- действия

    /** Спуск затвора. */
    fun capture(): Boolean {
        val response = transport.sendCommand(
            opcode = LumixOp.INITIATE_CAPTURE,
            parameters = intArrayOf(LumixParam.INITIATE_CAPTURE),
            expected = intArrayOf(
                PtpResponseCode.OK,
                PtpResponseCode.DEVICE_BUSY,
            ),
        )
        return response.resCode == PtpResponseCode.OK
    }

    /** Автофокус. */
    fun autoFocus(): Boolean {
        val response = transport.sendCommand(
            opcode = LumixOp.CTRL_LIVEVIEW,
            parameters = intArrayOf(LumixParam.AUTO_FOCUS),
            expected = intArrayOf(
                PtpResponseCode.OK,
                PtpResponseCode.DEVICE_BUSY,
            ),
        )
        return response.resCode == PtpResponseCode.OK
    }

    /**
     * Ручная фокусировка.
     * @param direction "near" или "far"
     * @param speed 1 (медленно) или 2 (быстро)
     */
    fun manualFocus(direction: String, speed: Int): Boolean {
        val mode = when {
            direction == "far" && speed == 1 -> 2
            direction == "far" && speed == 2 -> 1
            direction == "near" && speed == 1 -> 3
            direction == "near" && speed == 2 -> 4
            else -> return false
        }

        val payload = ByteBuffer.allocate(10).order(ByteOrder.LITTLE_ENDIAN)
        payload.putInt(LumixParam.MANUAL_FOCUS_PROP)
        payload.putInt(2)
        payload.putShort(mode.toShort())

        val response = transport.sendData(
            opcode = LumixOp.MANUAL_FOCUS_DRIVE,
            parameters = intArrayOf(LumixParam.MANUAL_FOCUS_PROP),
            payload = payload.array(),
            expected = intArrayOf(
                PtpResponseCode.OK,
                PtpResponseCode.DEVICE_BUSY,
            ),
        )
        return response.resCode == PtpResponseCode.OK
    }

    // ------------------------------------------------------------------ служебное

    private fun requireOk(resCode: Int, operation: String) {
        if (resCode != PtpResponseCode.OK) {
            throw PtpException(
                "$operation: ${PtpResponseCode.name(resCode)}",
            )
        }
    }

    companion object {
        private const val TAG = "LumixCamera"

        /** Стандартный PTP использует номер сессии 1. */
        private const val STANDARD_SESSION_ID = 0x00000001

        /** Liveview-кадр может заставить подождать: камера занята автофокусом. */
        const val LIVEVIEW_TIMEOUT_MS = 2_000

        private const val DEVICE_INFO_MAX_BYTES = 64 * 1024
        private const val PROP_DESC_MAX_BYTES = 64 * 1024
        private const val SETTINGS_MAX_BYTES = 4 * 1024

        /**
         * Таймаут команд открытия сессии. На исправной камере ответ приходит за
         * миллисекунды, поэтому запас нужен только на пробуждение после сброса.
         */
        private const val SESSION_TIMEOUT_MS = 3_000

        /** Повтор открытия сессии: первая команда после захвата может потеряться. */
        private const val OPEN_SESSION_ATTEMPTS = 2
        private const val OPEN_SESSION_RETRY_DELAY_MS = 700L

        /** Верхняя граница кадра liveview — как в рабочем референсе. */
        private const val LIVEVIEW_MAX_BYTES = 1_000_000

        /** Разумный предел длины списка допустимых значений свойства. */
        private const val MAX_ALLOWED_VALUES = 512
    }
}

/** Описание свойства: текущее и допустимые значения. */
data class PropDesc(
    val current: Int,
    val allowed: IntArray,
) {
    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other !is PropDesc) return false
        return current == other.current && allowed.contentEquals(other.allowed)
    }

    override fun hashCode(): Int = 31 * current + allowed.contentHashCode()
}
