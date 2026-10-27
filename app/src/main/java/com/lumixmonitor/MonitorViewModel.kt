package com.lumixmonitor

import android.app.Application
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Paint
import android.net.Uri
import android.content.ContentValues
import android.os.Build
import android.provider.MediaStore
import android.hardware.usb.UsbDevice
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
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
import com.lumixmonitor.lumix.LatestPresentation
import com.lumixmonitor.monitor.CubeLut
import com.lumixmonitor.monitor.DemoFeed
import com.lumixmonitor.monitor.Scopes
import com.lumixmonitor.monitor.ScopeRaster
import com.lumixmonitor.monitor.ScopeMath
import com.lumixmonitor.monitor.ToolPreferences
import com.lumixmonitor.monitor.ViewTransform
import com.lumixmonitor.monitor.PreviewRecorder
import com.lumixmonitor.monitor.DisplayCalibration
import java.io.File
import com.lumixmonitor.ptp.PtpLog
import com.lumixmonitor.ptp.PtpUsbTransport
import com.lumixmonitor.usb.LumixUsbConnector
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CancellationException
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong

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
    val showThirds: Boolean = true,
    val desqueeze: Float = 1.0f,
    val showWaveform: Boolean = false,
    val showVectorscope: Boolean = false,
    val rgbHistogram: Boolean = false,
    val peakingThreshold: Float = 0.10f,
    val guideAspect: Float = 2.39f,
    val safeArea: Float = 0.9f,
    val lutEnabled: Boolean = false,
    val lutAmount: Float = 1f,
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
    private val preferences = application.getSharedPreferences("monitor", 0)
    private val mainHandler = Handler(Looper.getMainLooper())
    private val generation = AtomicLong()
    private val recovery = ConnectionRecovery()
    private val settingsRefreshPending = AtomicBoolean()
    @Volatile private var wantConnection = preferences.getBoolean("auto_reconnect", false)
    @Volatile private var disposed = false
    @Volatile private var permissionPending = false

    private var transport: PtpUsbTransport? = null
    private var camera: LumixCamera? = null
    private var liveview: LiveviewLoop? = null
    @Volatile private var latestCameraJpeg: ByteArray? = null
    private var heldCameraJpeg: ByteArray? = null

    /**
     * Разрешение последнего кадра — пишем в журнал только при смене.
     * Летит между потоком кадров и рабочим потоком, поэтому volatile.
     */
    @Volatile
    private var lastFrameSize: String? = null

    /** Время последнего перечитывания настроек: защита от флуда по USB. */
    private val pendingProperties = ConcurrentHashMap.newKeySet<Int>()
    private val cachedProperties = mutableMapOf<Int, PropDesc>()

    private val io = Executors.newSingleThreadScheduledExecutor { runnable ->
        Thread(runnable, "lumix-io")
    }

    // ------------------------------------------------------------- состояние

    val remoteControl = RemoteCameraController(application, io, { camera }, { generation.get() },
        { isCurrent(it) && !isDemo }, { text -> captureFeedback=text;statusText=text },
        { cameraFocusAssist=false;cancelAssistSelection() })

    val observedSettings: CameraSettings get() {
        if(isWifi)return wifiSettings()
        val t=remoteControl.telemetry
        if(SystemClock.elapsedRealtime()-t.observedAtMs>4500)return settings.copy(aperture=if(settings.aperture=="f/0")"—" else settings.aperture)
        return settings.copy(
            iso=if(settings.iso.startsWith("auto") && t.actualIso!=null)"A ${t.actualIso}" else settings.iso,
            aperture=t.actualAperture?.let{LumixCodec.formatAperture(it/10.0)} ?: if(settings.aperture=="f/0")"—" else settings.aperture,
            shutter=t.actualShutter?.let{LumixCodec.decodeShutterSpeed(it)} ?: settings.shutter,
        )
    }

    var connectionState by mutableStateOf<ConnectionState>(ConnectionState.Disconnected)
        private set

    var frame by mutableStateOf<Bitmap?>(null)
        private set

    var performanceText by mutableStateOf("")
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

    var tools by mutableStateOf(ToolPreferences.load(preferences))
        private set

    var isDemo by mutableStateOf(false)
        private set
    var heldFrame by mutableStateOf<Bitmap?>(null)
        private set
    val displayFrame: Bitmap? get() = heldFrame ?: frame
    var scopes by mutableStateOf<Scopes?>(null)
        private set
    var scopeRaster by mutableStateOf<ScopeRaster?>(null)
        private set
    var viewTransform by mutableStateOf(ViewTransform())
        private set
    var nativePixels by mutableStateOf(false)
        private set
    var cameraFocusAssist by mutableStateOf(false)
        private set
    var focusAssistPending by mutableStateOf(false)
        private set
    var selectingAssist by mutableStateOf(false)
        private set
    private var pendingAssistPoint:Pair<Int,Int>?=null
    var previewQuarterTurns by mutableStateOf(preferences.getInt("previewRotation",0).coerceIn(0,3))
        private set
    var capturePending by mutableStateOf(false)
        private set
    var captureFeedback by mutableStateOf("")
        private set
    var viewingLut by mutableStateOf<CubeLut?>(null)
        private set
    var cameraBattery by mutableStateOf<Int?>(null)
        private set
    var recordCommand by mutableStateOf("Статус записи GH5 не получен")
        private set
    var selectedPage by mutableStateOf(preferences.getString("page","frame") ?: "frame")
        private set
    var screenBrightness by mutableStateOf(preferences.getFloat("brightness",-1f))
        private set
    var displayCalibration by mutableStateOf(DisplayCalibration(
        preferences.getBoolean("viewCorrection",false),
        preferences.getFloat("viewEv",0f).coerceIn(-3f,3f),
        preferences.getFloat("viewGamma",1f).coerceIn(.5f,2f),
        preferences.getFloat("viewContrast",1f).coerceIn(.5f,1.5f)))
        private set
    private val featureIo=Executors.newSingleThreadExecutor { Thread(it,"monitor-tools") }
    val previewDiagnosis=PreviewDiagnosisController(application,io,featureIo,{camera},{!disposed && !isDemo})
    var isWifi by mutableStateOf(false)
        private set
    val lastConnectionWifi:Boolean get()=preferences.getString("connection_type","usb")=="wifi"
    val wifi:com.lumixmonitor.wifi.WifiCameraController=com.lumixmonitor.wifi.WifiCameraController(application,
        {bitmap,jpeg->if(isWifi && !disposed){
            latestCameraJpeg=jpeg;frame=bitmap;settings=wifiSettings();frameCount++
            logFrameSize(bitmap);levelRoll=null;levelPitch=null;histogram=null
            previewRecorder?.offer(bitmap,System.nanoTime());analyseFrame()
        }},
        {name->if(isWifi && !disposed){cameraModel=name;connectionState=ConnectionState.Connected("Wi-Fi");settings=wifiSettings()}},
        {text->if(isWifi && !disposed){statusText=text;captureFeedback=text}},
        {text->if(isWifi && !disposed){performanceText=text;PtpLog.add("WIFI PERF $text")}},
        {text->if(isWifi && !disposed){lastError=text;statusText=text;connectionState=ConnectionState.Failed(text)}})
    val cameraTelemetry:com.lumixmonitor.lumix.CameraTelemetry get()=if(isWifi)wifi.telemetry else remoteControl.telemetry
    val focusAssistActive:Boolean get()=if(isWifi)wifi.assist.active else cameraFocusAssist
    val assistPending:Boolean get()=if(isWifi)wifi.assistBusy else focusAssistPending
    val touchFocusEnabled:Boolean get()=if(isWifi)wifi.touchAf else remoteControl.touchAf
    val focusPoint:Pair<Float,Float>? get()=if(isWifi)wifi.focusTarget else remoteControl.focusTarget
    fun toggleTouchFocus(){if(isWifi)wifi.toggleTouchAf() else remoteControl.toggleTouchAf()}
    fun pointFocus(x:Float,y:Float){if(isWifi)wifi.point((x*1000).toInt(),(y*1000).toInt()) else remoteControl.point(x,y)}
    fun wifiType(kind:SettingKind):String?=when(kind){
        SettingKind.ISO->"iso";SettingKind.SHUTTER->"shtrspeed";SettingKind.APERTURE->"focal"
        SettingKind.EXPOSURE->"exposure";SettingKind.WHITE_BALANCE->"whitebalance";SettingKind.PHOTO_STYLE->"photostyle"
        SettingKind.AF_MODE->"focusmode";SettingKind.AF_ZONE->"afmode";else->null
    }
    val shootingModeText:String get()=if(isWifi)wifi.shootingMode else if(settings.mode=="video")"Видео" else settings.mode
    private fun wifiSettings()=CameraSettings(mode=wifi.shootingMode,iso=wifi.text("iso"),shutter=wifi.text("shtrspeed"),
        aperture=wifi.text("focal"),exposureComp=wifi.text("exposure"),whiteBalance=wifi.text("whitebalance"),
        photoStyle=wifi.text("photostyle"),afMode=wifi.text("focusmode"),afZone=wifi.text("afmode"))
    fun showConnectionSetup(wireless:Boolean){
        disconnect();preferences.edit().putString("connection_type",if(wireless)"wifi" else "usb").apply();isWifi=wireless
    }
    fun connectWifi(address:String){
        stopDemo();wantConnection=false;generation.incrementAndGet();permissionPending=false
        isWifi=true;connectionState=ConnectionState.Connecting;statusText="Подключение Wi-Fi…";lastError=null
        frame=null;heldFrame=null;latestCameraJpeg=null;heldCameraJpeg=null;actualFrameSize=null;lastFrameSize=null
        frameCount=0;settings=CameraSettings();performanceText="";cameraModel=null
        preferences.edit().putBoolean("auto_reconnect",false).putString("connection_type","wifi").apply()
        val token=generation.get()
        io.execute{releaseQuietly(graceful=true);mainHandler.post{if(!disposed && generation.get()==token && isWifi)wifi.connect(address)}}
    }
    var neutralPreview by mutableStateOf(false)
    val previewTools:MonitorTools get()=if(neutralPreview)tools.copy(lutEnabled=false,falseColor=false,focusPeaking=false,zebra=false) else tools
    val previewCalibration:DisplayCalibration get()=if(neutralPreview)DisplayCalibration() else displayCalibration
    fun capturePreviewDiagnosis(stage:String){
        if(isWifi){statusText="Сравнение параметров фото/видео пока доступно по USB";return}
        if(isDemo){statusText="Для сравнения нужна подключённая GH5";return}
        val app=getApplication<Application>()
        val state=org.json.JSONObject().put("version",BuildConfig.VERSION_NAME).put("cameraModel",cameraModel)
            .put("neutralPreview",neutralPreview).put("calibrationEnabled",displayCalibration.enabled)
            .put("viewEv",displayCalibration.ev).put("viewGamma",displayCalibration.gamma).put("viewContrast",displayCalibration.contrast)
            .put("lutEnabled",tools.lutEnabled).put("lutTitle",viewingLut?.title ?: org.json.JSONObject.NULL).put("lutAmount",tools.lutAmount)
            .put("peaking",tools.focusPeaking).put("zebra",tools.zebra).put("falseColor",tools.falseColor)
            .put("heldFrameVisible",heldFrame!=null).put("nativeMfRequested",cameraFocusAssist).put("windowBrightness",screenBrightness)
            .put("systemBrightnessMode",android.provider.Settings.System.getInt(app.contentResolver,android.provider.Settings.System.SCREEN_BRIGHTNESS_MODE,-1))
            .put("systemBrightness",android.provider.Settings.System.getInt(app.contentResolver,android.provider.Settings.System.SCREEN_BRIGHTNESS,-1))
        previewDiagnosis.capture(stage,state)
    }
    private val analysisBusy=AtomicBoolean()
    private var lastAnalysisMs=0L
    private val featureEpoch=AtomicLong()
    private val lutEpoch=AtomicLong()
    private var demoFeed:DemoFeed?=null
    private var referenceRestore:Pair<MonitorTools,DisplayCalibration>?=null
    private val frameProbeRequested=AtomicBoolean()
    @Volatile private var previewRecorder:PreviewRecorder?=null
    var proxyRecording by mutableStateOf(false)
        private set
    var proxyStatus by mutableStateOf("")
        private set
    private data class DemoSample(val bitmap:Bitmap,val roll:Float,val pitch:Float,val fps:Float,val epoch:Long)
    private val demoMailbox=LatestPresentation<DemoSample>()
    private val presentDemo=Runnable {
        val sample=demoMailbox.take()
        if(isDemo && sample!=null && featureEpoch.get()==sample.epoch){
            frame=sample.bitmap;frameCount++;levelRoll=sample.roll;levelPitch=sample.pitch
            actualFrameSize="${sample.bitmap.width}x${sample.bitmap.height}"
            previewRecorder?.offer(sample.bitmap,System.nanoTime())
            performanceText=String.format(java.util.Locale.US,"DEMO %.1f FPS",sample.fps)
            analyseFrame()
        }
    }

    init {
        val file=File(getApplication<Application>().filesDir,"viewing.cube")
        if(file.exists())featureIo.execute { try { val lut=file.reader().use{CubeLut.parse(it,file.name)};mainHandler.post{viewingLut=lut} } catch(t:Throwable){PtpLog.add("LUT не загружен: ${t.message}")} }
    }

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
        val wasWifi=isWifi
        wifi.disconnect();isWifi=false;preferences.edit().putString("connection_type","usb").apply()
        stopDemo()
        recovery.observeDevice(device.deviceName)
        if (recovery.requiresUsbChange) { statusText = USB_REPLUG_HINT; return }
        if (!wasWifi && connectionState is ConnectionState.Connecting) return
        if (!wasWifi && connectionState is ConnectionState.Connected && lastDevice?.deviceName == device.deviceName) return
        wantConnection = true
        preferences.edit().putBoolean("auto_reconnect", true).apply()
        val token = generation.incrementAndGet()
        connectionState = ConnectionState.Connecting
        lastError = null
        statusText = "Запрос доступа к USB…"
        lastDevice = device
        PtpLog.add("=== подключение: ${device.deviceName} ===")

        io.execute {
            releaseQuietly(graceful = true)
            requestOpen(device, token)
        }
    }

    /**
     * Переподключается к той же камере без физического перетыкания кабеля.
     *
     * Полностью закрывает прежнее соединение, заново открывает устройство,
     * сбрасывает состояние PTP и поднимает сессию и liveview заново.
     */
    fun reconnect() {
        if(isWifi){connectWifi(wifi.address);return}
        stopDemo()
        recovery.observeDevice(findCurrentDevice()?.deviceName)
        if (recovery.requiresUsbChange) { statusText = USB_REPLUG_HINT; return }
        wantConnection = true
        preferences.edit().putBoolean("auto_reconnect", true).apply()
        val token = generation.incrementAndGet()
        permissionPending = false
        connectionState = ConnectionState.Connecting
        lastError = null
        statusText = "Переподключение…"
        PtpLog.add("=== переподключение ===")
        io.execute {
            releaseQuietly(graceful = true)
            if (!isCurrent(token)) return@execute
            val device = findCurrentDevice()
            if (device == null) {
                statusText = "Ожидание камеры — вернитесь к съёмке на GH5"
            } else requestOpen(device, token)
        }
    }

    private fun isCurrent(token: Long) = !disposed && wantConnection && generation.get() == token

    /** Never reuse a stale UsbDevice after the camera re-enumerates. */
    private fun findCurrentDevice(): UsbDevice? {
        val devices = connector.findPtpDevices()
        val previous = lastDevice
        if (previous == null) return devices.firstOrNull { it.vendorId == 0x04DA }
        return devices.firstOrNull {
            it.vendorId == previous.vendorId && it.productId == previous.productId
        }
    }

    private fun requestOpen(device: UsbDevice, token: Long) {
        if (!isCurrent(token)) return
        recovery.attemptStarted(SystemClock.elapsedRealtime())
        permissionPending = true
        lastDevice = device
        mainHandler.post {
            if (!isCurrent(token)) return@post
            try {
                connector.requestPermission(device) { granted ->
                    if (!isCurrent(token)) return@requestPermission
                    if (!granted) {
                        permissionPending = false
                        wantConnection = false
                        preferences.edit().putBoolean("auto_reconnect", false).apply()
                        connectionState = ConnectionState.Failed("Доступ к USB не выдан")
                        statusText = "Доступ к USB не выдан"
                    } else io.execute {
                        if (isCurrent(token)) openCamera(device, token)
                    }
                }
            } catch (t: Throwable) {
                if (isCurrent(token)) {
                    permissionPending = false
                    connectionState = ConnectionState.Failed("Камера временно недоступна")
                    statusText = "Ожидание камеры; повторное подключение автоматически"
                    PtpLog.add("запрос USB-доступа: ${t.message}")
                }
            }
        }
    }

    /** Serialized with all open/close operations: a failed old session cannot close a new one. */
    private fun checkConnectionHealth() {
        if (disposed || isWifi || !wantConnection) return
        val device = findCurrentDevice()
        recovery.observeDevice(device?.deviceName)
        if (permissionPending) {
            if (device == null) {
                generation.incrementAndGet()
                permissionPending = false
            }
            return
        }
        if (device == null) {
            if (transport != null) {
                generation.incrementAndGet()
                releaseQuietly()
                PtpLog.add("USB-камера исчезла — ожидаю повторного появления")
            }
            connectionState = ConnectionState.Connecting
            statusText = "Ожидание камеры — вернитесь к съёмке на GH5"
            return
        }
        if (transport?.isSynchronized == false) {
            recovery.blockUntilUsbChanges(device.deviceName)
            releaseQuietly()
            connectionState = ConnectionState.Failed(USB_REPLUG_HINT)
            statusText = USB_REPLUG_HINT
            PtpLog.add("Восстановление приостановлено до смены USB-устройства: незавершённая PTP-транзакция")
            return
        }
        val now = SystemClock.elapsedRealtime()
        if (!recovery.canAttempt(now)) return
        if (camera != null && !recovery.needsRecovery(now, liveview?.isRunning == true) &&
            device.deviceName == lastDevice?.deviceName) return

        val token = generation.incrementAndGet()
        PtpLog.add("=== автоматическое восстановление USB ===")
        connectionState = ConnectionState.Connecting
        statusText = "Восстановление связи — ожидание режима съёмки…"
        releaseQuietly()
        requestOpen(device, token)
    }

    fun onForeground() {
        wifi.setForeground(true)
        remoteControl.active=true
        demoFeed?.active=true
        if (!disposed) io.execute { checkConnectionHealth() }
    }

    fun onBackground(){demoFeed?.active=false;remoteControl.active=false;wifi.setForeground(false)}

    /** Обновляет снимок журнала диагностики для показа в интерфейсе. */
    fun refreshDiagnostics() {
        diagnostics = PtpLog.snapshot()
    }

    fun clearDiagnostics() {
        PtpLog.clear()
        diagnostics = emptyList()
    }

    private fun openCamera(device: UsbDevice, token: Long) {
        permissionPending = false
        try {
            if (!isCurrent(token)) return
            frame = null
            latestCameraJpeg=null;heldCameraJpeg=null
            heldFrame=null;scopes=null;cameraBattery=null;recordCommand="Статус записи GH5 не получен"
            viewTransform=ViewTransform()
            featureEpoch.incrementAndGet()
            frameCount = 0
            actualFrameSize = null
            lastFrameSize = null
            levelRoll = null
            levelPitch = null
            histogram = null
            propDescs.clear()
            cachedProperties.clear()
            pendingProperties.clear()
            performanceText = ""
            activeLiveviewMode = null
            liveviewModes = emptyList()
            statusText = "Открытие PTP-соединения…"
            val newTransport = connector.open(device)
            transport = newTransport

            val newCamera = LumixCamera(newTransport)
            newCamera.onObjectAdded = { if(isCurrent(token))remoteControl.newObject() }
            newCamera.onDevicePropChanged = propChanged@ { propCode ->
                if (!isCurrent(token)) return@propChanged
                Log.d(TAG, "камера изменила свойство 0x%08X".format(propCode))
                scheduleSettingsRefresh(propCode)
                if((propCode and 0xfffffff0.toInt()) in com.lumixmonitor.lumix.RemoteSetting.groups)remoteControl.requestRefresh()
            }

            statusText = "Открытие сессии…"
            newCamera.open()
            if (!isCurrent(token)) throw CancellationException("подключение отменено")
            camera = newCamera
            cameraModel = newCamera.deviceInfo?.displayName
            captureFeedback=""

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
            recovery.opened(SystemClock.elapsedRealtime())
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

            if (!isCurrent(token)) throw CancellationException("подключение отменено")
            connectionState = ConnectionState.Connected(device.deviceName)
            lastError = null
            statusText = "Подключено"
            refreshSettings()
        } catch (t: Throwable) {
            Log.e(TAG, "не удалось подключиться", t)
            // Причина отказа обязательно попадает в журнал: logcat недоступен,
            // пока камера занимает единственный USB-порт телефона.
            PtpLog.add("подключение не удалось: ${t.javaClass.simpleName}: ${t.message}")
            t.stackTrace.take(8).forEach { frame -> PtpLog.add("    at $frame") }
            if (transport?.isSynchronized == false && findCurrentDevice()?.deviceName == device.deviceName) {
                recovery.blockUntilUsbChanges(device.deviceName)
            }
            releaseQuietly()
            if (!isCurrent(token)) return
            lastError = if (recovery.requiresUsbChange) USB_REPLUG_HINT else t.message
            connectionState = ConnectionState.Failed(lastError ?: "Неизвестная ошибка")
            statusText = if (recovery.requiresUsbChange) USB_REPLUG_HINT else "Ожидание камеры; повторное подключение автоматически"
        }
    }

    fun disconnect() {
        wifi.disconnect();isWifi=false
        stopDemo()
        heldFrame=null;scopes=null;featureEpoch.incrementAndGet()
        wantConnection = false
        preferences.edit().putBoolean("auto_reconnect", false).apply()
        val token = generation.incrementAndGet()
        permissionPending = false
        connectionState = ConnectionState.Connecting
        statusText = "Завершение сеанса GH5 — дождитесь закрытия соединения…"
        io.execute {
            releaseQuietly(graceful = true)
            mainHandler.post {
                if (generation.get() == token && !wantConnection && !disposed) {
                    connectionState = ConnectionState.Disconnected
                    if (!isDemo) statusText = "Соединение закрыто"
                }
            }
        }
        frame = null
        latestCameraJpeg=null;heldCameraJpeg=null
        frameCount = 0
        levelRoll = null
        levelPitch = null
        histogram = null
        actualFrameSize = null
        lastFrameSize = null
    }

    private fun releaseQuietly(graceful: Boolean = false) {
        wifi.disconnect(graceful)
        previewDiagnosis.cancel()
        neutralPreview=false
        remoteControl.reset()
        stopProxy()
        val previousLoop = liveview
        val previousTransport = transport
        val previousCamera = camera
        val endFocusAssist = cameraFocusAssist && !isDemo
        cameraFocusAssist = false
        focusAssistPending = false
        selectingAssist = false
        pendingAssistPoint = null
        // Invalidate callbacks before unblocking old workers.
        liveview = null
        camera = null
        transport = null
        if (graceful && previousCamera != null && previousTransport?.isSynchronized == true) {
            try {
                val drained = previousLoop?.stopGracefully() ?: true
                if (drained && previousTransport.isSynchronized && findCurrentDevice() != null) {
                    if (endFocusAssist) previousCamera.setNativeFocusAssist(false)
                    PtpLog.add("Штатное отключение: кадр завершён, останавливаю liveview и закрываю сессию")
                    val stopped = previousCamera.stopLiveview()
                    if (stopped && previousTransport.isSynchronized) previousCamera.close()
                } else PtpLog.add("Штатное завершение невозможно: команда в полёте или USB недоступен")
            } catch (t: Throwable) {
                PtpLog.add("штатное завершение сессии: ${t.message}")
            }
        }
        try {
            // Stop accepting transactions and release the handle, without more camera commands.
            previousTransport?.close()
        } catch (t: Throwable) {
            Log.w(TAG, "закрытие транспорта", t)
        }
        try {
            previousLoop?.stop(join = true)
        } catch (t: Throwable) {
            Log.w(TAG, "остановка потока", t)
        }
    }

    /** Перечитывает текущие настройки камеры. */
    private fun refreshSettings(properties: Set<Int>? = null) {
        val cam = camera ?: return
        fun read(code: Int, size: Int): PropDesc? {
            if (properties == null || code in properties) {
                cam.getDevicePropDesc(code, size)?.let { cachedProperties[code] = it }
            }
            return cachedProperties[code]
        }
        try {
            val modeDesc = read(LumixProp.CAMERA_MODE_MODE_POS, 2)
            val isoDesc = read(LumixProp.ISO, 4)
                ?.also { propDescs[SettingKind.ISO] = it }
            val shutterDesc = read(LumixProp.SHUTTER_SPEED, 4)
                ?.also { propDescs[SettingKind.SHUTTER] = it }
            val apertureDesc = read(LumixProp.APERTURE, 2)
                ?.also { propDescs[SettingKind.APERTURE] = it }
            val exposureDesc = read(LumixProp.EXPOSURE, 2)
                ?.also { propDescs[SettingKind.EXPOSURE] = it }
            val wbDesc = read(LumixProp.WHITE_BALANCE, 2)
                ?.also { propDescs[SettingKind.WHITE_BALANCE] = it }
            val styleDesc = read(LumixProp.PHOTO_STYLE, 2)
                ?.also { propDescs[SettingKind.PHOTO_STYLE] = it }
            val aspectDesc = read(LumixProp.IMAGE_MODE_ASPECT, 2)
                ?.also { propDescs[SettingKind.ASPECT] = it }
            val qualityDesc = read(LumixProp.IMAGE_MODE_QUALITY, 2)
                ?.also { propDescs[SettingKind.QUALITY] = it }
            val afDesc = read(LumixProp.AF_AREA_MODE_PARAM, 2)
            val afModeDesc = afDesc?.also { propDescs[SettingKind.AF_MODE] = it }
            val afZoneDesc = read(LumixProp.AF_AREA_AREA_PARAM, 2)
                ?.also { propDescs[SettingKind.AF_ZONE] = it }

            if(camera !== cam || isDemo)return
            settings = CameraSettings(
                mode = modeDesc?.let {
                    LumixTables.nameOf(LumixTables.exposureMode, it.current)
                } ?: "—",
                iso = isoDesc?.let { LumixCodec.decodeIso(it.current) } ?: "—",
                shutter = shutterDesc?.let {
                    LumixCodec.decodeShutterSpeed(it.current)
                } ?: "—",
                aperture = apertureDesc?.let {
                    LumixCodec.apertureText(it.current)
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

    /** The GH5 shutter follows the physical camera mode: still capture or start movie. */
    fun shutter(){
        if(isWifi){
            val t=wifi.telemetry
            if(android.os.SystemClock.elapsedRealtime()-t.observedAtMs>=4500){captureFeedback="Статус GH5 устарел — дождитесь ответа камеры";return}
            if(t.recording==true)wifi.command("video_recstop") else if(wifi.shutterMovie)wifi.command("video_recstart") else wifi.command("capture")
            return
        }
        val t=remoteControl.telemetry
        if(t.recording==true && android.os.SystemClock.elapsedRealtime()-t.observedAtMs<4500)movie(false)
        else capture()
    }

    fun capture() {
        if(isWifi){wifi.command("capture");return}
        if(capturePending)return
        if(isDemo){captureFeedback="DEMO: спуск камеры, фото/видео зависит от режима GH5";return}
        val token=generation.get()
        if(camera==null){captureFeedback="Подключите GH5 для спуска затвора";return}
        capturePending=true
        captureFeedback="Спуск затвора…"
        io.execute {
            try {
                if(!isCurrent(token))return@execute
                val cam=camera ?: return@execute
                val accepted=cam.capture()
                if(isCurrent(token)){
                    captureFeedback=if(accepted)"GH5 приняла команду спуска" else "GH5 не приняла спуск: проверьте режим и карту памяти"
                    statusText=captureFeedback
                    PtpLog.add(captureFeedback)
                }
            }catch(t:Throwable){
                if(isCurrent(token)){captureFeedback="Спуск: ${t.message}";lastError=t.message}
            }finally{capturePending=false}
        }
    }

    fun autoFocus(){if(isWifi)wifi.command("oneshot_af") else onIo("Автофокус") { it.autoFocus() }}

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
        if(isWifi){statusText="Выберите Wi-Fi quality в меню камеры";return}
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
                if (applied) {
                    val selected = actual ?: setting
                    preferences.edit().putString("liveview_mode",
                        "${selected.width},${selected.height},${selected.frameSize},${selected.fps}").apply()
                }
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

    /** Bounded debug requests: only live-view size, never sensor/record settings. */
    fun probeUsbQuality(preset: String) {
        if (!BuildConfig.DEBUG || isDemo || camera == null) return
        val baseline = liveviewModes.maxByOrNull { it.pixels } ?: return
        val dimensions = when (preset) {
            "fhd-4x3" -> 1920 to 1440
            "qhd-4x3" -> 2560 to 1920
            "uhd-4x3" -> 3840 to 2880
            "uhd-16x9" -> 3840 to 2160
            "baseline" -> baseline.width to baseline.height
            else -> return
        }
        PtpLog.add("USB quality probe: $preset; only liveview transfer settings")
        selectLiveviewMode(LiveviewSetting(dimensions.first, dimensions.second,
            baseline.frameSize, baseline.fps))
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
        val saved = preferences.getString("liveview_mode", null)
        val best = modes.firstOrNull { "${it.width},${it.height},${it.frameSize},${it.fps}" == saved }
            ?: modes.maxByOrNull { it.pixels } ?: return

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
    private fun startLiveviewLoop(cam: LumixCamera) {
        val token = generation.get()
        lateinit var loop: LiveviewLoop
        loop = LiveviewLoop(
            camera = cam,
            onFrame = { bitmap, liveviewFrame ->
                if (isCurrent(token) && camera === cam && liveview === loop) {
                    recovery.frameReceived(SystemClock.elapsedRealtime())
                    latestCameraJpeg = liveviewFrame.jpeg
                    frame = bitmap
                    previewDiagnosis.offer(bitmap,liveviewFrame.jpeg)
                    if(frameProbeRequested.compareAndSet(true,false)){
                        heldFrame=bitmap;heldCameraJpeg=liveviewFrame.jpeg
                        val jpeg=liveviewFrame.jpeg
                        val info="${bitmap.width}x${bitmap.height}, ${bitmap.config}, ${bitmap.colorSpace}, premultiplied=${bitmap.isPremultiplied}, calibration=$displayCalibration, tools=$tools"
                        featureIo.execute{
                            try{
                                val dir=getApplication<Application>().filesDir
                                File(dir,"live-probe-source.jpg").writeBytes(jpeg)
                                File(dir,"live-probe-decoded.png").outputStream().use{bitmap.compress(Bitmap.CompressFormat.PNG,100,it)}
                                File(dir,"live-probe-info.txt").writeText(info)
                                PtpLog.add("LIVE PROBE сохранён: $info")
                            }catch(t:Throwable){PtpLog.add("LIVE PROBE: ${t.message}")}
                        }
                    }
                    previewRecorder?.offer(bitmap,System.nanoTime())
                    analyseFrame()
                    frameCount += 1
                    levelRoll = liveviewFrame.rollDegrees
                    levelPitch = liveviewFrame.pitchDegrees
                    liveviewFrame.histogram?.let { histogram = it }
                    logFrameSize(bitmap)
                }
            },
            onError = { t ->
                if (isCurrent(token) && camera === cam && liveview === loop) {
                    PtpLog.add("поток прерван: ${t.message}; проверка состояния USB")
                    recovery.streamFailed(SystemClock.elapsedRealtime())
                    lastError = t.message
                    statusText = "Проверка USB после обрыва…"
                }
            },
            onStats = { text ->
                if (isCurrent(token) && camera === cam && liveview === loop) performanceText = text
            },
            onStopped = {
                if (isCurrent(token) && camera === cam && liveview === loop) {
                    recovery.streamFailed(SystemClock.elapsedRealtime())
                    statusText = "Проверка USB после остановки потока…"
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
    private fun scheduleSettingsRefresh(propCode: Int) {
        pendingProperties.addAll(SettingsRefreshPlan.forEvent(propCode))
        if (pendingProperties.isNotEmpty()) queueSettingsRefresh(generation.get())
    }

    private fun queueSettingsRefresh(token: Long) {
        if (!isCurrent(token) || !settingsRefreshPending.compareAndSet(false, true)) return
        io.schedule({
            try {
                if (isCurrent(token)) {
                    val changed = pendingProperties.toSet()
                    pendingProperties.removeAll(changed)
                    if (changed.isNotEmpty()) refreshSettings(changed)
                }
            } finally {
                settingsRefreshPending.set(false)
                if (pendingProperties.isNotEmpty()) queueSettingsRefresh(generation.get())
            }
        }, 350, TimeUnit.MILLISECONDS)
    }

    /**
     * Выполняет операцию с камерой в отдельном потоке.
     * Ошибка не роняет приложение, а показывается в статусе.
     */
    private fun onIo(label: String, action: (LumixCamera) -> Boolean) {
        val token = generation.get()
        io.execute {
            if (!isCurrent(token)) return@execute
            val cam = camera ?: return@execute
            try {
                val ok = action(cam)
                if (!isCurrent(token)) return@execute
                statusText = if (ok) "$label: выполнено" else "$label: камера отказала"
                if (ok) refreshSettings(SettingsRefreshPlan.exposure)
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
        ToolPreferences.save(preferences,tools)
        analyseFrame(force=true)
    }

    fun selectPage(page:String){
        if(page !in listOf("frame","focus","expose") || page==selectedPage)return
        selectedPage=page
        preferences.edit().putString("page",page).apply()
    }

    fun updateDisplayCalibration(value:DisplayCalibration){
        displayCalibration=value.copy(ev=value.ev.coerceIn(-3f,3f),gamma=value.gamma.coerceIn(.5f,2f),contrast=value.contrast.coerceIn(.5f,1.5f))
        val c=displayCalibration
        preferences.edit().putBoolean("viewCorrection",c.enabled).putFloat("viewEv",c.ev)
            .putFloat("viewGamma",c.gamma).putFloat("viewContrast",c.contrast).apply()
    }

    fun setBrightness(value:Float){screenBrightness=if(value<0f)-1f else value.coerceIn(0.05f,1f);preferences.edit().putFloat("brightness",screenBrightness).apply()}

    fun setTransform(zoom:Float,panX:Float=0f,panY:Float=0f,baseZoom:Float=1f){
        val z=zoom.coerceIn(1f,8f);val limit=(1f-1f/(z*baseZoom.coerceAtLeast(1f)))/2f
        viewTransform=ViewTransform(z,panX.coerceIn(-limit,limit),panY.coerceIn(-limit,limit))
    }

    fun toggleNativePixels(){nativePixels=!nativePixels;viewTransform=ViewTransform()}

    fun cancelAssistSelection(){selectingAssist=false;remoteControl.pickAssistArea=null;pendingAssistPoint=null}
    fun startFocusAssistAt(x:Int,y:Int){
        cancelAssistSelection()
        pendingAssistPoint=x.coerceIn(0,1000) to y.coerceIn(0,1000)
        toggleFocusAssist()
    }

    /** Camera-side magnification; no local digital zoom is applied to live USB frames. */
    fun toggleFocusAssist(){
        if(isWifi){nativePixels=false;viewTransform=ViewTransform();heldFrame=null;heldCameraJpeg=null;wifi.toggleAssist();return}
        if (focusAssistPending) return
        if (isDemo) {
            cameraFocusAssist = !cameraFocusAssist
            nativePixels = false
            setTransform(if (cameraFocusAssist) 4f else 1f)
            captureFeedback = "DEMO: имитация лупы GH5"
            return
        }
        if (camera == null) { captureFeedback = "Подключите GH5 для MF Assist"; return }
        if(selectingAssist){cancelAssistSelection();return}
        val token = generation.get()
        val enabled = !cameraFocusAssist
        val target=pendingAssistPoint ?: remoteControl.assistTarget
        pendingAssistPoint=null
        if(enabled && target==null){
            selectingAssist=true
            remoteControl.pickAssistArea={x,y->startFocusAssistAt(x,y)}
            nativePixels=false;viewTransform=ViewTransform();heldFrame=null;heldCameraJpeg=null
            captureFeedback="Выберите область лупы касанием кадра или схемы. Выбор запомнится на телефоне."
            return
        }
        focusAssistPending = true
        io.execute {
            if (!isCurrent(token)) return@execute
            try {
                val cam=camera ?: return@execute
                val accepted = cam.setNativeFocusAssistAt(enabled,target?.first ?: 500,target?.second ?: 500)
                mainHandler.post {
                    if (!isCurrent(token)) return@post
                    if (accepted) {
                        cameraFocusAssist = enabled
                        if(enabled && target!=null)remoteControl.confirmAssistPosition(target)
                        nativePixels = false
                        viewTransform = ViewTransform()
                        heldFrame = null
                        heldCameraJpeg = null
                    }
                    captureFeedback = if (accepted) {
                        if (enabled) "Лупа GH5: увеличение выполняет камера" else "Лупа GH5 выключена"
                    } else "GH5 не приняла MF Assist: проверьте режим камеры"
                    statusText = captureFeedback
                }
            } catch (t: Throwable) {
                PtpLog.add("MF Assist GH5: ${t.message}")
                mainHandler.post { if (isCurrent(token)) { captureFeedback = "MF Assist: ${t.message}"; statusText = captureFeedback } }
            } finally {
                mainHandler.post { if (isCurrent(token)) focusAssistPending = false }
            }
        }
    }

    fun rotatePreview(){
        previewQuarterTurns=(previewQuarterTurns+1)%4
        preferences.edit().putInt("previewRotation",previewQuarterTurns).apply()
        nativePixels=false
        viewTransform=ViewTransform()
    }

    fun toggleHold(){
        if(heldFrame==null){heldFrame=frame;heldCameraJpeg=latestCameraJpeg}
        else{heldFrame=null;heldCameraJpeg=null}
        analyseFrame(force=true)
    }

    fun requestFrameProbe(){frameProbeRequested.set(true);PtpLog.add("LIVE PROBE: ожидаю следующий кадр камеры")}

    fun startDemo(sourceImage:ByteArray?=null){
        disconnect()
        if(sourceImage!=null){
            referenceRestore=tools to displayCalibration
            tools=MonitorTools(showThirds=false,showCenterMarker=false,showLevel=false,showHistogram=false)
            displayCalibration=DisplayCalibration()
        }
        demoSettingValues.clear()
        demoSettingValues.putAll(mapOf(SettingKind.ISO to 400,SettingKind.SHUTTER to 60000,
            SettingKind.APERTURE to 50,SettingKind.EXPOSURE to 0,SettingKind.WHITE_BALANCE to 4,
            SettingKind.PHOTO_STYLE to 2))
        captureFeedback=""
        isDemo=true;cameraModel="DEMO";actualFrameSize="1280x720"
        viewTransform=ViewTransform();nativePixels=false;cameraBattery=null
        settings=CameraSettings(mode="M",iso="400",shutter="1/60",aperture="F5.0",exposureComp="0",whiteBalance="Дневной",photoStyle="Natural",aspect="16:9",afMode="DEMO")
        statusText=if(sourceImage!=null)"Повтор исходного файла: без USB, LUT и коррекции" else "Демонстрация: камера не используется"
        val epoch=featureEpoch.incrementAndGet()
        demoFeed=DemoFeed(sourceImage) { bitmap,roll,pitch,fps ->
            if(isDemo && epoch==featureEpoch.get() && demoMailbox.offer(DemoSample(bitmap,roll,pitch,fps,epoch)))mainHandler.post(presentDemo)
        }
    }

    fun stopDemo(){
        demoFeed?.close();demoFeed=null
        referenceRestore?.let{tools=it.first;displayCalibration=it.second};referenceRestore=null
        if(isDemo){isDemo=false;featureEpoch.incrementAndGet();frame=null;heldFrame=null;scopes=null;cameraModel=null;actualFrameSize=null;settings=CameraSettings();performanceText=""}
        demoMailbox.clear();mainHandler.removeCallbacks(presentDemo)
    }

    private fun analyseFrame(force:Boolean=false){
        if(!tools.showHistogram && !tools.showWaveform && !tools.showVectorscope)return
        val bitmap=displayFrame ?: return
        val now=SystemClock.elapsedRealtime()
        if(!force && now-lastAnalysisMs<125)return
        if(!analysisBusy.compareAndSet(false,true))return
        lastAnalysisMs=now
        val epoch=featureEpoch.get()
        featureIo.execute {
            try {
                val small=Bitmap.createBitmap(160,90,Bitmap.Config.ARGB_8888)
                Canvas(small).drawBitmap(bitmap,null,android.graphics.Rect(0,0,160,90),Paint(Paint.FILTER_BITMAP_FLAG))
                val pixels=IntArray(160*90);small.getPixels(pixels,0,160,0,0,160,90);small.recycle()
                val data=ScopeMath.analyse(pixels,160,90)
                val raster=ScopeRaster.from(data)
                mainHandler.post{if(!disposed && epoch==featureEpoch.get()){scopes=data;scopeRaster=raster}}
            }catch(t:Throwable){PtpLog.add("Scopes: ${t.message}")}
            finally{analysisBusy.set(false)}
        }
    }

    fun allowedSettings(kind:SettingKind):List<Pair<Int,String>>{
        if(isWifi)return wifiType(kind)?.let{wifi.choices(it).map{v->v.id to v.label}} ?: emptyList()
        val values=if(isDemo)when(kind){
            SettingKind.ISO->intArrayOf(-1,100,125,160,200,250,320,400,500,640,800,1000,1250,1600,2000,2500,3200,4000,5000,6400,8000,10000,12800,16000,20000,25600)
            SettingKind.SHUTTER->intArrayOf(8000000,4000000,2000000,1000000,500000,250000,125000,100000,80000,60000,50000,40000,30000,25000,20000,15000,10000,8000,5000,4000,2000,1000,Int.MIN_VALUE or 2000)
            SettingKind.APERTURE->intArrayOf(28,35,40,50,56,63,71,80,90,100,110,130,140,160,180,200,220)
            SettingKind.EXPOSURE->(-15..15).map{if(it<0)0x8000 or -it else it}.toIntArray()
            SettingKind.WHITE_BALANCE->intArrayOf(2,4,0x8008,0x800F,6)
            SettingKind.PHOTO_STYLE->intArrayOf(0,1,2,14,18)
            else->intArrayOf(1,2,3)
        }else propDescs[kind]?.allowed ?: intArrayOf()
        return SettingScale.ordered(kind,values).map{it to describeValue(kind,it)}
    }

    fun currentSettingValue(kind:SettingKind):Int? = if(isWifi)wifiType(kind)?.let{wifi.current(it)?.id} else if(isDemo)demoSettingValues[kind] else propDescs[kind]?.current

    fun currentSettingText(kind:SettingKind):String=if(isWifi)wifiType(kind)?.let{wifi.text(it)} ?: "—" else when(kind){
        SettingKind.ISO->settings.iso;SettingKind.SHUTTER->settings.shutter
        SettingKind.APERTURE->settings.aperture;SettingKind.EXPOSURE->settings.exposureComp
        SettingKind.WHITE_BALANCE->settings.whiteBalance
        else->currentSettingValue(kind)?.let{describeValue(kind,it)} ?: "—"
    }

    fun setSettingValue(kind:SettingKind,value:Int){
        if(isWifi){wifiType(kind)?.let{wifi.select(it,value)};return}
        if(isDemo){
            if(allowedSettings(kind).none{it.first==value})return
            demoSettingValues[kind]=value
            val text=describeValue(kind,value)
            settings=when(kind){SettingKind.ISO->settings.copy(iso=text);SettingKind.SHUTTER->settings.copy(shutter=text);SettingKind.APERTURE->settings.copy(aperture=text);SettingKind.EXPOSURE->settings.copy(exposureComp=text);SettingKind.WHITE_BALANCE->settings.copy(whiteBalance=text);SettingKind.PHOTO_STYLE->settings.copy(photoStyle=text);else->settings}
            statusText="DEMO: ${kind.label} $text";return
        }
        val token=generation.get()
        val sequence=settingWriteRequests.computeIfAbsent(kind){AtomicLong()}
        val request=sequence.incrementAndGet()
        io.execute{
            // Scroll gestures can outpace USB: skip superseded queued writes.
            if(!isCurrent(token) || sequence.get()!=request)return@execute
            val cam=camera ?: return@execute
            if(propDescs[kind]?.allowed?.contains(value)!=true)return@execute
            try{
                val ok=cam.setDevicePropValue(kind.paramCode,kind.valueSize,value)
                if(isCurrent(token) && sequence.get()==request){
                    statusText=if(ok)"${kind.label}: подтверждено камерой" else "${kind.label}: камера отказала"
                    if(ok)refreshSettings(setOf(kind.propCode))
                }
            }catch(t:Throwable){if(isCurrent(token) && sequence.get()==request)statusText="${kind.label}: ${t.message}"}
        }
    }

    fun setKelvin(kelvin:Int){
        if(kelvin !in 2500..10000)return
        if(isDemo){settings=settings.copy(whiteBalance="${kelvin}K");return}
        onIo("ББ ${kelvin}K"){it.setColorTemperature(kelvin)}
    }

    fun movie(start:Boolean){
        if(isWifi){wifi.command(if(start)"video_recstart" else "video_recstop");return}
        if(isDemo){statusText="DEMO: запись на камере не запускается";return}
        val token=generation.get()
        io.execute{
            if(!isCurrent(token))return@execute
            val cam=camera ?: return@execute
            try{
                val accepted=cam.movie(start)
                if(isCurrent(token)){
                    recordCommand=if(accepted)"${if(start)"REC" else "STOP"}: команда принята; фактический статус не получен" else "Камера отказала"
                    statusText=recordCommand
                }
            }catch(t:Throwable){if(isCurrent(token)){recordCommand="REC/STOP: ${t.message}";statusText=recordCommand}}
        }
    }

    fun probeBattery(){
        if(isDemo){statusText="DEMO: батарея камеры недоступна";return}
        val token=generation.get()
        io.execute{
            val cam=camera ?: return@execute
            if(!isCurrent(token))return@execute
            try{val value=cam.batteryLevel();if(isCurrent(token)){cameraBattery=value;statusText=value?.let{"Батарея GH5: $it%"} ?: "GH5 не вернула заряд батареи"}}
            catch(t:Throwable){if(isCurrent(token))statusText="Батарея: ${t.message}"}
        }
    }

    fun loadLut(uri:Uri){
        val token=lutEpoch.incrementAndGet()
        featureIo.execute {
            try {
                val app=getApplication<Application>()
                val text=app.contentResolver.openInputStream(uri)?.bufferedReader()?.use{reader->
                    val out=StringBuilder();val buffer=CharArray(8192);var n=reader.read(buffer)
                    while(n>=0){require(out.length+n<=16_000_000){"Слишком большой LUT"};out.append(buffer,0,n);n=reader.read(buffer)};out.toString()
                } ?: error("Файл не открыт")
                val lut=CubeLut.parse(text.reader(),uri.lastPathSegment?.substringAfterLast('/') ?: "LUT")
                if(token!=lutEpoch.get())return@execute
                File(app.filesDir,"viewing.cube").writeText(text)
                mainHandler.post{if(!disposed && token==lutEpoch.get()){viewingLut=lut;updateTools{it.copy(lutEnabled=true)};statusText="LUT: ${lut.title}"}}
            }catch(t:Throwable){mainHandler.post{if(!disposed && token==lutEpoch.get())statusText="LUT: ${t.message}"}}
        }
    }

    fun clearLut(){
        lutEpoch.incrementAndGet()
        viewingLut=null;updateTools{it.copy(lutEnabled=false)}
        for(page in listOf("frame","focus","expose"))getApplication<Application>().getSharedPreferences("tools_$page",0).edit().putBoolean("lutEnabled",false).apply()
        featureIo.execute{File(getApplication<Application>().filesDir,"viewing.cube").delete()}
        statusText="Просмотр без LUT"
    }

    fun savePreview(){
        val bitmap=displayFrame ?: return
        val demo=isDemo
        val jpeg=if(demo)null else if(heldFrame!=null)heldCameraJpeg else latestCameraJpeg
        featureIo.execute{
            var pending:Uri?=null
            try{
                val app=getApplication<Application>();val name="GH5_${if(demo)"DEMO_" else ""}${System.currentTimeMillis()}.jpg"
                if(Build.VERSION.SDK_INT>=29){
                    val values=ContentValues().apply{put(MediaStore.Images.Media.DISPLAY_NAME,name);put(MediaStore.Images.Media.MIME_TYPE,"image/jpeg");put(MediaStore.Images.Media.RELATIVE_PATH,"Pictures/GH5 Monitor");put(MediaStore.Images.Media.IS_PENDING,1)}
                    pending=app.contentResolver.insert(MediaStore.Images.Media.EXTERNAL_CONTENT_URI,values) ?: error("Не удалось создать JPEG")
                    app.contentResolver.openOutputStream(pending!!)?.use{if(jpeg!=null)it.write(jpeg) else check(bitmap.compress(Bitmap.CompressFormat.JPEG,95,it))} ?: error("JPEG не открыт")
                    app.contentResolver.update(pending!!,ContentValues().apply{put(MediaStore.Images.Media.IS_PENDING,0)},null,null)
                }else File(app.getExternalFilesDir("Pictures"),name).outputStream().use{if(jpeg!=null)it.write(jpeg) else check(bitmap.compress(Bitmap.CompressFormat.JPEG,95,it))}
                mainHandler.post{statusText="JPEG исходного превью сохранён: $name"}
            }catch(t:Throwable){pending?.let{getApplication<Application>().contentResolver.delete(it,null,null)};mainHandler.post{statusText="Сохранение: ${t.message}"}}
        }
    }

    fun startProxy(){
        val bitmap=frame ?: return
        if(previewRecorder!=null)return
        val width=bitmap.width-(bitmap.width%2);val height=bitmap.height-(bitmap.height%2)
        proxyRecording=true;proxyStatus="Подготовка MP4 без звука…"
        previewRecorder=PreviewRecorder(getApplication(),width,height,isDemo,
            onProgress={text->mainHandler.post{proxyStatus=text}},
            onFinished={text->mainHandler.post{previewRecorder=null;proxyRecording=false;proxyStatus=text;statusText=text}})
    }

    fun stopProxy(){previewRecorder?.stop()}

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
        val token = generation.get()
        io.execute {
            if (!isCurrent(token)) return@execute
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
                    refreshSettings(setOf(kind.propCode))
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
        SettingKind.APERTURE -> LumixCodec.apertureText(value)
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
    private val propDescs = ConcurrentHashMap<SettingKind, PropDesc>()
    private val demoSettingValues=mutableMapOf<SettingKind,Int>()
    private val settingWriteRequests=ConcurrentHashMap<SettingKind,AtomicLong>()

    fun clearError() {
        lastError = null
    }

    override fun onCleared() {
        super.onCleared()
        wifi.close()
        remoteControl.close()
        stopDemo()
        featureEpoch.incrementAndGet()
        lutEpoch.incrementAndGet()
        featureIo.shutdownNow()
        stopProxy()
        disposed = true
        wantConnection = false
        generation.incrementAndGet()
        healthCheck.cancel(false)
        connector.release()
        io.execute { releaseQuietly(graceful = true) }
        io.shutdown()
    }

    private val healthCheck = io.scheduleWithFixedDelay({
        try {
            checkConnectionHealth()
        } catch (t: Throwable) {
            PtpLog.add("проверка соединения: ${t.message}")
        }
    }, 1, 1, TimeUnit.SECONDS)

    companion object {
        private const val TAG = "MonitorViewModel"
        private const val USB_REPLUG_HINT = "USB-передача прервана. Отсоедините кабель GH5 и подключите снова в PC(Tether)."


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
