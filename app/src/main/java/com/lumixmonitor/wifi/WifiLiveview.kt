package com.lumixmonitor.wifi

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import com.lumixmonitor.lumix.LatestPresentation
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.InetAddress
import java.net.SocketTimeoutException
import java.util.Locale
import java.util.concurrent.atomic.AtomicLong

/** UDP reception never waits for decoding or UI. Both hand-offs have capacity one. */
class WifiLiveview(private val socket:DatagramSocket,private val cameraAddress:InetAddress,
    private val onFrame:(Bitmap,ByteArray)->Unit,private val onStats:(String)->Unit,
    private val onFailure:(Throwable)->Unit) {
    private data class Packet(val jpeg:ByteArray,val receivedAt:Long)
    private data class Decoded(val bitmap:Bitmap,val packet:Packet,val decodeMs:Double)
    private val main=Handler(Looper.getMainLooper())
    private val presentation=LatestPresentation<Decoded>()
    private val lock=Object();private var latest:Packet?=null
    @Volatile private var running=false
    private var closed=false
    @Volatile var active=true
    @Volatile var lastFrameAt:Long=0;private set
    val received=AtomicLong();val invalid=AtomicLong();val superseded=AtomicLong()
    private val complete=AtomicLong();private val byteCount=AtomicLong();private val gapMax=AtomicLong()
    private var lastDatagramAt=0L
    private var countedFrames=0L;private var countedBytes=0L
    private val receiver=Thread({receive()},"wifi-udp")
    private val decoder=Thread({decode()},"wifi-jpeg")
    val port:Int get()=socket.localPort
    private var shown=0;private var statsAt=0L;private var decodeTotal=0.0;private var maxAppAge=0L
    private val present=Runnable{
        val item=presentation.take()
        if(item!=null && running && active){
            val age=SystemClock.elapsedRealtime()-item.packet.receivedAt
            // Do not show a backlog if Android stalled or this session was backgrounded.
            if(age<=250){
                onFrame(item.bitmap,item.packet.jpeg);shown++;decodeTotal+=item.decodeMs;maxAppAge=maxOf(maxAppAge,age)
                val now=SystemClock.elapsedRealtime()
                if(now-statsAt>=2000){
                    val frames=complete.get();val bytes=byteCount.get();val elapsed=now-statsAt
                    val frameDelta=frames-countedFrames;val byteDelta=bytes-countedBytes
                    onStats(String.format(Locale.US,"%.1f FPS · Wi-Fi %d×%d · RX %.1f fps / %.2f Mb/s · %.1f KB/frame · UDP gap ≤%d ms · JPEG %.1f ms · app age ≤%d ms · drop %d / bad %d",
                        shown*1000.0/elapsed,item.bitmap.width,item.bitmap.height,frameDelta*1000.0/elapsed,byteDelta*.008/elapsed,
                        if(frameDelta>0)byteDelta/1024.0/frameDelta else 0.0,gapMax.getAndSet(0),decodeTotal/shown,maxAppAge,superseded.get(),invalid.get()))
                    countedFrames=frames;countedBytes=bytes
                    statsAt=now;shown=0;decodeTotal=0.0;maxAppAge=0
                }
            }
        }
    }
    @Synchronized fun start(){check(!closed && !running){"Preview session already started or closed"};running=true;statsAt=SystemClock.elapsedRealtime();socket.soTimeout=250;receiver.start();decoder.start()}
    private fun receive(){
        val bytes=ByteArray(65535)
        try{while(running){
            val packet=DatagramPacket(bytes,bytes.size)
            try{socket.receive(packet)}catch(_:SocketTimeoutException){continue}
            if(packet.address!=cameraAddress)continue
            val receivedAt=SystemClock.elapsedRealtime()
            if(lastDatagramAt!=0L){val gap=receivedAt-lastDatagramAt;gapMax.updateAndGet{maxOf(it,gap)}}
            lastDatagramAt=receivedAt
            received.incrementAndGet()
            val jpeg=WifiJpeg.extract(packet.data,packet.length)
            if(jpeg==null){invalid.incrementAndGet();continue}
            complete.incrementAndGet();byteCount.addAndGet(packet.length.toLong())
            val now=receivedAt;lastFrameAt=now
            synchronized(lock){if(latest!=null)superseded.incrementAndGet();latest=Packet(jpeg,now);lock.notifyAll()}
        }}catch(t:Throwable){if(running)main.post{if(running)onFailure(t)}}
    }
    private fun decode(){
        try{while(running){
            val packet=synchronized(lock){while(running && latest==null)lock.wait();latest.also{latest=null}} ?: continue
            if(!active || SystemClock.elapsedRealtime()-packet.receivedAt>150)continue
            val begin=SystemClock.elapsedRealtimeNanos()
            val bitmap=BitmapFactory.decodeByteArray(packet.jpeg,0,packet.jpeg.size)
            if(bitmap==null){invalid.incrementAndGet();continue}
            val decoded=Decoded(bitmap,packet,(SystemClock.elapsedRealtimeNanos()-begin)/1_000_000.0)
            if(running && presentation.offer(decoded))main.post(present)
        }}catch(_:InterruptedException){}catch(t:Throwable){if(running)main.post{if(running)onFailure(t)}}
    }
    @Synchronized fun stop(){closed=true;running=false;socket.close();synchronized(lock){latest=null;lock.notifyAll()};decoder.interrupt();presentation.clear();main.removeCallbacks(present)}
}
