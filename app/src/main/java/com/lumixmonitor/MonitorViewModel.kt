package com.lumixmonitor

import android.app.Application
import android.graphics.Bitmap
import android.hardware.usb.UsbDevice
import android.util.Log
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.AndroidViewModel
import com.lumixmonitor.lumix.LiveviewLoop
import com.lumixmonitor.lumix.LiveviewSetting
import com.lumixmonitor.lumix.LumixCamera
import com.lumixmonitor.lumix.LumixCodec
import com.lumixmonitor.lumix.LumixProp
import com.lumixmonitor.lumix.LumixTables
import com.lumixmonitor.lumix.PropDesc
import com.lumixmonitor.ptp.PtpLog
import com.lumixmonitor.ptp.PtpUsbTransport
import com.lumixmonitor.usb.LumixUsbConnector
import java.util.concurrent.Executors

/** Состояние подключения к камере. */
sealed interface ConnectionState {
    data object Disconnected : ConnectionState
    data object Connecting : ConnectionState
    data class Connected(val deviceName: String) : ConnectionState
    data class Failed(val message: String) : ConnectionState
}

/** Текущие значения настроек камеры, как их показывает интерфейс. */
data class CameraSettings(
    val mode: String = "—",
    val iso: String = "—",
    val shutter: String = "—",
    val aperture: String = "—",
    val exposureComp: String = "—",
    val whiteBalance: String = "—",
    val photoStyle: String = "—",
    val aspect: String = "—",
    val quality: String = "—",
    val afMode: String = "—",
    val afZone: String = "—",
)

/** Инструменты видеоконтроля, накладываемые на изображение. */
data class MonitorTools(
    val focusPeaking: Boolean = false,
    val zebra: Boolean = false,
    val zebraThreshold: Float = 0.9f,
    val falseColor: Boolean = false,
    val showLevel: Boolean = true,
    val showHistogram: Boolean = true,
    val showFramelines: Boolean = false,
    val showCenterMarker: Boolean = true,
    val desqueeze: Float = 1.0f,
)

/**
 * Настройки камеры, которые интерфейс переключает по кругу.
 *
 * Для каждой указаны два кода свойства: propCode — для чтения описания
 * (текущее значение и список допустимых), paramCode — для записи.
 * Это особенность протокола Panasonic, а не ошибка.
 */
enum class SettingKind(
    val label: String,
    val propCode: Int,
    val paramCode: Int,
    val valueSize: Int,
) {
    ISO("ISO", LumixProp.ISO, LumixProp.ISO_PARAM, 4),
    SHUTTER("Выдержка", LumixProp.SHUTTER_SPEED, LumixProp.SHUTTER_SPEED_PARAM, 4),
    APERTURE("Диафрагма", LumixProp.APERTURE, LumixProp.APERTURE_PARAM, 2),
    EXPOSURE("EV", LumixProp.EXPOSURE, LumixProp.EXPOSURE_PARAM, 2),
    WHITE_BALANCE("ББ", LumixProp.WHITE_BALANCE, LumixProp.WHITE_BALANCE_PARAM, 2),
    PHOTO_STYLE("Стиль", LumixProp.PHOTO_STYLE, LumixProp.PHOTO_STYLE_PARAM, 2),
    // Соотношение сторон: камера режет под него кадр liveview, поэтому именно
    // оно определяет, какое разрешение она реально отдаёт.
    ASPECT("Кадр", LumixProp.IMAGE_MODE_ASPECT, LumixProp.IMAGE_MODE_ASPECT, 2),
    QUALITY("Формат", LumixProp.IMAGE_MODE_QUALITY, LumixProp.IMAGE_MODE_QUALITY, 2),
    // Режим и зона автофокуса. Свойство AF_AREA (0x02000070) камера отдаёт
    // составной структурой из вложенных описаний, поэтому читаем и пишем
    // каждое под-свойство напрямую: у них обычный формат со списком значений.
    AF_MODE("AF-режим", LumixProp.AF_AREA_MODE_PARAM, LumixProp.AF_AREA_MODE_PARAM, 2),
    AF_ZONE("AF-зона", LumixProp.AF_AREA_AREA_PARAM, LumixProp.AF_AREA_AREA_PARAM, 2),
}

/**
 * Состояние приложения и все обращения к камере.
 *
 * Любое обращение к камере блокирующее и уходит в отдельный поток:
 * PTP-обмен по USB нельзя выполнять в главном потоке.
 */
class MonitorViewModel(application: Application) : AndroidViewModel(application) {

    private val connector = LumixUsbConnector(application)

    private var transport: PtpUsbTransport? = null
    private var camera: LumixCamera? = null
    private var liveview: LiveviewLoop? = null

    /**
     * Разрешение последнего кадра — пишем в журнал только при смене.
     * Летит между потоком кадров и рабочим потоком, поэтому volatile.
     */
    @Volatile
    private var lastFrameSize: String? = null

    /** Время последнего перечитывания настроек: защита от флуда по USB. */
    private var lastSettingsRefreshMs = 0L

    private val io = Executors.newSingleThreadExecutor { runnable ->
        Thread(runnable, "lumix-io")
    }

    // ------------------------------------------------------------- состояние

    var connectionState by mutableStateOf<ConnectionState>(ConnectionState.Disconnected)
        private set

    var frame by mutableStateOf<Bitmap?>(null)
        private set

    var frameCount by mutableStateOf(0)
        private set

    var levelRoll by mutableStateOf<Float?>(null)
        private set

    var levelPitch by mutableStateOf<Float?>(null)
        private set

    var histogram by mutableStateOf<ByteArray?>(null)
        private set

    var settings by mutableStateOf(CameraSettings())
        private set

    var tools by mutableStateOf(MonitorTools())
        private set

    var liveviewModes by mutableStateOf<List<LiveviewSetting>>(emptyList())
        private set

    var activeLiveviewMode by mutableStateOf<LiveviewSetting?>(null)
        private set

    /**
     * Реальное разрешение приходящих кадров.
     *
     * Камера подтверждает запрошенный режим, но кадры может слать другого
     * размера, поэтому показываем то, что действительно приходит.
     */
    var actualFrameSize by mutableStateOf<String?>(null)
        private set

    /** Отчёт проверки живью: что камера отдаёт при каждом сочетании режима и кадра. */
    var probeReport by mutableStateOf<String?>(null)
        private set

    var statusText by mutableStateOf("Камера не подключена")
        private set

    var lastError by mutableStateOf<String?>(null)
        private set

    /** Имя модели, которое сообщила камера (например, «DC-S5»). */
    var cameraModel by mutableStateOf<String?>(null)
        private set

    /** Журнал диагностики. Показывается в приложении: телефон занят камерой, adb не подключить. */
    var diagnostics by mutableStateOf<List<String>>(emptyList())
        private set

    /** Последнее устройство — чтобы переподключаться без перетыкания кабеля. */
    private var lastDevice: UsbDevice? = null

    /** Подсказка, которую стоит показать, если камера не найдена в режиме тетеринга. */
    val tetherHint: String
        get() = "Проверьте на камере: Меню → USB → режим «PC(Tether)». " +
            "В режиме «PC(Storage)» камера не отвечает по PTP."

    // -------------------------------------------------------------- действия

    fun connect(device: UsbDevice) {
        if (connectionState is ConnectionState.Connecting) return
        connectionState = ConnectionState.Connecting
        lastError = null
        statusText = "Запрос доступа к USB…"
        lastDevice = device
        PtpLog.add("=== подключение: ${device.deviceName} ===")

        connector.requestPermission(device) { granted ->
            if (!granted) {
                connectionState = ConnectionState.Failed("Доступ к USB не выдан")
                statusText = "Доступ к USB не выдан"
                return@requestPermission
            }
            io.execute { openCamera(device) }
        }
    }

    /**
     * Переподключается к той же камере без физического перетыкания кабеля.
     *
     * Полностью закрывает прежнее соединение, заново открывает устройство,
     * сбрасывает состояние PTP и поднимает сессию и liveview заново.
     */
    fun reconnect() {
        // Устройство ищется заново, а не берётся из сохранённого объекта: если
        // камера переоткрылась — например, при смене USB-режима на самой камере
        // или после сбоя, — сохранённый UsbDevice указывает на уже не
        // существующее устройство, и обмен уходит в пустоту.
        val device = connector.findPtpDevices().firstOrNull() ?: lastDevice
        if (device == null) {
            statusText = "Камера не найдена — проверьте кабель и режим PC(Tether)"
            return
        }
        lastDevice = device
        if (connectionState is ConnectionState.Connecting) return

        connectionState = ConnectionState.Connecting
        lastError = null
        statusText = "Переподключение…"
        PtpLog.add("=== переподключение ===")

        io.execute {
            try {
                releaseQuietly()
                frame = null
                frameCount = 0
                levelRoll = null
                levelPitch = null
                histogram = null
                activeLiveviewMode = null
                liveviewModes = emptyList()

                // Разрешение могло быть отозвано — тогда запрашиваем заново.
                connector.requestPermission(device) { granted ->
                    if (!granted) {
                        connectionState = ConnectionState.Failed("Доступ к USB не выдан")
                        statusText = "Доступ к USB не выдан"
                    } else {
                        io.execute { openCamera(device) }
                    }
                }
            } catch (t: Throwable) {
                PtpLog.add("переподключение не удалось: $t")
                lastError = t.message
                connectionState = ConnectionState.Failed(t.message ?: "Ошибка переподключения")
                statusText = "Ошибка переподключения"
            }
        }
    }

    /** Обновляет снимок журнала диагностики для показа в интерфейсе. */
    fun refreshDiagnostics() {
        diagnostics = PtpLog.snapshot()
    }

    fun clearDiagnostics() {
        PtpLog.clear()
        diagnostics = emptyList()
    }

    private fun openCamera(device: UsbDevice) {
        try {
            statusText = "Открытие PTP-соединения…"
            val newTransport = connector.open(device)
            transport = newTransport

            val newCamera = LumixCamera(newTransport)
            newCamera.onDevicePropChanged = { propCode ->
                Log.d(TAG, "камера изменила свойство 0x%08X".format(propCode))
                scheduleSettingsRefresh()
            }

            statusText = "Открытие сессии…"
            newCamera.open()
            camera = newCamera
            cameraModel = newCamera.deviceInfo?.displayName

            // Узнаём, какие режимы liveview поддерживает камера, и берём самый крупный.
            val modes = try {
                newCamera.getRecommendedLiveviewSettings()
            } catch (t: Throwable) {
                Log.w(TAG, "не удалось получить список режимов liveview", t)
                emptyList()
            }
            liveviewModes = modes

            // Сначала включаем передачу, и только потом задаём режим.
            // До запуска камера записанный режим сбрасывает: проверка живью
            // показала, что при обратном порядке вместо 1024x576 она отдаёт
            // 640x360 — младший режим по умолчанию.
            statusText = "Включение liveview…"
            newCamera.startLiveview()
            startLiveviewLoop(newCamera)

            // Качество всегда на максимум: по умолчанию камера отдаёт младший режим.
            applyBestLiveviewMode(newCamera, modes)

            if (activeLiveviewMode == null) {
                activeLiveviewMode = try {
                    newCamera.getCurrentLiveviewSetting()
                } catch (t: Throwable) {
                    null
                }
            }

            connectionState = ConnectionState.Connected(device.deviceName)
            statusText = "Подключено"
            refreshSettings()
        } catch (t: Throwable) {
            Log.e(TAG, "не удалось подключиться", t)
            // Причина отказа обязательно попадает в журнал: logcat недоступен,
            // пока камера занимает единственный USB-порт телефона.
            PtpLog.add("подключение не удалось: ${t.javaClass.simpleName}: ${t.message}")
            t.stackTrace.take(8).forEach { frame -> PtpLog.add("    at $frame") }
            releaseQuietly()
            lastError = t.message
            connectionState = ConnectionState.Failed(t.message ?: "Неизвестная ошибка")
            statusText = "Ошибка подключения"
        }
    }

    fun disconnect() {
        releaseQuietly()
        frame = null
        frameCount = 0
        levelRoll = null
        levelPitch = null
        histogram = null
        actualFrameSize = null
        lastFrameSize = null
        connectionState = ConnectionState.Disconnected
        statusText = "Камера не подключена"
    }

    private fun releaseQuietly() {
        try {
            liveview?.stop()
        } catch (t: Throwable) {
            Log.w(TAG, "остановка потока", t)
        }
        liveview = null
        try {
            camera?.stopLiveview()
            camera?.close()
        } catch (t: Throwable) {
            Log.w(TAG, "закрытие сессии", t)
        }
        camera = null
        try {
            transport?.close()
        } catch (t: Throwable) {
            Log.w(TAG, "закрытие транспорта", t)
        }
        transport = null
    }

    /** Перечитывает текущие настройки камеры. */
    private fun refreshSettings() {
        val cam = camera ?: return
        try {
            val modeDesc = cam.getDevicePropDesc(LumixProp.CAMERA_MODE_MODE_POS, 2)
            val isoDesc = cam.getDevicePropDesc(LumixProp.ISO, 4)
                ?.also { propDescs[SettingKind.ISO] = it }
            val shutterDesc = cam.getDevicePropDesc(LumixProp.SHUTTER_SPEED, 4)
                ?.also { propDescs[SettingKind.SHUTTER] = it }
            val apertureDesc = cam.getDevicePropDesc(LumixProp.APERTURE, 2)
                ?.also { propDescs[SettingKind.APERTURE] = it }
            val exposureDesc = cam.getDevicePropDesc(LumixProp.EXPOSURE, 2)
                ?.also { propDescs[SettingKind.EXPOSURE] = it }
            val wbDesc = cam.getDevicePropDesc(LumixProp.WHITE_BALANCE, 2)
                ?.also { propDescs[SettingKind.WHITE_BALANCE] = it }
            val styleDesc = cam.getDevicePropDesc(LumixProp.PHOTO_STYLE, 2)
                ?.also { propDescs[SettingKind.PHOTO_STYLE] = it }
            val aspectDesc = cam.getDevicePropDesc(LumixProp.IMAGE_MODE_ASPECT, 2)
                ?.also { propDescs[SettingKind.ASPECT] = it }
            val qualityDesc = cam.getDevicePropDesc(LumixProp.IMAGE_MODE_QUALITY, 2)
                ?.also { propDescs[SettingKind.QUALITY] = it }
            val afDesc = cam.getDevicePropDesc(LumixProp.AF_AREA_MODE_PARAM, 2)
            val afModeDesc = afDesc?.also { propDescs[SettingKind.AF_MODE] = it }
            val afZoneDesc = cam.getDevicePropDesc(LumixProp.AF_AREA_AREA_PARAM, 2)
                ?.also { propDescs[SettingKind.AF_ZONE] = it }

            settings = CameraSettings(
                mode = modeDesc?.let {
                    LumixTables.nameOf(LumixTables.exposureMode, it.current)
                } ?: "—",
                iso = isoDesc?.let { LumixCodec.decodeIso(it.current) } ?: "—",
                shutter = shutterDesc?.let {
                    LumixCodec.decodeShutterSpeed(it.current)
                } ?: "—",
                aperture = apertureDesc?.let {
                    LumixCodec.formatAperture(LumixCodec.decodeAperture(it.current))
                } ?: "—",
                exposureComp = exposureDesc?.let {
                    LumixCodec.decodeExposureComp(it.current)
                } ?: "—",
                whiteBalance = wbDesc?.let {
                    LumixTables.nameOf(LumixTables.whiteBalance, it.current)
                } ?: "—",
                photoStyle = styleDesc?.let {
                    LumixTables.nameOf(LumixTables.photoStyle, it.current)
                } ?: "—",
                aspect = aspectDesc?.let {
                    LumixTables.nameOf(LumixTables.imageAspect, it.current)
                } ?: "—",
                quality = qualityDesc?.let {
                    LumixTables.nameOf(LumixTables.imageQuality, it.current)
                } ?: "—",
                afMode = afModeDesc?.let { describeOpaque(LumixTables.afMode, it) } ?: "—",
                afZone = afZoneDesc?.let { describeOpaque(LumixTables.afZone, it) } ?: "—",
            )
        } catch (t: Throwable) {
            Log.w(TAG, "не удалось прочитать настройки", t)
        }
    }

    fun capture() = onIo("Спуск затвора") { it.capture() }

    fun autoFocus() = onIo("Автофокус") { it.autoFocus() }

    fun focusNear(speed: Int) = onIo("Фокус ближе") { it.manualFocus("near", speed) }

    fun focusFar(speed: Int) = onIo("Фокус дальше") { it.manualFocus("far", speed) }

    fun setIso(value: Int) = onIo("ISO $value") { it.setIso(value) }

    fun setShutterSpeed(text: String) = onIo("Выдержка $text") { it.setShutterSpeed(text) }

    fun setAperture(fNumber: Double) = onIo("Диафрагма $fNumber") {
        it.setAperture(fNumber)
    }

    fun setExposureCompensation(text: String) = onIo("Экспокоррекция $text") {
        it.setExposureCompensation(text)
    }

    fun setWhiteBalance(code: Int) = onIo("Баланс белого") { it.setWhiteBalance(code) }

    fun setPhotoStyle(code: Int) = onIo("Photo Style") { it.setPhotoStyle(code) }

    /**
     * Смена режима liveview.
     *
     * Поток кадров при этом НЕ трогаем: камера применяет режим на ходу, а
     * остановка и повторный запуск передачи её подвешивали — после переключения
     * на 640x480 или 832x624 картинка замирала навсегда.
     */
    fun selectLiveviewMode(setting: LiveviewSetting) {
        io.execute {
            val cam = camera ?: return@execute
            try {
                statusText = "Смена режима liveview на ${setting.label}…"
                val applied = cam.setLiveviewSetting(setting)
                val actual = try {
                    cam.getCurrentLiveviewSetting()
                } catch (t: Throwable) {
                    PtpLog.add("чтение текущего режима не удалось: ${t.message}")
                    null
                }

                // Пишем размер кадра заново: видно, что камера отдаёт после смены.
                lastFrameSize = null

                activeLiveviewMode = actual ?: setting
                statusText = when {
                    !applied -> "Режим ${setting.label}: камера отказала"
                    actual != null && actual.pixels != setting.pixels ->
                        "Камера оставила режим ${actual.label}"
                    else -> "Режим liveview: ${setting.label}"
                }
            } catch (t: Throwable) {
                Log.w(TAG, "смена режима liveview не удалась", t)
                lastError = t.message
                statusText = "Смена режима: ${t.message}"
            }
        }
    }

    /**
     * Режимы, которых нет в списке камеры, но которые стоит попробовать:
     * камера сообщает только рекомендованные, а поддержать может больше.
     */
    val experimentalModes: List<LiveviewSetting>
        get() {
            val fs = liveviewModes.maxByOrNull { it.pixels }?.frameSize ?: 0
            return listOf(
                LiveviewSetting(1280, 720, fs, 30),
                LiveviewSetting(1920, 1080, fs, 30),
            )
        }

    /**
     * Проверяет, какое разрешение камера отдаёт на самом деле.
     *
     * Перебирает сочетания «соотношение сторон × режим живью» и записывает
     * реальный размер приходящих кадров. Один прогон вместо ручного перебора:
     * камера подтверждает режим, но кадры может слать совсем другого размера.
     */
    fun probeLiveview() {
        io.execute {
            val cam = camera ?: return@execute
            try {
                probeReport = "Проверка…"
                val report = StringBuilder()
                PtpLog.add("=== проверка живью ===")

                val modes = liveviewModes.ifEmpty {
                    listOf(
                        LiveviewSetting(640, 480, 32, 30),
                        LiveviewSetting(832, 624, 190, 30),
                        LiveviewSetting(1024, 768, 290, 30),
                    )
                }

                for ((aspectCode, aspectName) in PROBE_ASPECTS) {
                    cam.setDevicePropValue(LumixProp.IMAGE_MODE_ASPECT, 2, aspectCode)
                    Thread.sleep(PROBE_SETTLE_MS)
                    for (mode in modes) {
                        cam.setLiveviewSetting(mode)
                        lastFrameSize = null
                        Thread.sleep(PROBE_FRAME_WAIT_MS)
                        val line = "$aspectName + ${mode.label}: кадр ${lastFrameSize ?: "нет"}"
                        report.append(line).append('\n')
                        PtpLog.add(line)
                        probeReport = report.toString()
                    }
                }

                // Возвращаем крупный режим и исходное соотношение сторон.
                modes.maxByOrNull { it.pixels }?.let { cam.setLiveviewSetting(it) }
                cam.setDevicePropValue(
                    LumixProp.IMAGE_MODE_ASPECT,
                    2,
                    PROBE_ASPECTS.first().first,
                )
                statusText = "Проверка живью завершена"
            } catch (t: Throwable) {
                Log.w(TAG, "проверка живью не удалась", t)
                PtpLog.add("проверка живью не удалась: ${t.message}")
                statusText = "Проверка живью: ${t.message}"
            }
        }
    }

    fun clearProbeReport() {
        probeReport = null
    }

    /** Пишет в журнал списки допустимых значений по всем настройкам. */
    fun logAllowedValues() {        io.execute {
            val cam = camera ?: return@execute
            PtpLog.add("=== допустимые значения ===")
            SettingKind.values().forEach { kind ->
                try {
                    val desc = cam.getDevicePropDesc(kind.propCode, kind.valueSize)
                    if (desc == null) {
                        PtpLog.add("${kind.label}: камера не ответила")
                    } else {
                        PtpLog.add(
                            "${kind.label}: текущее=${describeValue(kind, desc.current)}, " +
                                "варианты=[${desc.allowed.joinToString(",")}]",
                        )
                    }
                } catch (t: Throwable) {
                    PtpLog.add("${kind.label}: ${t.message}")
                }
            }
        }
    }

    /**
     * Сырые структуры свойств автофокуса.
     *
     * Камера отдаёт их не обычным списком значений, а составной структурой.
     * Без сырых байт нельзя понять, есть ли там точка фокуса, которой можно
     * управлять с телефона. Выкладываем в журнал — по ним и разберём.
     */
    fun dumpAfStructure() {
        io.execute {
            val cam = camera ?: return@execute
            PtpLog.add("=== структура автофокуса ===")
            AF_PROP_CODES.forEach { code ->
                try {
                    val raw = cam.getDevicePropDescRaw(code)
                    if (raw == null) {
                        PtpLog.add("0x%08X: камера не ответила".format(code))
                    } else {
                        PtpLog.add(
                            "0x%08X (%d байт): %s".format(
                                code,
                                raw.size,
                                raw.joinToString(" ") { "%02X".format(it) },
                            ),
                        )
                    }
                } catch (t: Throwable) {
                    PtpLog.add("0x%08X: %s".format(code, t.message))
                }
            }
            statusText = "Структура автофокуса записана в журнал"
        }
    }

    /**
     * Ставит самый крупный режим живью и проверяет, что камера его приняла.
     *
     * Камера подтверждает запись сразу, но применяет не мгновенно, а до запуска
     * передачи и вовсе сбрасывает режим на младший. Поэтому значение читается
     * обратно, и при неудаче попытка повторяется.
     */
    private fun applyBestLiveviewMode(cam: LumixCamera, modes: List<LiveviewSetting>) {
        val best = modes.maxByOrNull { it.pixels } ?: return

        repeat(LIVEVIEW_MODE_ATTEMPTS) { attempt ->
            cam.setLiveviewSetting(best)
            val actual = try {
                cam.getCurrentLiveviewSetting()
            } catch (t: Throwable) {
                PtpLog.add("чтение режима живью не удалось: ${t.message}")
                null
            }

            if (actual != null && actual.pixels >= best.pixels) {
                activeLiveviewMode = actual
                lastFrameSize = null
                PtpLog.add("режим живью: ${actual.label}")
                return
            }

            PtpLog.add(
                "режим живью: попытка ${attempt + 1} из $LIVEVIEW_MODE_ATTEMPTS — " +
                    "камера сообщила ${actual?.label ?: "нет ответа"}",
            )
            Thread.sleep(LIVEVIEW_MODE_RETRY_MS)
        }

        // Подтвердить не вышло — оставляем запрошенный, строка «кадр» покажет правду.
        activeLiveviewMode = best
        lastFrameSize = null
    }

    /** Создаёт и запускает цикл получения кадров. */
    private fun startLiveviewLoop(cam: LumixCamera) {        val loop = LiveviewLoop(
            camera = cam,
            onFrame = { bitmap, liveviewFrame ->
                frame = bitmap
                frameCount += 1
                levelRoll = liveviewFrame.rollDegrees
                levelPitch = liveviewFrame.pitchDegrees
                liveviewFrame.histogram?.let { histogram = it }
                logFrameSize(bitmap)
            },
            onError = { t ->
                lastError = t.message
                statusText = "Поток прерван: ${t.message}"
            },
            onStopped = {
                if (connectionState is ConnectionState.Connected) {
                    statusText = "Поток остановлен"
                }
            },
        )
        liveview = loop
        loop.start()
    }

    /** Пишет в журнал реальное разрешение кадров — это и есть настоящее качество. */
    private fun logFrameSize(bitmap: Bitmap) {
        val size = "${bitmap.width}x${bitmap.height}"
        if (size != lastFrameSize) {
            lastFrameSize = size
            actualFrameSize = size
            PtpLog.add("кадр liveview: $size")
        }
    }

    /**
     * Перечитывает настройки не чаще раза в секунду.
     *
     * Камера во время liveview сыплет событиями об изменении свойств, и без
     * ограничения опрос (семь USB-команд на круг) забивал шину и мешал потоку.
     */
    private fun scheduleSettingsRefresh() {
        val now = System.currentTimeMillis()
        if (now - lastSettingsRefreshMs < SETTINGS_REFRESH_MIN_INTERVAL_MS) return
        lastSettingsRefreshMs = now
        io.execute { refreshSettings() }
    }

    /**
     * Выполняет операцию с камерой в отдельном потоке.
     * Ошибка не роняет приложение, а показывается в статусе.
     */
    private fun onIo(label: String, action: (LumixCamera) -> Boolean) {
        io.execute {
            val cam = camera ?: return@execute
            try {
                val ok = action(cam)
                statusText = if (ok) "$label: выполнено" else "$label: камера отказала"
                if (ok) refreshSettings()
            } catch (t: Throwable) {
                Log.w(TAG, "$label не удалось", t)
                lastError = t.message
                statusText = "$label: ${t.message}"
            }
        }
    }

    // ------------------------------------------------------- инструменты

    fun updateTools(transform: (MonitorTools) -> MonitorTools) {
        tools = transform(tools)
    }

    // -------------------------------------------------------- список устройств

    /** Подключённые устройства с PTP-интерфейсом. */
    fun findDevices(): List<UsbDevice> = connector.findPtpDevices()

    // ------------------------------------------------- настройки по кругу

    /**
     * Переключает настройку на соседнее значение.
     *
     * Список допустимых значений берётся у камеры, но порядок в нём
     * произвольный — шаг по нему уводил выдержку и диафрагму в разные стороны.
     * Поэтому список сортируется по количеству света, и «вперёд» всегда
     * означает «больше света»: одинаково для ISO, выдержки, диафрагмы и EV.
     */
    fun cycleSetting(kind: SettingKind, forward: Boolean = true) {
        io.execute {
            val cam = camera ?: return@execute
            try {
                val desc = propDescs[kind]
                    ?: cam.getDevicePropDesc(kind.propCode, kind.valueSize)
                        ?.also { propDescs[kind] = it }
                    ?: run {
                        statusText = "${kind.label}: камера не сообщила значения"
                        return@execute
                    }

                if (desc.allowed.isEmpty()) {
                    statusText = "${kind.label}: значение только для чтения"
                    return@execute
                }

                val ordered = desc.allowed.sortedBy { lightKey(kind, it) }
                val currentIndex = ordered.indexOf(desc.current)
                val size = ordered.size
                val nextIndex = if (currentIndex < 0) {
                    0
                } else {
                    val step = if (forward) 1 else -1
                    ((currentIndex + step) % size + size) % size
                }

                val target = ordered[nextIndex]
                if (cam.setDevicePropValue(kind.paramCode, kind.valueSize, target)) {
                    statusText = "${kind.label}: ${describeValue(kind, target)}"
                    // Обновляем кэш сразу: иначе следующий тап считается от старого
                    // значения, и настройка прыгает не туда.
                    propDescs[kind] = desc.copy(current = target)
                    refreshSettings()
                }
            } catch (t: Throwable) {
                Log.w(TAG, "переключение ${kind.label} не удалось", t)
                lastError = t.message
                statusText = "${kind.label}: ${t.message}"
            }
        }
    }

    /**
     * Ключ порядка значений: от тёмного к светлому.
     *
     * Для баланса белого и стиля смысла в «свете» нет — там порядок камеры.
     */
    private fun lightKey(kind: SettingKind, code: Int): Double = when (kind) {
        SettingKind.ISO -> if (code < 0) -1.0 else code.toDouble()
        SettingKind.SHUTTER -> shutterSeconds(code)
        // Меньше f — шире диафрагма и больше света, поэтому знак перевёрнут.
        SettingKind.APERTURE -> -code / 10.0
        SettingKind.EXPOSURE -> exposureStops(code)
        else -> code.toDouble()
    }

    /** Выдержка в секундах по внутреннему коду камеры. */
    private fun shutterSeconds(code: Int): Double = when {
        code == 0x0FFFFFFF -> -1.0
        code == 0xFFFFFFFF.toInt() -> Double.MAX_VALUE
        else -> {
            val isSeconds = (code and 0x80000000.toInt()) != 0
            val magnitude = code and 0x7FFFFFFF
            when {
                magnitude == 0 -> 0.0
                isSeconds -> magnitude / 1000.0
                else -> 1000.0 / magnitude
            }
        }
    }

    /** Экспокоррекция в ступенях EV. */
    private fun exposureStops(code: Int): Double {
        val negative = (code and 0x8000) != 0
        val ev = (code and 0x7FFF) / 3.0
        return if (negative) -ev else ev
    }

    /** Читаемое представление значения настройки. */
    fun describeValue(kind: SettingKind, value: Int): String = when (kind) {
        SettingKind.ISO -> LumixCodec.decodeIso(value)
        SettingKind.SHUTTER -> LumixCodec.decodeShutterSpeed(value) ?: "—"
        SettingKind.APERTURE -> LumixCodec.formatAperture(LumixCodec.decodeAperture(value))
        SettingKind.EXPOSURE -> LumixCodec.decodeExposureComp(value)
        SettingKind.WHITE_BALANCE ->
            LumixTables.nameOf(LumixTables.whiteBalance, value) ?: "0x%04X".format(value)
        SettingKind.PHOTO_STYLE ->
            LumixTables.nameOf(LumixTables.photoStyle, value) ?: "0x%04X".format(value)
        SettingKind.ASPECT ->
            LumixTables.nameOf(LumixTables.imageAspect, value) ?: "0x%04X".format(value)
        SettingKind.QUALITY ->
            LumixTables.nameOf(LumixTables.imageQuality, value) ?: "0x%04X".format(value)
        SettingKind.AF_MODE, SettingKind.AF_ZONE -> "0x%04X".format(value)
    }

    /**
     * Значение, для которого нет таблицы имён.
     * Показываем номер варианта: он понятнее сырого кода камеры.
     */
    private fun describeOpaque(table: List<Pair<Int, String>>, desc: PropDesc): String {
        LumixTables.nameOf(table, desc.current)?.let { return it }
        val index = desc.allowed.indexOf(desc.current)
        val code = "0x%04X".format(desc.current)
        return if (index >= 0) "${index + 1}/${desc.allowed.size} ($code)" else code
    }

    /** Кэш описаний свойств: текущее значение и список допустимых. */
    private val propDescs = mutableMapOf<SettingKind, PropDesc>()

    fun clearError() {
        lastError = null
    }

    override fun onCleared() {
        super.onCleared()
        releaseQuietly()
        connector.release()
        io.shutdownNow()
    }

    companion object {
        private const val TAG = "MonitorViewModel"

        /** Не чаще раза в секунду: иначе опрос настроек забивает USB и душит liveview. */
        private const val SETTINGS_REFRESH_MIN_INTERVAL_MS = 1_000L

        /** Сколько раз пробовать поставить максимальный режим живью. */
        private const val LIVEVIEW_MODE_ATTEMPTS = 3

        /** Пауза между попытками: камера применяет режим не мгновенно. */
        private const val LIVEVIEW_MODE_RETRY_MS = 400L

        /** Соотношения сторон, между которыми перебирает проверка живью. */
        private val PROBE_ASPECTS = listOf(1 to "4:3", 2 to "3:2", 3 to "16:9")

        /** Пауза после смены настройки кадра. */
        private const val PROBE_SETTLE_MS = 400L

        /** Сколько ждать кадр после смены режима живью. */
        private const val PROBE_FRAME_WAIT_MS = 900L

        /**
         * Свойства автофокуса Panasonic: сам блок AF и его под-свойства
         * (режим, зона и следующие за ними — возможно, там и точка фокуса).
         */
        private val AF_PROP_CODES = listOf(
            0x02000070, 0x02000071, 0x02000072, 0x02000073, 0x02000074, 0x02000075, 0x02000076,
        )
    }
}
