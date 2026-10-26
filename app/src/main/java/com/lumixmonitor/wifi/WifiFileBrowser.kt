package com.lumixmonitor.wifi

import android.app.Application
import android.content.ContentValues
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.provider.MediaStore
import androidx.compose.runtime.*
import java.io.ByteArrayOutputStream
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicLong
import java.util.concurrent.atomic.AtomicReference

/** Playback owns a scoped mode; binary transfers never block camera keep-alives. */
class WifiFileBrowser(private val app:Application,private val control:ExecutorService,
    private val link:()->WifiNetwork?,private val sessionEpoch:()->Long,private val active:()->Boolean,
    private val leave:()->Unit){
    private val main=Handler(Looper.getMainLooper());private val files=Executors.newSingleThreadExecutor{Thread(it,"wifi-files")}
    private val epoch=AtomicLong();private val connection=AtomicReference<WifiNetwork?>()
    var items by mutableStateOf<List<WifiCardFile>>(emptyList());private set
    var loading by mutableStateOf(false);private set
    var transferBusy by mutableStateOf(false);private set
    var message by mutableStateOf("");private set
    var currentId by mutableStateOf("0");private set
    var start by mutableStateOf(0);private set
    var total by mutableStateOf(0);private set
    var returned by mutableStateOf(0);private set
    var preview by mutableStateOf<Bitmap?>(null);private set
    var selected by mutableStateOf<WifiCardFile?>(null);private set
    var downloadedUri by mutableStateOf<Uri?>(null);private set
    private val parents=ArrayDeque<String>()
    private var cachedOriginal:Pair<String,ByteArray>?=null
    fun browse(id:String=currentId,offset:Int=0){
        val net=link() ?: return;if(loading || transferBusy || !active())return
        val token=epoch.get();val session=sessionEpoch();loading=true;message="Читаю карту GH5…"
        control.execute{try{
            if(epoch.get()!=token || sessionEpoch()!=session || !active())return@execute
            val page=WifiCardFiles.parse(net.browse(WifiCardFiles.browseBody(id,offset)),net.endpoint.host)
            main.post{if(epoch.get()==token && sessionEpoch()==session && active()){items=page.items;currentId=id;start=offset;total=page.total;returned=page.returned;loading=false;message="${offset+1}–${offset+page.returned} из ${page.total}"}}
        }catch(t:Throwable){main.post{if(epoch.get()==token){loading=false;message="Карта: ${t.message}"}}}}
    }
    fun open(item:WifiCardFile){
        if(loading || transferBusy || !active())return
        if(item.container){parents.addLast(currentId);browse(item.id);return}
        selected=item;preview=null;cachedOriginal=null;val res=item.original?.takeIf{it.mime=="image/jpeg"} ?: item.preview ?: run{message="У этого файла нет JPEG-превью";return}
        val net=link() ?: return;val token=epoch.get();val session=sessionEpoch();transferBusy=true;message="Загружаю предпросмотр…"
        files.execute{var owned:WifiNetwork?=null
            try{
                check(epoch.get()==token && sessionEpoch()==session && active()){"Передача отменена"}
                val transfer=WifiNetwork(app,net.endpoint);owned=transfer;connection.set(transfer)
                check(epoch.get()==token && sessionEpoch()==session && active()){"Передача отменена"}
                val out=ByteArrayOutputStream();transfer.file(res.url,out,24L*1024*1024)
                val bytes=out.toByteArray();val bounds=BitmapFactory.Options().apply{inJustDecodeBounds=true};BitmapFactory.decodeByteArray(bytes,0,bytes.size,bounds)
                require(bounds.outWidth in 1..20000 && bounds.outHeight in 1..20000){"Неверный JPEG камеры"}
                var sample=1;while(maxOf(bounds.outWidth,bounds.outHeight)/sample>2048)sample*=2
                val bitmap=BitmapFactory.decodeByteArray(bytes,0,bytes.size,BitmapFactory.Options().apply{inSampleSize=sample}) ?: error("Не удалось открыть JPEG")
                main.post{if(epoch.get()==token && sessionEpoch()==session && active()){preview=bitmap;if(res==item.original)cachedOriginal=res.url to bytes;message="${item.name} · ${bounds.outWidth}×${bounds.outHeight}"}}
            }catch(t:Throwable){main.post{if(epoch.get()==token)message="Просмотр: ${t.message}"}}
            finally{connection.compareAndSet(owned,null);owned?.close();main.post{if(epoch.get()==token)transferBusy=false}}
        }
    }
    fun download(item:WifiCardFile){
        if(Build.VERSION.SDK_INT<29){message="Сохранение доступно с Android 10";return}
        if(transferBusy || !active())return
        val res=item.original ?: run{message="Камера не предложила оригинал этого файла";return}
        val cached=cachedOriginal?.takeIf{it.first==res.url}?.second
        val net=link() ?: return;val token=epoch.get();val session=sessionEpoch();transferBusy=true;downloadedUri=null;message="Сохраняю оригинал…"
        files.execute{var owned:WifiNetwork?=null;var destination:Uri?=null
            try{
                check(epoch.get()==token && sessionEpoch()==session && active()){"Передача отменена"}
                val transfer=if(cached==null)WifiNetwork(app,net.endpoint).also{owned=it;connection.set(it)} else null
                check(epoch.get()==token && sessionEpoch()==session && active()){"Передача отменена"}
                val name=item.name.replace(Regex("[^\\p{L}\\p{N}._ -]"),"_").take(120).ifBlank{"GH5-original"}
                destination=app.contentResolver.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI,ContentValues().apply{
                    put(MediaStore.MediaColumns.DISPLAY_NAME,name);put(MediaStore.MediaColumns.MIME_TYPE,res.mime.ifBlank{"application/octet-stream"})
                    put(MediaStore.MediaColumns.RELATIVE_PATH,"Download/GH5 Originals");put(MediaStore.MediaColumns.IS_PENDING,1)
                }) ?: error("Не удалось создать файл")
                var last=0L
                val progress:(Long)->Unit={bytes->
                    check(epoch.get()==token && sessionEpoch()==session && active()){"Передача отменена"}
                    val now=SystemClock.elapsedRealtime();if(now-last>=250){last=now;main.post{if(epoch.get()==token)message="Сохранено ${bytes/1024} КБ"}}
                }
                val count=app.contentResolver.openOutputStream(destination!!).use{out->requireNotNull(out)
                    if(cached!=null){var at=0;while(at<cached.size){val n=minOf(32768,cached.size-at);progress((at+n).toLong());out.write(cached,at,n);at+=n};cached.size.toLong()}
                    else requireNotNull(transfer).file(res.url,out,1_073_741_824L,progress)
                }
                // GH5 DIDL sizes are estimates (e.g. every original JPEG is 5,000,000).
                // WifiNetwork validates the actual HTTP body framing and its length.
                require(count>0){"Камера вернула пустой файл"}
                check(epoch.get()==token && sessionEpoch()==session){"Передача отменена"}
                app.contentResolver.update(destination!!,ContentValues().apply{put(MediaStore.MediaColumns.IS_PENDING,0)},null,null)
                val uri=destination;main.post{if(epoch.get()==token){downloadedUri=uri;message="Оригинал сохранён: Download/GH5 Originals/$name"}}
            }catch(t:Throwable){destination?.let{runCatching{app.contentResolver.delete(it,null,null)}};main.post{if(epoch.get()==token)message="Сохранение: ${t.message}"}}
            finally{connection.compareAndSet(owned,null);owned?.close();main.post{if(epoch.get()==token)transferBusy=false}}
        }
    }
    fun back():Boolean {
        if(transferBusy || loading)return true
        if(selected!=null){selected=null;preview=null;cachedOriginal=null;return true}
        if(parents.isNotEmpty()){browse(parents.removeLast());return true}
        leave();return true
    }
    fun notice(text:String){message=text}
    fun cancelTransfer(){epoch.incrementAndGet();connection.getAndSet(null)?.close();transferBusy=false;loading=false;message="Передача отменена"}
    fun clear(){epoch.incrementAndGet();connection.getAndSet(null)?.close();items=emptyList();preview=null;cachedOriginal=null;selected=null;parents.clear();loading=false;transferBusy=false;currentId="0";start=0;total=0;returned=0;downloadedUri=null}
    fun close(){clear();files.shutdown()}
}
