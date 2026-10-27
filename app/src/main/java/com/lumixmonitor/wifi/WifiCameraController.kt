package com.lumixmonitor.wifi

import android.app.Application
import android.graphics.Bitmap
import android.net.wifi.WifiManager
import android.content.Context
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import androidx.compose.runtime.*
import com.lumixmonitor.lumix.CameraTelemetry
import com.lumixmonitor.ptp.PtpLog
import java.net.InetAddress
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicLong
import java.util.concurrent.atomic.AtomicBoolean

/** HTTP control and UDP preview are independent. No USB/PTP operation is used here. */
class WifiCameraController(private val app:Application,
    private val onFrame:(Bitmap,ByteArray)->Unit,private val onConnected:(String)->Unit,
    private val onStatus:(String)->Unit,private val onStats:(String)->Unit,private val onFailure:(String)->Unit) {
    private val main=Handler(Looper.getMainLooper())
    private val io=Executors.newSingleThreadScheduledExecutor{Thread(it,"wifi-control")}
    private val epoch=AtomicLong();private val writes=ConcurrentHashMap<String,AtomicLong>()
    private val lifecycle=Any()
    private val prefs=app.getSharedPreferences("wifi-camera",0)
    private val clientId=prefs.getString("client_id",null) ?: ("4D454930-"+UUID.randomUUID().toString().substring(9)).also{prefs.edit().putString("client_id",it).apply()}
    @Volatile private var session:Session?=null
    @Volatile private var connectingLink:WifiNetwork?=null
    @Volatile private var wanted:CameraEndpoint?=null
    @Volatile private var foreground=true
    private var retry=0
    private val focusSequence=AtomicLong()
    private val assistSequence=AtomicLong()
    private data class Session(val token:Long,val link:WifiNetwork,val stream:WifiLiveview,val catalog:WifiSettingsCatalog,
        var wifiLock:WifiManager.WifiLock?,var state:Map<String,String> = emptyMap(),var failures:Int=0,
        var poll:Int=0,var lastControlReplyAt:Long=SystemClock.elapsedRealtime(),
        val recovery:WifiStreamRecovery=WifiStreamRecovery(SystemClock.elapsedRealtime()))
    var address by mutableStateOf(prefs.getString("address","192.168.54.1") ?: "192.168.54.1");private set
    var model by mutableStateOf("LUMIX Wi-Fi");private set
    var connected by mutableStateOf(false);private set
    var starting by mutableStateOf(false);private set
    var discovering by mutableStateOf(false);private set
    var discovered by mutableStateOf<List<WifiDiscoveredCamera>>(emptyList());private set
    var message by mutableStateOf("");private set
    var telemetry by mutableStateOf(CameraTelemetry());private set
    var options by mutableStateOf<Map<String,List<WifiOption>>>(emptyMap());private set
    var values by mutableStateOf<Map<String,String>>(emptyMap());private set
    var shutterMovie by mutableStateOf(false)
    var lowLatency by mutableStateOf(prefs.getBoolean("low_latency",false));private set
    var commandBusy by mutableStateOf(false);private set
    private var pendingCommands=0
    private var stopRequested=false
    var cameraStatus by mutableStateOf(WifiCameraStatus());private set
    var lastPresentedAt by mutableStateOf(0L);private set
    var assist by mutableStateOf(WifiAssistState());private set
    var assistRange by mutableStateOf<WifiAssistRange?>(null);private set
    var assistBusy by mutableStateOf(false);private set
    var touchAf by mutableStateOf(false);private set
    var focusTarget by mutableStateOf<Pair<Float,Float>?>(null);private set
    var gallery by mutableStateOf(false);private set
    @Volatile internal var galleryRequested=false
    @Volatile private var returningPreview=false
    val cardFiles=WifiFileBrowser(app,io,{session?.link},{epoch.get()},{gallery},{resumePreview()})
    private fun post(token:Long,action:()->Unit){main.post{if(epoch.get()==token)action()}}
    fun discover(){
        if(discovering)return
        discovering=true;message="Searching for LUMIX on Wi-Fi…"
        io.execute{val result=runCatching{WifiDiscovery.find(app)}
            main.post{discovering=false;discovered=result.getOrDefault(emptyList());message=result.exceptionOrNull()?.message ?: if(discovered.isEmpty())"No SSDP reply. For direct camera Wi-Fi use 192.168.54.1, or enter its local IP." else "Select the camera address"}}
    }
    fun diagnose(){
        val endpoint=runCatching{CameraEndpoint.parse(address)}.getOrElse{message=it.message ?: "Invalid address";return}
        val token=epoch.get()
        io.execute{
            var owned:WifiNetwork?=null
            try{
                val net=session?.link ?: WifiNetwork(app,endpoint).also{owned=it}
                val r=net.request("getstate",timeoutMs=1800)
                PtpLog.add("WIFI DIAG ${r.text.take(2400)}")
                post(token){message="GH5: ${r.result} · mode=${r.values["cammode"] ?: "—"} · rec=${r.values["rec"] ?: "—"}";onStatus(message)}
            }catch(t:Throwable){post(token){message="GH5: ${t.message}";onStatus(message)}}
            finally{owned?.close()}
        }
    }
    fun connect(text:String){
        val endpoint=try{CameraEndpoint.parse(text)}catch(t:Throwable){message=t.message ?: "Invalid address";onFailure(message);return}
        disconnect(false)
        address=endpoint.address;prefs.edit().putString("address",address).apply()
        wanted=endpoint;retry=0;starting=true;message="Connecting to GH5 Wi-Fi…"
        val token=epoch.get();io.execute{open(endpoint,token)}
    }
    private fun open(endpoint:CameraEndpoint,token:Long){
        if(epoch.get()!=token || wanted!=endpoint)return
        var link:WifiNetwork?=null;var stream:WifiLiveview?=null;var wifiLock:WifiManager.WifiLock?=null
        try {
            val net=WifiNetwork(app,endpoint);link=net
            synchronized(lifecycle){check(epoch.get()==token){"Connection cancelled"};connectingLink=net}
            post(token){onStatus("Wi-Fi: waiting for the camera — approve GH5 Monitor on GH5 if requested")}
            val deadline=SystemClock.elapsedRealtime()+30_000
            while(true){
                check(epoch.get()==token){"Connection cancelled"}
                val access=net.request("accctrl",mapOf("type" to "req_acc","value" to clientId,"value2" to "GH5 Monitor"))
                if(access.ok)break
                if(!access.waitingApproval || SystemClock.elapsedRealtime()>deadline)error("Access: ${access.result}. Select Remote Shooting & View on GH5.")
                Thread.sleep(1000)
            }
            check(epoch.get()==token){"Connection cancelled"}
            val first=net.request("getstate");require(first.ok){"Camera state: ${first.result}"}
            // Connect is an explicit monitor request; switch to rec once only, never hammer it in recovery.
            if(first.values["cammode"]=="play"){
                require(first.values["rec"]!="on"){"Camera is recording"}
                val mode=net.request("camcmd",mapOf("value" to "recmode"));require(mode.ok){"Shooting mode: ${mode.result}"}
                Thread.sleep(1000)
            }
            val catalog=WifiSettingsCatalog()
            val capability=net.request("getinfo",mapOf("type" to "capability"),2500)
            val range=WifiAssistRange.from(capability)
            post(token){assistRange=range}
            val menu=net.request("getinfo",mapOf("type" to "allmenu"),2500)
            if(menu.ok)catalog.parseMenu(menu)
            runCatching{catalog.applyLens(net.request("getinfo",mapOf("type" to "lens")))}
            runCatching{catalog.applyCurrent(net.request("getinfo",mapOf("type" to "curmenu")))}
            for(type in listOf("focal","shtrspeed","iso","whitebalance","exposure","photostyle")){
                if(epoch.get()!=token)error("Connection cancelled")
                val r=net.request("getsetting",mapOf("type" to type));if(r.ok)catalog.applyValues(r.values)
            }
            val name=menu.root?.getElementsByTagName("menuset")?.item(0)?.attributes?.getNamedItem("model")?.nodeValue?.let{"LUMIX $it"} ?: "LUMIX Wi-Fi"
            val receiver=net.socket()
            val startAccepted=AtomicBoolean(false)
            stream=WifiLiveview(receiver,InetAddress.getByName(endpoint.host),
                {bitmap,jpeg->if(epoch.get()==token){if(startAccepted.get())retry=0;lastPresentedAt=SystemClock.elapsedRealtime();onFrame(bitmap,jpeg)}},
                {text->if(epoch.get()==token){
                    if(message=="Wi-Fi connected — waiting for UDP frames"){message="Wi-Fi preview active";onStatus(message)}
                    onStats(text)
                }},
                {t->if(epoch.get()==token)io.execute{lost(token,t)}})
            stream.active=foreground
            wifiLock=acquireWifiLock()
            val s=Session(token,net,stream,catalog,wifiLock,state=first.values)
            synchronized(lifecycle){
                check(epoch.get()==token && wanted==endpoint){"Connection cancelled"}
                session=s;connectingLink=null
                stream.start() // UDP must already be bound before telling the camera to send.
            }
            val start=net.request("startstream",mapOf("value" to stream.port.toString()))
            // A previous stream can still be arriving after control-path interruption.
            // BUSY is not a reason to close this new receiver and lose those frames.
            require(start.ok || start.result=="err_busy"){"Start stream: ${start.result}"}
            startAccepted.set(true)
            s.recovery.started(SystemClock.elapsedRealtime())
            post(token){connected=true;starting=false;model=name;message="Wi-Fi connected — waiting for UDP frames";onConnected(name);onStatus(if(start.ok)message else "GH5 busy — keeping the UDP receiver and waiting for frames")}
            publish(s)
            PtpLog.add("Wi-Fi session opened; UDP receiver bound before startstream")
        }catch(t:Throwable){
            PtpLog.add("WIFI OPEN failed: ${t.javaClass.simpleName} ${t.message}")
            stream?.stop();link?.close();if(wifiLock?.isHeld==true)wifiLock.release()
            if(epoch.get()==token){
                session=null;connectingLink=null
                if(retry>0 && wanted==endpoint)retryOpen(endpoint,token,t)
                else post(token){starting=false;connected=false;message="Wi-Fi: ${t.message}";onFailure(message)}
            }
        }
    }
    private fun publish(s:Session){
        val list=WifiSettingsCatalog.TYPES.associateWith{s.catalog.available(it)};val current=s.catalog.values.toMap()
        val state=s.state
        val status=WifiCameraStatus.from(state)
        val battery=status.battery?.takeIf{it.percent}?.current
        val rec=when(state["rec"]){"on"->1;"off"->if(state["cammode"]=="play")3 else 0;else->null}
        val t=CameraTelemetry(observedAtMs=SystemClock.elapsedRealtime(),status=rec,battery=battery,
            remainingImages=state["remaincapacity"]?.toLongOrNull()?.takeIf{it>=0},
            remainingSeconds=state["video_remaincapacity"]?.toLongOrNull()?.takeIf{it>=0},
            elapsedSeconds=state["rec_time"]?.toLongOrNull()?.takeIf{it>=0})
        post(s.token){options=list;values=current;telemetry=t;cameraStatus=status}
    }
    fun choices(type:String)=options[type].orEmpty()
    val shootingMode:String get()=WifiShootingMode.label(values["recmode"],values["recmode_detail"])
    fun current(type:String)=values[type]?.let{v->choices(type).firstOrNull{it.value==v || it.optionId==v}}
    fun text(type:String):String {
        if(type=="whitebalance" && values[type]?.startsWith("color_temp")==true)
            return values["settingvalue"]?.toIntOrNull()?.takeIf{it in 1500..15000}?.let{"${it}K"} ?: WifiSettingsCatalog.label(type,values.getValue(type))
        return current(type)?.label ?: values[type]?.let{WifiSettingsCatalog.label(type,it)} ?: "—"
    }
    fun select(type:String,id:Int){
        val s=session ?: return;val option=choices(type).firstOrNull{it.id==id} ?: return
        val sequence=writes.computeIfAbsent(type){AtomicLong()};val request=sequence.incrementAndGet()
        io.execute{
            if(epoch.get()!=s.token || session!==s || sequence.get()!=request)return@execute
            try {
                val params=mutableMapOf("type" to option.type,"value" to option.value)
                option.value2?.let{params["value2"]=it}
                val r=s.link.request("setsetting",params);require(r.ok){r.result}
                val read=s.link.request("getsetting",mapOf("type" to type));if(read.ok)s.catalog.applyValues(read.values)
                runCatching{s.catalog.applyCurrent(s.link.request("getinfo",mapOf("type" to "curmenu")))}
                if(type=="liveviewsize" && s.state["rec"]!="on"){
                    // Apply quality only after the camera accepted an advertised value.
                    val stop=s.link.request("stopstream");if(stop.ok){Thread.sleep(300)
                        val start=s.link.request("startstream",mapOf("value" to s.stream.port.toString()))
                        if(start.ok)s.recovery.started(SystemClock.elapsedRealtime())
                    }
                }
                publish(s);post(s.token){message="$type: ${if(s.catalog.values[type]==option.value)"confirmed" else "accepted; waiting for readback"}";onStatus(message)}
            }catch(t:Throwable){post(s.token){message="$type: ${t.message}";onStatus(message)}}
        }
    }
    fun command(value:String){
        if(galleryRequested || (commandBusy && value!="video_recstop") || (value=="video_recstop" && stopRequested))return
        val s=session ?: return
        val label=when(value){"video_recstart"->"Начать запись";"video_recstop"->"Остановить запись";"capture"->"Снимок";"oneshot_af"->"Автофокус";else->"Команда камеры"}
        pendingCommands++;commandBusy=true;if(value=="video_recstop")stopRequested=true
        io.execute{try{
            if(epoch.get()!=s.token || session!==s)return@execute
            val result=s.link.request("camcmd",mapOf("value" to value),2500)
            require(result.ok){result.result}
            if(value.startsWith("video_rec"))Thread.sleep(500)
            val state=s.link.request("getstate");if(state.ok){s.state=state.values;publish(s)}
            post(s.token){message=when{value=="video_recstart" && s.state["rec"]=="on"->"GH5 записывает видео";value=="video_recstop" && s.state["rec"]=="off"->"Запись GH5 остановлена";else->"$label: принято GH5"};onStatus(message)}
        }catch(t:Throwable){post(s.token){message="$label: ${t.message}";onStatus(message)}}
        finally{post(s.token){pendingCommands=(pendingCommands-1).coerceAtLeast(0);commandBusy=pendingCommands>0;if(value=="video_recstop")stopRequested=false}}}
    }
    fun toggleTouchAf(){touchAf=!touchAf}
    fun point(x:Int,y:Int){
        val s=session ?: return;if(galleryRequested)return
        val xx=x.coerceIn(0,1000);val yy=y.coerceIn(0,1000)
        io.execute{
            if(epoch.get()!=s.token || session!==s)return@execute
            try {
                val mode=s.link.request("getsetting",mapOf("type" to "focusmode"))
                require(mode.ok && mode.values["focusmode"] in listOf("afs","aff","afc")){"Для AF касанием выберите AFS/AFC на GH5"}
                val r=try{s.link.request("camctrl",mapOf("type" to "touch","value" to "$xx/$yy","value2" to "on"))}
                    finally{runCatching{s.link.request("camctrl",mapOf("type" to "touch","value" to "0/0","value2" to "off"))}}
                require(r.ok){r.result}
                post(s.token){focusTarget=xx/1000f to yy/1000f;message="Касание: принято GH5"}
            }catch(t:Throwable){post(s.token){message="Касание: ${t.message}";onStatus(message)}}
        }
    }
    fun toggleAssist(){
        val s=session ?: return;if(assistBusy || galleryRequested)return
        val enabled=!assist.active;assistBusy=true
        io.execute{
            try{
                if(epoch.get()!=s.token || session!==s)return@execute
                val r=s.link.request("camctrl",mapOf("type" to "asst_disp","value" to if(enabled)"current_auto" else "off","value2" to "mf_asst/0/0"))
                require(r.ok){"${r.result}. Установите MF и приоритет «Смартфон» на GH5"}
                var actual=WifiAssistState.parse(r,assist) ?: error("GH5 не вернула состояние лупы")
                if(enabled && actual.display=="pinp"){
                    val full=s.link.request("camctrl",mapOf("type" to "asst_disp","value" to "full","value2" to "mf_asst/0/0"))
                    if(full.ok)actual=WifiAssistState.parse(full,actual) ?: actual
                }
                val confirmed=actual
                post(s.token){assist=confirmed;message=if(confirmed.active)"Лупа GH5 ${confirmed.magnification?.div(100f)}×" else "Лупа GH5 выключена"}
            }catch(t:Throwable){post(s.token){message="Лупа: ${t.message}";onStatus(message)}}
            finally{post(s.token){assistBusy=false}}
        }
    }
    fun moveAssist(x:Int,y:Int){
        if(!assist.active)return
        val seq=assistSequence.incrementAndGet();val s=session ?: return
        io.schedule({if(epoch.get()!=s.token || session!==s || assistSequence.get()!=seq)return@schedule
            try{val r=s.link.request("camctrl",mapOf("type" to "asst_disp","value" to "move","value2" to "mf_asst/${x.coerceIn(0,1000)}/${y.coerceIn(0,1000)}"))
                require(r.ok){r.result};val actual=WifiAssistState.parse(r,assist) ?: error("Нет координат GH5");post(s.token){assist=actual}
            }catch(t:Throwable){post(s.token){message="Область лупы: ${t.message}"}}
        },65,TimeUnit.MILLISECONDS)
    }
    fun stepAssist(increase:Boolean){
        if(!assist.active || assistBusy)return
        val s=session ?: return;val next=(assist.magnification ?: return)+(if(increase)100 else -100)
        val range=assistRange ?: return;assistBusy=true
        io.execute{try{
            if(epoch.get()!=s.token || session!==s)return@execute
            val max=if(assist.display=="pinp")minOf(range.maximum,600) else range.maximum
            val r=s.link.request("camctrl",mapOf("type" to "change_disp_mag","value" to next.coerceIn(range.minimum,max).toString()))
            require(r.ok){r.result};val actual=WifiAssistState.parse(r,assist) ?: error("Нет масштаба GH5");post(s.token){assist=actual}
        }catch(t:Throwable){post(s.token){message="Масштаб лупы: ${t.message}"}}finally{post(s.token){assistBusy=false}}}
    }
    fun driveFocus(far:Boolean,fast:Boolean=false){
        if(galleryRequested)return
        val sequence=focusSequence.incrementAndGet();val s=session ?: return
        io.execute{if(epoch.get()!=s.token || session!==s || focusSequence.get()!=sequence)return@execute
            try{val r=s.link.request("camctrl",mapOf("type" to "focus","value" to "${if(far)"tele" else "wide"}-${if(fast)"fast" else "normal"}"),1000)
                post(s.token){message=if(r.ok)"Шаг MF: принят GH5" else "MF: ${r.result}"}
            }catch(t:Throwable){post(s.token){message="MF: ${t.message}"}}
        }
    }
    fun stopFocus(){
        // GH5's focus commands are bounded steps, returning ok,position,range.
        // Unlike newer bodies it rejects focus-stop. Cancel queued repeats;
        // an already accepted short step is allowed to finish.
        focusSequence.incrementAndGet()
    }
    fun openGallery(){
        val s=session ?: return;if(galleryRequested || commandBusy)return
        galleryRequested=true
        io.execute{try{
            if(epoch.get()!=s.token || session!==s)return@execute
            val state=s.link.request("getstate");require(state.ok && state.values["rec"]=="off" && state.values["sd_access"]!="on" && state.values["sd2_access"]!="on" && state.values["interval_status"] in listOf(null,"off")){"Дождитесь завершения записи или работы карты"}
            if(assist.active){val stop=s.link.request("camctrl",mapOf("type" to "asst_disp","value" to "off","value2" to "mf_asst/0/0"));require(stop.ok){"Не удалось закрыть лупу"}}
            s.link.request("stopstream");s.stream.active=false
            val play=s.link.request("camcmd",mapOf("value" to "playmode"));require(play.ok){play.result}
            var ready=false
            repeat(12){if(!ready){Thread.sleep(200);val r=s.link.request("getstate");if(r.ok){s.state=r.values;ready=r.values["cammode"]=="play"}}}
            require(ready){"GH5 не перешла в просмотр"}
            post(s.token){gallery=true;assist=WifiAssistState();cardFiles.browse("0")}
        }catch(t:Throwable){
            if(session===s && epoch.get()==s.token)runCatching{
                val state=s.link.request("getstate")
                if(state.ok && state.values["cammode"]=="play" && state.values["rec"]=="off"){
                    val rec=s.link.request("camcmd",mapOf("value" to "recmode"));if(rec.ok)Thread.sleep(800)
                }
                s.stream.active=foreground
                val start=s.link.request("startstream",mapOf("value" to s.stream.port.toString()));if(start.ok)s.recovery.started(SystemClock.elapsedRealtime())
            }
            galleryRequested=false;post(s.token){message="Галерея: ${t.message}";onStatus(message)}
        }}
    }
    fun resumePreview(){
        val s=session ?: return;if(cardFiles.transferBusy || returningPreview)return
        returningPreview=true
        io.execute{try{
            if(epoch.get()!=s.token || session!==s)return@execute
            val state=s.link.request("getstate");require(state.ok && (state.values["cammode"]=="play" || state.values["rec"]=="off")){"GH5 занята"}
            if(state.values["cammode"]=="play"){val rec=s.link.request("camcmd",mapOf("value" to "recmode"));require(rec.ok){rec.result};Thread.sleep(800)}
            s.stream.active=foreground
            val start=s.link.request("startstream",mapOf("value" to s.stream.port.toString()));require(start.ok || start.result=="err_busy"){start.result}
            s.recovery.started(SystemClock.elapsedRealtime());post(s.token){cardFiles.clear();gallery=false;galleryRequested=false;message="Живой просмотр"}
        }catch(t:Throwable){post(s.token){message="Возврат к камере: ${t.message}";cardFiles.notice(message);onStatus(message)}}finally{returningPreview=false}}
    }
    private fun poll(){
        val s=session ?: return
        if(epoch.get()!=s.token)return
        try {
            val state=s.link.request("getstate",timeoutMs=1500)
            s.lastControlReplyAt=SystemClock.elapsedRealtime()
            if(!state.ok){
                if(state.result=="err_reject"){wanted=null;lost(s.token,IllegalStateException("Camera ended the remote session"));return}
                // Busy means wait, not a request to change camera mode.
                post(s.token){onStatus("Wi-Fi: ${state.result}; waiting for GH5")};return
            }
            s.state=state.values;s.failures=0;s.poll++
            if(galleryRequested){publish(s);return}
            // A body-dial change is reported by curmenu, not getstate. Refresh it
            // every foreground poll, before slower lens reads can delay the badge.
            // Fixed-delay supervision keeps at most one poll running, without catch-up work.
            if(foreground || s.poll%5==0){
                val r=s.link.request("getinfo",mapOf("type" to "curmenu"),1200)
                s.catalog.applyCurrent(r);publish(s)
            }
            if(s.poll%3==0 && foreground && state.values["cammode"]=="rec"){
                for(type in listOf("focal","shtrspeed","focusmode")){val r=s.link.request("getsetting",mapOf("type" to type),900);if(r.ok)s.catalog.applyValues(r.values)}
            }
            publish(s)
            val now=SystemClock.elapsedRealtime()
            val stale=now-s.stream.lastFrameAt>3500
            val recording=when(state.values["rec"]){"on"->true;"off"->false;else->null}
            val busy=state.values["sd_access"]=="on" || state.values["interval_status"]?.let{it!="off"}==true
            if(s.recovery.shouldRestart(now,s.stream.lastFrameAt,state.values["cammode"]=="rec",recording,busy)){
                val start=s.link.request("startstream",mapOf("value" to s.stream.port.toString()))
                post(s.token){onStatus("Wi-Fi: stream recovery ${start.result}; no mode switch")}
            }
            if(stale && state.values["cammode"]=="play")post(s.token){onStatus("GH5 playback: return to shooting on the camera")}
        }catch(t:Throwable){
            s.failures++
            if(s.failures>=3 && WifiSessionHealth.expired(SystemClock.elapsedRealtime(),s.stream.lastFrameAt,s.lastControlReplyAt))lost(s.token,t)
            else post(s.token){onStatus("Wi-Fi control reply delayed — keeping the video receiver")}
        }
    }
    private fun lost(token:Long,t:Throwable){
        if(epoch.get()!=token)return
        PtpLog.add("WIFI session interrupted: ${t.javaClass.simpleName} ${t.message}")
        val old=session ?: return;session=null;old.stream.stop();old.link.close();old.wifiLock?.let{if(it.isHeld)it.release()}
        galleryRequested=false;returningPreview=false;focusSequence.incrementAndGet();assistSequence.incrementAndGet()
        post(token){cardFiles.clear();gallery=false;assist=WifiAssistState();assistBusy=false;focusTarget=null;commandBusy=false;pendingCommands=0;stopRequested=false;telemetry=CameraTelemetry();cameraStatus=WifiCameraStatus()}
        val endpoint=wanted
        if(endpoint!=null)retryOpen(endpoint,token,t)
        else post(token){connected=false;starting=false;message="Wi-Fi disconnected: ${t.message}";onFailure(message)}
    }
    private fun retryOpen(endpoint:CameraEndpoint,token:Long,t:Throwable){
        if(retry<4){val delay=2L shl retry++;post(token){connected=false;starting=true;onStatus("Wi-Fi interrupted; retry in ${delay}s")}
            io.schedule({if(epoch.get()==token && wanted==endpoint)open(endpoint,token)},delay,TimeUnit.SECONDS)
        }else post(token){connected=false;starting=false;message="Wi-Fi disconnected: ${t.message}";onFailure(message)}
    }
    fun setForeground(active:Boolean){foreground=active;session?.stream?.active=active && !galleryRequested;if(!active)stopFocus()}
    private fun acquireWifiLock():WifiManager.WifiLock?=runCatching{
        val wifi=app.applicationContext.getSystemService(Context.WIFI_SERVICE) as WifiManager
        // On Android 14+ HIGH_PERF is replaced by LOW_LATENCY by the platform.
        val mode=if(Build.VERSION.SDK_INT>=34 || (lowLatency && Build.VERSION.SDK_INT>=29))WifiManager.WIFI_MODE_FULL_LOW_LATENCY else WifiManager.WIFI_MODE_FULL_HIGH_PERF
        wifi.createWifiLock(mode,"gh5-liveview").apply{setReferenceCounted(false);acquire()}
    }.getOrNull()
    fun chooseLowLatency(value:Boolean){
        lowLatency=value;prefs.edit().putBoolean("low_latency",value).apply()
        val s=session ?: return
        io.execute{
            val next=acquireWifiLock()
            synchronized(lifecycle){
                if(session!==s || epoch.get()!=s.token){if(next?.isHeld==true)next.release();return@execute}
                if(next!=null){val old=s.wifiLock;s.wifiLock=next;if(old?.isHeld==true)old.release()}
            }
            post(s.token){message=if(next==null)"Wi-Fi performance request unavailable" else if(value)"Wi-Fi: low-latency requested" else "Wi-Fi: throughput requested";onStatus(message)}
        }
    }
    fun disconnect(graceful:Boolean=true){
        val closeAssist=assist.active
        val old=synchronized(lifecycle){
            wanted=null;epoch.incrementAndGet()
            connectingLink?.close();connectingLink=null
            session.also{session=null}
        }
        starting=false;connected=false;commandBusy=false;pendingCommands=0;stopRequested=false
        focusSequence.incrementAndGet();assistSequence.incrementAndGet();galleryRequested=false;returningPreview=false;gallery=false;cardFiles.clear()
        options=emptyMap();values=emptyMap();telemetry=CameraTelemetry();cameraStatus=WifiCameraStatus();lastPresentedAt=0
        assist=WifiAssistState();assistRange=null;assistBusy=false;focusTarget=null
        old?.stream?.stop()
        old?.wifiLock?.let{if(it.isHeld)it.release()}
        if(old!=null)io.execute{try{
            if(closeAssist && old.state["rec"]!="on")runCatching{old.link.request("camctrl",mapOf("type" to "asst_disp","value" to "off","value2" to "mf_asst/0/0"),900)}
            if(graceful && old.state["rec"]!="on")old.link.request("stopstream",timeoutMs=900)
        }catch(_:Throwable){}finally{old.link.close()}}
    }
    private val supervisor=io.scheduleWithFixedDelay({runCatching{poll()}.onFailure{PtpLog.add("Wi-Fi supervisor: ${it.message}")}},1,1,TimeUnit.SECONDS)
    fun close(){disconnect();cardFiles.close();supervisor.cancel(false);io.shutdown()}
}
