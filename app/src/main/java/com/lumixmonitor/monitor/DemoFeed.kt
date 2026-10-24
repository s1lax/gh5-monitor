package com.lumixmonitor.monitor

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.Shader
import android.os.SystemClock
import kotlin.math.sin

/** Local test signal. It never sends commands to a USB device. */
class DemoFeed(private val sourceImage:ByteArray?=null, private val onFrame: (Bitmap, Float, Float, Float) -> Unit) {
    @Volatile private var running = true
    @Volatile var active = true
    private val thread = Thread({
        val paint = Paint(Paint.ANTI_ALIAS_FLAG)
        val started = SystemClock.elapsedRealtime()
        var count = 0
        var windowStart = started
        var fps = 0f
        while (running) {
            if(!active){try{Thread.sleep(100)}catch(_:InterruptedException){break};continue}
            val tick = SystemClock.elapsedRealtime()
            val t = (tick-started)/1000f
            val bitmap = if(sourceImage!=null)BitmapFactory.decodeByteArray(sourceImage,0,sourceImage.size) ?: break
                else Bitmap.createBitmap(1280,720,Bitmap.Config.ARGB_8888)
            if(sourceImage==null){
            val canvas = Canvas(bitmap)
            paint.shader = LinearGradient(0f,0f,1280f,720f,
                intArrayOf(Color.rgb(22,34,47),Color.rgb(125,152,151),Color.rgb(242,224,174)),null,Shader.TileMode.CLAMP)
            canvas.drawRect(0f,0f,1280f,720f,paint)
            paint.shader = null
            paint.color = Color.rgb(40,49,64);canvas.drawRect(340f,150f,860f,438f,paint)
            paint.color = Color.rgb(18,26,38);canvas.drawRect(355f,165f,845f,418f,paint)
            paint.color = Color.rgb(97,111,120);canvas.drawRect(580f,438f,620f,510f,paint)
            for (row in 0..3) for (col in 0..17) {
                paint.color = if ((row+col)%4 == 0) Color.rgb(224,179,170) else Color.rgb(199,216,198)
                canvas.drawRoundRect(160f+col*46,525f+row*28,200f+col*46,548f+row*28,3f,3f,paint)
            }
            paint.color = Color.rgb(246,243,219)
            canvas.drawCircle(1050f+sin(t)*50f,410f,58f,paint)
            for(i in 0..31) {
                val gray = i*255/31
                paint.color=Color.rgb(gray,gray,gray)
                canvas.drawRect(i*40f,0f,(i+1)*40f,64f,paint)
            }
            val colors=intArrayOf(Color.WHITE,Color.YELLOW,Color.CYAN,Color.GREEN,Color.MAGENTA,Color.RED,Color.BLUE,Color.BLACK)
            for(i in colors.indices){paint.color=colors[i];canvas.drawRect(i*160f,658f,(i+1)*160f,720f,paint)}
            paint.color=Color.WHITE; paint.textSize=26f
            canvas.drawText("DEMO / 1280 x 720 / USB НЕ ИСПОЛЬЗУЕТСЯ",36f,110f,paint)
            paint.textSize=24f
            canvas.drawText("Контраст / градации / движение",370f,280f,paint)
            paint.color=Color.rgb(145,185,171)
            canvas.drawRect(380f,325f,820f,328f,paint)
            }
            count++
            if(tick-windowStart>=1000){fps=count*1000f/(tick-windowStart);count=0;windowStart=tick}
            if (running) onFrame(bitmap,sin(t*0.4f)*4f,sin(t*0.25f)*2f,fps)
            try { Thread.sleep((33-(SystemClock.elapsedRealtime()-tick)).coerceAtLeast(1)) }
            catch (_: InterruptedException) { break }
        }
    },"monitor-demo").apply { start() }
    fun close() { running=false;thread.interrupt() }
}
