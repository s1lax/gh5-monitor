package com.lumixmonitor

import android.app.Application
import android.content.ContentValues
import android.net.Uri
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.provider.MediaStore
import androidx.compose.runtime.*
import com.lumixmonitor.lumix.*
import com.lumixmonitor.ptp.PtpLog
import java.security.DigestOutputStream
import java.security.MessageDigest
import java.util.concurrent.*
import java.util.concurrent.atomic.AtomicLong

/** Session-scoped remote controls. Telemetry is observational and never changes exposure. */
class RemoteCameraController(
    private val app:Application, private val io:ScheduledExecutorService,
    private val camera:()->LumixCamera?, private val epoch:()->Long, private val valid:(Long)->Boolean,
    private val show:(String)->Unit,
    private val onRecording:()->Unit = {},
) {
    private val main=Handler(Looper.getMainLooper())
    private val fileIo=Executors.newSingleThreadExecutor{Thread(it,"gh5-original")}
    private val writes=ConcurrentHashMap<Int,AtomicLong>()
    private val assistPreferences=app.getSharedPreferences("native-mf-assist",android.content.Context.MODE_PRIVATE)
    private fun savedAssist():Pair<Int,Int>? {
        val x=assistPreferences.getInt("x",-1);val y=assistPreferences.getInt("y",-1)
        return if(x in 0..1000 && y in 0..1000)x to y else null
    }
    @Volatile var active=true
    @Volatile private var previousCamera:LumixCamera?=null
    @Volatile private var refreshWanted=true
    @Volatile private var disposed=false
    private var lastSlow=0L
    private var lastCaps=0L
    var telemetry by mutableStateOf(CameraTelemetry()); private set
    var properties by mutableStateOf<Map<Int,CameraProperty>>(emptyMap()); private set
    var touchAf by mutableStateOf(false); private set
    var focusTarget by mutableStateOf<Pair<Float,Float>?>(null); private set
    // GH5 rejects the newer AF magnification-state blocks. Never invent its initial position.
    var assistTarget by mutableStateOf(savedAssist()); private set
    var pickAssistArea:((Int,Int)->Unit)?=null
    var focusBusy by mutableStateOf(false); private set
    var files by mutableStateOf<List<CameraObject>>(emptyList()); private set
    var filesLoading by mutableStateOf(false); private set
    var filesChanged by mutableStateOf(false); private set
    var transferBusy by mutableStateOf(false); private set
    var transferText by mutableStateOf(""); private set
    var downloadedUri by mutableStateOf<Uri?>(null); private set
    var downloadedMime by mutableStateOf("image/jpeg"); private set
    var feedback by mutableStateOf(""); private set
    var whiteBalanceMeasuring by mutableStateOf(false); private set
    var whiteBalanceBusy by mutableStateOf(false); private set
    private var whiteBalanceCamera:LumixCamera?=null
    private var whiteBalanceSlot:Int?=null
    private val wbMeasurementEpoch=AtomicLong()
    private val wbFailureCount=AtomicLong()
    private val wbShiftWrites=AtomicLong()

    private fun post(token:Long,cam:LumixCamera,block:()->Unit){main.post{if(!disposed && valid(token) && camera()===cam)block()}}
    private fun message(text:String){feedback=text;show(text)}
    fun requestRefresh(){refreshWanted=true}
    fun newObject(){val token=epoch();val cam=camera() ?: return;post(token,cam){filesChanged=true}}
    fun toggleTouchAf(){touchAf=!touchAf;message(if(touchAf)"Касание кадра: точка AF" else "AF по касанию выключен")}
    fun choices(setting:RemoteSetting):List<Pair<Int,String>> = properties[setting.code]?.choices.orEmpty()
        .sortedBy{if(setting.signed)PanasonicData.signedMagnitude(it).toLong() else it.toLong() and 0xffffffffL}.map{it to setting.text(it)}

    fun set(setting:RemoteSetting,value:Int){
        val token=epoch();val p=properties[setting.code] ?: return
        if(value !in p.choices)return
        val wbMode=if(setting.section=="ББ")properties[LumixProp.WHITE_BALANCE_PARAM]?.current else null
        val serial=writes.computeIfAbsent(setting.code){AtomicLong()};val request=serial.incrementAndGet()
        io.schedule({
            if(!valid(token) || serial.get()!=request)return@schedule
            val cam=camera() ?: return@schedule
            try{
                if(setting.section=="ББ" && (whiteBalanceCamera!=null || wbMode==null || cam.getDevicePropDesc(LumixProp.WHITE_BALANCE,2)?.current!=wbMode)){
                    requestRefresh();post(token,cam){message("ББ изменился на камере — дождитесь обновления шкалы")};return@schedule
                }
                val ok=cam.remote.setProperty(p,value)
                val next=if(ok)cam.remote.capabilities(cam.deviceInfo?.model) else null
                post(token,cam){
                    if(serial.get()==request){
                        if(next!=null)properties=next
                        val actual=next?.get(setting.code)?.current
                        message(if(ok && actual==value)"${setting.label}: ${setting.text(value)} — подтверждено"
                            else if(ok)"${setting.label}: камера вернула ${actual?.let(setting::text) ?: "—"}"
                            else "${setting.label}: недоступно в текущем режиме GH5")
                    }
                }
            }catch(t:Throwable){post(token,cam){message("${setting.label}: ${t.message}")}}
        },140,TimeUnit.MILLISECONDS)
    }

    fun beginWhiteBalanceMeasurement(){
        if(whiteBalanceBusy || whiteBalanceMeasuring)return
        val token=epoch();val cam=camera() ?: return;whiteBalanceBusy=true
        io.execute{
            try{
                if(!valid(token) || camera()!==cam)return@execute
                val mode=cam.getDevicePropDesc(LumixProp.WHITE_BALANCE,2)?.current
                require(UsbWhiteBalance.isCustom(mode)){"Выберите WB1–WB4 для замера белого"}
                require(cam.remote.telemetry(false,false).recording==false){"Для замера остановите запись GH5"}
                // A physical WB change can already have closed the native operation.
                // END is idempotent on GH5 (InvalidParameter means it was not open).
                check(cam.remote.whiteBalanceMeasure(UsbWhiteBalance.END)){"GH5 не разрешила закрыть прежний замер"}
                check(cam.remote.whiteBalanceMeasure(UsbWhiteBalance.START)){"GH5 отклонила вход в замер ББ"}
                whiteBalanceCamera=cam;whiteBalanceSlot=mode
                post(token,cam){whiteBalanceMeasuring=true;message("Наведите центральную область на белый / серый эталон. Замер изменит WB${UsbWhiteBalance.slot(mode!!)}.")}
            }catch(t:Throwable){post(token,cam){message(t.message ?: "Замер ББ недоступен")}}
            finally{post(token,cam){whiteBalanceBusy=false}}
        }
    }
    fun sampleWhiteBalance(){
        if(whiteBalanceBusy || !whiteBalanceMeasuring)return
        val token=epoch();val cam=whiteBalanceCamera ?: return;val mode=whiteBalanceSlot ?: return;whiteBalanceBusy=true
        val seq=wbMeasurementEpoch.incrementAndGet();val failureBefore=wbFailureCount.get()
        io.execute{
            try{
                if(!valid(token) || camera()!==cam)return@execute
                require(cam.getDevicePropDesc(LumixProp.WHITE_BALANCE,2)?.current==mode){"Ячейка ББ изменилась — закройте замер"}
                require(cam.remote.telemetry(false,false).recording==false){"Для замера остановите запись GH5"}
                check(cam.remote.whiteBalanceMeasure(UsbWhiteBalance.END)){"Не удалось сбросить прежний замер"}
                check(cam.remote.whiteBalanceMeasure(UsbWhiteBalance.START)){"Закройте меню ББ на GH5 и войдите в замер заново"}
                val ok=cam.remote.whiteBalanceMeasure(UsbWhiteBalance.SAMPLE)
                check(ok){"GH5 отклонила замер. Закройте меню ББ на камере и повторите вход."}
                post(token,cam){message("WB${UsbWhiteBalance.slot(mode)}: ожидание результата GH5…")}
                io.schedule({
                    if(!valid(token) || camera()!==cam || wbMeasurementEpoch.get()!=seq)return@schedule
                    runCatching{endWhiteBalanceMeasurement(cam)}
                    requestRefresh()
                    post(token,cam){whiteBalanceMeasuring=false;whiteBalanceBusy=false
                        message(if(wbFailureCount.get()!=failureBefore)"GH5: замер ББ не удался. Проверьте освещение и нейтральный эталон. Можно повторить вход."
                            else "Команда замера WB${UsbWhiteBalance.slot(mode)} принята; сообщения об ошибке не получено. Проверьте результат на GH5.")}
                },1000,TimeUnit.MILLISECONDS)
            }catch(t:Throwable){
                runCatching{endWhiteBalanceMeasurement(cam)}
                post(token,cam){whiteBalanceMeasuring=false;whiteBalanceBusy=false;message(t.message ?: "Ошибка замера ББ")}
            }
        }
    }
    fun whiteBalanceFailure(){
        wbFailureCount.incrementAndGet()
        val token=epoch();val cam=camera() ?: return
        post(token,cam){message("GH5: замер ББ не удался. Возможно, мало света или эталон вне области замера.")}
    }
    /** Invoked only after a fresh WB property read, on the serialized USB worker. */
    fun observeWhiteBalanceMode(mode:Int){
        if(whiteBalanceCamera==null || whiteBalanceSlot==mode)return
        val cam=whiteBalanceCamera ?: return;val token=epoch();wbMeasurementEpoch.incrementAndGet()
        runCatching{endWhiteBalanceMeasurement(cam)}
        whiteBalanceCamera=null;whiteBalanceSlot=null
        post(token,cam){whiteBalanceMeasuring=false;whiteBalanceBusy=false;message("ББ переключён на камере. Для нового замера нажмите «Войти в замер ББ».")}
    }
    fun setWhiteBalanceShift(ab:Int,gm:Int){
        if(ab !in -9..9 || gm !in -9..9)return
        val token=epoch();val mode=properties[LumixProp.WHITE_BALANCE_PARAM]?.current ?: return
        val a=properties[0x02000053] ?: return;val g=properties[0x02000054] ?: return
        val aa=UsbWhiteBalance.encodeShift(ab);val gg=UsbWhiteBalance.encodeShift(gm)
        if(aa !in a.choices || gg !in g.choices)return
        val seq=wbShiftWrites.incrementAndGet()
        writes.computeIfAbsent(a.code){AtomicLong()}.incrementAndGet();writes.computeIfAbsent(g.code){AtomicLong()}.incrementAndGet()
        io.schedule({
            if(!valid(token) || wbShiftWrites.get()!=seq)return@schedule
            val cam=camera() ?: return@schedule
            try{
                require(whiteBalanceCamera==null && cam.getDevicePropDesc(LumixProp.WHITE_BALANCE,2)?.current==mode){"ББ изменился — дождитесь обновления сетки"}
                check(cam.remote.setProperty(a,aa)){"GH5 отклонила подстройку A/B"}
                require(cam.getDevicePropDesc(LumixProp.WHITE_BALANCE,2)?.current==mode){"Режим ББ сменился во время подстройки"}
                check(cam.remote.setProperty(g,gg)){"GH5 отклонила подстройку G/M"}
                val next=cam.remote.whiteBalanceCapabilities(cam.deviceInfo?.model)
                post(token,cam){if(wbShiftWrites.get()==seq){properties=properties+next
                    message(if(next[a.code]?.current==aa && next[g.code]?.current==gg)"Подстройка ББ подтверждена камерой" else "GH5 вернула другую подстройку — сетка обновлена")}}
            }catch(t:Throwable){requestRefresh();post(token,cam){message(t.message ?: "Ошибка подстройки ББ")}}
        },140,TimeUnit.MILLISECONDS)
    }
    fun cancelWhiteBalanceMeasurement(){
        if(!whiteBalanceMeasuring && !whiteBalanceBusy)return
        wbMeasurementEpoch.incrementAndGet()
        val token=epoch();val cam=camera() ?: return
        io.execute{
            if(!valid(token) || camera()!==cam)return@execute
            try{
                val ok=endWhiteBalanceMeasurement(cam)
                post(token,cam){if(ok){whiteBalanceMeasuring=false;message("Замер закрыт")}else message("GH5 не подтвердила выход из замера — повторите закрытие");whiteBalanceBusy=false;requestRefresh()}
            }catch(t:Throwable){post(token,cam){whiteBalanceBusy=false;message("Выход из замера: ${t.message}")}}
        }
    }
    /** Called on the USB worker before a graceful session close as well as by the editor. */
    fun endWhiteBalanceMeasurement(cam:LumixCamera):Boolean {
        if(whiteBalanceCamera!==cam)return true
        val ok=cam.remote.whiteBalanceMeasure(UsbWhiteBalance.END)
        if(ok){whiteBalanceCamera=null;whiteBalanceSlot=null}
        return ok
    }

    private fun focus(label:String,action:(LumixCamera)->Boolean,accepted:()->Unit={}){
        if(focusBusy)return
        val token=epoch();val cam=camera() ?: return
        focusBusy=true
        io.execute{
            try{
                if(!valid(token) || camera()!==cam)return@execute
                val ok=action(cam)
                post(token,cam){
                    if(ok){accepted();feedback="$label: команда принята"}
                    else message("$label: GH5 отказала; проверьте режим AF/MF")
                }
            }catch(t:Throwable){post(token,cam){message("$label: ${t.message}")}}
            finally{post(token,cam){focusBusy=false}}
        }
    }
    fun point(x:Float,y:Float){
        val xx=x.coerceIn(0f,1f);val yy=y.coerceIn(0f,1f)
        focus("Точка AF",{cam->
            val zone=cam.getDevicePropDesc(LumixProp.AF_AREA_AREA_PARAM,2)
            val changed=zone!=null && zone.current!=0
            if(changed && (0 !in zone!!.allowed || !cam.setDevicePropValue(LumixProp.AF_AREA_AREA_PARAM,2,0)))false
            else {
                val ok=cam.remote.focusPoint((xx*1000).toInt(),(yy*1000).toInt())
                if(!ok && changed)cam.setDevicePropValue(LumixProp.AF_AREA_AREA_PARAM,2,zone!!.current)
                ok
            }
        }){focusTarget=xx to yy;telemetry=telemetry.copy(afStatus=null)}
    }
    fun confirmAssistPosition(p:Pair<Int,Int>){
        assistTarget=p
        assistPreferences.edit().putInt("x",p.first).putInt("y",p.second).apply()
    }
    fun moveAssist(x:Int,y:Int){val p=x.coerceIn(0,1000) to y.coerceIn(0,1000)
        pickAssistArea?.let{it(p.first,p.second);return}
        focus("Область MF",{it.remote.moveAssist(p.first,p.second)}){confirmAssistPosition(p)}}
    fun nudgeAssist(dx:Float,dy:Float,quarterTurns:Int){
        val p=assistTarget ?: (500 to 500)
        val screen=com.lumixmonitor.monitor.ImageGeometry.screenPoint(p.first/1000f,p.second/1000f,quarterTurns)
        val sensor=com.lumixmonitor.monitor.ImageGeometry.sensorPoint((screen.first+dx).coerceIn(0f,1f),(screen.second+dy).coerceIn(0f,1f),quarterTurns)
        moveAssist((sensor.first*1000).toInt(),(sensor.second*1000).toInt())
    }
    fun stepAssist(increase:Boolean)=focus(if(increase)"Лупа +1×" else "Лупа −1×",{it.remote.assistStep(increase)})
    fun aeLock(enabled:Boolean)=focus(if(enabled)"AF/AE Lock" else "Снять блокировку AF/AE",{it.remote.aeLock(enabled)})
    fun driveFocus(far:Boolean,speed:Int)=focus("MF",{it.manualFocus(if(far)"far" else "near",speed)})
    fun stopFocus(){val token=epoch();val cam=camera() ?: return
        io.execute{if(valid(token) && camera()===cam)runCatching{cam.remote.stopManualFocus()}.onFailure{PtpLog.add("Остановка MF: ${it.message}")}}}

    fun refreshFiles(){
        if(filesLoading || transferBusy)return
        val token=epoch();val cam=camera() ?: return
        filesLoading=true
        io.execute{
            try{if(!valid(token))return@execute
                val found=cam.remote.imageObjects()
                post(token,cam){files=found;filesChanged=false;message(if(found.isEmpty())"Среди последних файлов карты нет JPEG/RW2" else "Оригиналы на карте: ${found.size}")}
            }catch(t:Throwable){post(token,cam){message("Файлы GH5: ${t.message}")}}
            finally{post(token,cam){filesLoading=false}}
        }
    }
    fun download(item:CameraObject){
        if(transferBusy)return
        if(Build.VERSION.SDK_INT<29){message("Сохранение оригиналов доступно с Android 10");return}
        val token=epoch();val cam=camera() ?: return
        transferBusy=true;transferText="${item.name}: подготовка…"
        fileIo.execute{
            val resolver=app.contentResolver;var pending:Uri?=null
            try{
                check(valid(token) && camera()===cam){"Соединение изменилось"}
                val jpeg=item.mime=="image/jpeg"
                val values=ContentValues().apply{
                    put(MediaStore.MediaColumns.DISPLAY_NAME,item.name)
                    put(MediaStore.MediaColumns.MIME_TYPE,item.mime)
                    put(MediaStore.MediaColumns.RELATIVE_PATH,if(jpeg)"DCIM/GH5 Originals" else "Download/GH5 Originals")
                    put(MediaStore.MediaColumns.IS_PENDING,1)
                }
                val uri=resolver.insert(if(jpeg)MediaStore.Images.Media.EXTERNAL_CONTENT_URI else MediaStore.Downloads.EXTERNAL_CONTENT_URI,values)
                    ?: error("Не удалось создать файл")
                pending=uri
                val digest=MessageDigest.getInstance("SHA-256");var lastPost=0L
                resolver.openOutputStream(uri,"w")?.use { output ->
                    val stream=DigestOutputStream(output,digest)
                    cam.remote.download(item,stream,{disposed || !valid(token) || camera()!==cam}) { bytes ->
                        val now=SystemClock.elapsedRealtime()
                        if(now-lastPost>=200 || bytes==item.bytes){lastPost=now;post(token,cam){transferText="${item.name}: ${bytes*100/item.bytes}%"}}
                    }
                    stream.flush()
                } ?: error("Не удалось открыть файл")
                check(valid(token) && camera()===cam){"Загрузка отменена"}
                resolver.update(uri,ContentValues().apply{put(MediaStore.MediaColumns.IS_PENDING,0)},null,null)
                pending=null
                val hash=digest.digest().joinToString(""){"%02x".format(it)}
                PtpLog.add("Оригинал ${item.name}: ${item.bytes} байт SHA256=$hash")
                post(token,cam){downloadedUri=uri;downloadedMime=item.mime;transferText="${item.name}: сохранён оригинал";message(transferText)}
            }catch(t:Throwable){pending?.let{runCatching{resolver.delete(it,null,null)}};post(token,cam){transferText="Загрузка: ${t.message}";message(transferText)}}
            finally{main.post{transferBusy=false}}
        }
    }

    private val polling=io.scheduleWithFixedDelay({
        if(!active || disposed)return@scheduleWithFixedDelay
        val token=epoch();val cam=camera() ?: return@scheduleWithFixedDelay
        if(!valid(token))return@scheduleWithFixedDelay
        try{
            val now=SystemClock.elapsedRealtime()
            if(previousCamera!==cam){previousCamera=cam;refreshWanted=true;lastSlow=0;lastCaps=0}
            if(refreshWanted && now-lastCaps>=700){
                refreshWanted=false;lastCaps=now
                val caps=cam.remote.capabilities(cam.deviceInfo?.model);post(token,cam){properties=caps}
            }
            val slow=now-lastSlow>=10000
            val read=cam.remote.telemetry(slow,slow).copy(observedAtMs=SystemClock.elapsedRealtime())
            if(slow)lastSlow=now
            post(token,cam){telemetry=read.copy(battery=read.battery ?: if(!slow)telemetry.battery else null,
                focalLength=read.focalLength ?: if(!slow)telemetry.focalLength else null)
                if(read.recording==true)onRecording()}
        }catch(t:Throwable){PtpLog.add("Телеметрия GH5: ${t.message}")}
    },1200,1000,TimeUnit.MILLISECONDS)

    fun reset(){wbMeasurementEpoch.incrementAndGet();wbShiftWrites.incrementAndGet();previousCamera=null;refreshWanted=true;properties=emptyMap();telemetry=CameraTelemetry();focusBusy=false;filesLoading=false;focusTarget=null;assistTarget=savedAssist();pickAssistArea=null;files=emptyList();filesChanged=false;whiteBalanceCamera=null;whiteBalanceSlot=null;whiteBalanceMeasuring=false;whiteBalanceBusy=false}
    fun close(){disposed=true;polling.cancel(false);fileIo.shutdownNow()}
}
