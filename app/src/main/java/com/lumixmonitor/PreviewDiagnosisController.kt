package com.lumixmonitor

import android.app.Application
import android.content.ContentValues
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.provider.MediaStore
import androidx.compose.runtime.*
import com.lumixmonitor.lumix.*
import com.lumixmonitor.monitor.PreviewSignalStats
import com.lumixmonitor.ptp.PtpLog
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.util.concurrent.ExecutorService
import java.util.concurrent.atomic.AtomicLong
import java.util.concurrent.atomic.AtomicReference
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

data class PreviewDiagnosisCapture(val directory:File,val summary:String,val thumbnail:Bitmap?)

/** Captures a live USB JPEG after read-only camera queries. Never alters exposure or processing. */
class PreviewDiagnosisController(
    private val app:Application,private val io:ExecutorService,private val files:ExecutorService,
    private val camera:()->LumixCamera?,private val valid:()->Boolean,
) {
    private val main=Handler(Looper.getMainLooper())
    private val epoch=AtomicLong()
    private data class Pending(val epoch:Long,val slot:String,val camera:LumixCamera,val metadata:JSONObject)
    private val pending=AtomicReference<Pending?>(null)
    private val root=File(app.filesDir,"preview-diagnosis")
    var busy by mutableStateOf(false);private set
    var message by mutableStateOf("");private set
    var captures by mutableStateOf<Map<String,PreviewDiagnosisCapture>>(emptyMap());private set
    init {files.execute{
        val saved=listOf("photo","video").mapNotNull{slot->runCatching{
            val pointer=File(root,"$slot-latest.txt")
            val name=pointer.readText().trim();require(name.matches(Regex("(photo|video)-[0-9]+")))
            slot to load(File(root,name))
        }.getOrNull()}.toMap()
        main.post{captures=captures+saved.filterKeys{it !in captures}}
    }}
    private fun load(directory:File):PreviewDiagnosisCapture {
        val report=JSONObject(File(directory,"report.json").readText())
        val thumb=BitmapFactory.decodeFile(File(directory,"source.jpg").absolutePath,BitmapFactory.Options().apply{inSampleSize=4})
        return PreviewDiagnosisCapture(directory,report.getString("summary"),thumb)
    }
    fun capture(slot:String,appState:JSONObject){
        require(slot in listOf("photo","video"))
        if(busy)return
        val cam=camera()
        if(cam==null || !valid()){message="Нужна живая USB-картинка GH5";return}
        busy=true;message="Читаю настройки GH5, затем сохраняю следующий USB-кадр…"
        val request=epoch.incrementAndGet()
        io.execute{
            try{
                val readStart=SystemClock.elapsedRealtime()
                val packets=cam.remote.previewDiagnosis()
                val values=linkedMapOf<Int,ByteArray>()
                val records=JSONArray()
                packets.forEach{p->
                    if(p.response==0x2001)values.putAll(PanasonicData.blocks(p.data))
                    records.put(JSONObject().put("opcode","0x%04X".format(p.opcode)).put("parameter","0x%08X".format(p.parameter))
                        .put("response","0x%04X".format(p.response)).put("dataHex",p.data.joinToString(""){"%02x".format(it.toInt() and 255)}))
                }
                fun number(code:Int)=values[code]?.let{when(it.size){1->it[0].toLong() and 255;2->PanasonicData.u16(it,0)?.toLong();4->PanasonicData.u32(it,0);else->null}}
                val mode=number(0x02000082)?.toInt()
                val profile=number(0x02000011)?.toInt()
                val decoded=JSONObject()
                values.forEach{(code,_)->number(code)?.let{decoded.put("0x%08X".format(code),it)}}
                val metadata=JSONObject().put("schema",1).put("userStage",slot).put("createdAtMs",System.currentTimeMillis())
                    .put("readStartElapsedMs",readStart).put("readFinishedElapsedMs",SystemClock.elapsedRealtime())
                    .put("cameraMode",mode ?: JSONObject.NULL).put("photoStyle",profile ?: JSONObject.NULL)
                    .put("cameraModeText",mode?.let{LumixTables.nameOf(LumixTables.exposureMode,it)} ?: "Не получен")
                    .put("photoStyleText",profile?.let{LumixTables.nameOf(LumixTables.photoStyle,it)} ?: "Не получен")
                    .put("stageMismatch",if(mode==null || mode in 8..10)JSONObject.NULL else (slot=="video")!=(mode==5))
                    .put("currentValues",decoded).put("readPackets",records).put("app",appState)
                    .put("limits","JPEG code values, not RAW exposure or calibrated IRE. LCD brightness, camera LUT/HLG assist and video luminance range are not inferred from pixel statistics. Reads precede the captured frame; keep the camera mode and scene still.")
                main.post{
                    if(epoch.get()!=request)return@post
                    if(camera()!==cam || !valid()){busy=false;message="Соединение изменилось — повторите снимок";return@post}
                    pending.set(Pending(request,slot,cam,metadata))
                    main.postDelayed({
                        if(pending.get()?.epoch==request){pending.set(null);busy=false;message="Новый USB-кадр не получен. Вернитесь к живому просмотру."}
                    },5000)
                }
            }catch(t:Throwable){main.post{if(epoch.get()==request){busy=false;message="Диагностика: ${t.message}"}}}
        }
    }
    /** Called only with newly delivered live frames; held/display frames are never used. */
    fun offer(bitmap:Bitmap,jpeg:ByteArray){
        val request=pending.getAndSet(null) ?: return
        if(epoch.get()!=request.epoch || camera()!==request.camera){busy=false;return}
        val frameAt=SystemClock.elapsedRealtime()
        files.execute{
            try{
                if(epoch.get()!=request.epoch)return@execute
                val directory=File(root,"${request.slot}-${System.currentTimeMillis()}").apply{mkdirs()}
                val sample=Bitmap.createScaledBitmap(bitmap,160,120,true)
                val pixels=IntArray(sample.width*sample.height);sample.getPixels(pixels,0,sample.width,0,0,sample.width,sample.height)
                val stats=PreviewSignalStats.from(pixels)
                val report=request.metadata.put("frameElapsedMs",frameAt).put("width",bitmap.width).put("height",bitmap.height)
                    .put("bitmapColorSpace",bitmap.colorSpace?.toString() ?: "unknown").put("bitmapConfig",bitmap.config.toString())
                    .put("jpegStatistics",JSONObject().put("min",stats.min).put("median",stats.median).put("max",stats.max).put("mean",stats.mean)
                        .put("sampling","Bilinear resize to 160x120 of the entire decoded JPEG, including any source borders; extrema refer to sampled pixels, not full-resolution clipping.")
                        .put("samples",stats.samples).put("atOrBelow16",stats.atOrBelow16).put("atOrAbove235",stats.atOrAbove235))
                val summary="${report.getString("cameraModeText")} · ${report.getString("photoStyleText")}\n${bitmap.width}×${bitmap.height} · выборка JPEG: min ${stats.min}, median ${stats.median}, max ${stats.max}"+
                    if(report.optBoolean("stageMismatch",false))"\nВнимание: выбранный этап не совпадает с режимом GH5" else ""
                report.put("summary",summary)
                File(directory,"source.jpg").writeBytes(jpeg)
                File(directory,"decoded.png").outputStream().use{check(bitmap.compress(Bitmap.CompressFormat.PNG,100,it))}
                File(directory,"report.json").writeText(report.toString(2))
                if(epoch.get()!=request.epoch)return@execute
                File(root,"${request.slot}-latest.txt").writeText(directory.name)
                val saved=load(directory)
                main.post{if(epoch.get()==request.epoch){captures=captures+(request.slot to saved);busy=false;message="Этап сохранён. Живой поток продолжается."}}
                PtpLog.add("PREVIEW DIAG ${request.slot}: $summary")
            }catch(t:Throwable){main.post{if(epoch.get()==request.epoch){busy=false;message="Сохранение диагностики: ${t.message}"}}}
        }
    }
    fun cancel(){epoch.incrementAndGet();pending.set(null);busy=false}
    fun export(){
        if(busy || captures.isEmpty())return
        if(Build.VERSION.SDK_INT<29){message="Экспорт доступен с Android 10";return}
        val selected=captures.toMap();busy=true
        files.execute{
            var destination:android.net.Uri?=null
            try{
                val name="GH5-preview-diagnosis-${System.currentTimeMillis()}.zip"
                destination=app.contentResolver.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI,ContentValues().apply{
                    put(MediaStore.MediaColumns.DISPLAY_NAME,name);put(MediaStore.MediaColumns.MIME_TYPE,"application/zip")
                    put(MediaStore.MediaColumns.RELATIVE_PATH,"Download/GH5 Diagnostics");put(MediaStore.MediaColumns.IS_PENDING,1)
                }) ?: error("Не удалось создать ZIP")
                app.contentResolver.openOutputStream(destination!!).use{output->
                    requireNotNull(output)
                    ZipOutputStream(output).use{zip->selected.forEach{(slot,capture)->
                        listOf("source.jpg","decoded.png","report.json").forEach{name->
                            zip.putNextEntry(ZipEntry("$slot/$name"));File(capture.directory,name).inputStream().use{it.copyTo(zip)};zip.closeEntry()
                        }
                    }}
                }
                app.contentResolver.update(destination!!,ContentValues().apply{put(MediaStore.MediaColumns.IS_PENDING,0)},null,null)
                main.post{busy=false;message="ZIP сохранён: Download/GH5 Diagnostics/$name"}
            }catch(t:Throwable){destination?.let{runCatching{app.contentResolver.delete(it,null,null)}};main.post{busy=false;message="Экспорт: ${t.message}"}}
        }
    }
}
