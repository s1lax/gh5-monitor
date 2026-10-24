package com.lumixmonitor.monitor

import android.content.ContentValues
import android.content.Context
import android.graphics.Bitmap
import android.media.MediaCodec
import android.media.MediaCodecInfo
import android.media.MediaFormat
import android.media.MediaMetadataRetriever
import android.media.MediaMuxer
import android.net.Uri
import android.opengl.EGL14
import android.opengl.EGLExt
import android.opengl.GLES20.*
import android.opengl.GLUtils
import android.os.Build
import android.os.ParcelFileDescriptor
import android.provider.MediaStore
import com.lumixmonitor.ptp.PtpLog
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder

/** Silent proxy of the original received frames. One pending frame; never blocks USB/UI. */
class PreviewRecorder(private val context:Context,private val width:Int,private val height:Int,
    private val demo:Boolean,private val onProgress:(String)->Unit,private val onFinished:(String)->Unit) {
    private data class Sample(val bitmap:Bitmap,val time:Long)
    private val lock=java.lang.Object()
    @Volatile private var stopping=false
    private var latest:Sample?=null
    init {Thread({record()},"monitor-proxy-encoder").start()}
    fun offer(bitmap:Bitmap,time:Long){synchronized(lock){if(!stopping){latest=Sample(bitmap,time);lock.notifyAll()}}}
    fun stop(){stopping=true;synchronized(lock){lock.notifyAll()}}

    private fun record(){
        var uri:Uri?=null
        var descriptor:ParcelFileDescriptor?=null
        var codec:MediaCodec?=null
        var muxer:MediaMuxer?=null
        var muxerStarted=false
        var encoderStarted=false
        var success=false
        var egl:EglEncoder?=null
        val name="GH5_${if(demo)"DEMO_" else ""}PREVIEW_${System.currentTimeMillis()}.mp4"
        try {
            if(Build.VERSION.SDK_INT>=29){
                val values=ContentValues().apply{put(MediaStore.Video.Media.DISPLAY_NAME,name);put(MediaStore.Video.Media.MIME_TYPE,"video/mp4");put(MediaStore.Video.Media.RELATIVE_PATH,"Movies/GH5 Monitor");put(MediaStore.Video.Media.IS_PENDING,1)}
                uri=context.contentResolver.insert(MediaStore.Video.Media.EXTERNAL_CONTENT_URI,values) ?: error("MP4 не создан")
                descriptor=context.contentResolver.openFileDescriptor(uri,"rw") ?: error("MP4 не открыт")
                muxer=MediaMuxer(descriptor.fileDescriptor,MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4)
            }else muxer=MediaMuxer(File(context.getExternalFilesDir("Movies"),name).absolutePath,MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4)
            val format=MediaFormat.createVideoFormat("video/avc",width,height).apply{
                setInteger(MediaFormat.KEY_COLOR_FORMAT,MediaCodecInfo.CodecCapabilities.COLOR_FormatSurface)
                setInteger(MediaFormat.KEY_BIT_RATE,6_000_000);setInteger(MediaFormat.KEY_FRAME_RATE,30);setInteger(MediaFormat.KEY_I_FRAME_INTERVAL,1)
            }
            val encoder=MediaCodec.createEncoderByType("video/avc");codec=encoder
            encoder.configure(format,null,null,MediaCodec.CONFIGURE_FLAG_ENCODE)
            val surface=encoder.createInputSurface()
            egl=EglEncoder(surface,width,height)
            encoder.start();encoderStarted=true
            val info=MediaCodec.BufferInfo()
            var track=-1
            var encoded=0
            fun drain(end:Boolean):Boolean {
                val deadline=System.nanoTime()+3_000_000_000L
                while(true){
                    val index=encoder.dequeueOutputBuffer(info,if(end)10_000 else 0)
                    if(index==MediaCodec.INFO_TRY_AGAIN_LATER){if(!end)return false;check(System.nanoTime()<deadline){"Таймаут завершения MP4"};continue}
                    if(index==MediaCodec.INFO_OUTPUT_FORMAT_CHANGED){check(!muxerStarted);track=muxer!!.addTrack(encoder.outputFormat);muxer!!.start();muxerStarted=true;continue}
                    if(index>=0){
                        val buffer=encoder.getOutputBuffer(index) ?: error("Буфер кодека недоступен")
                        if(info.flags and MediaCodec.BUFFER_FLAG_CODEC_CONFIG !=0)info.size=0
                        if(info.size>0){check(muxerStarted);buffer.position(info.offset);buffer.limit(info.offset+info.size);muxer!!.writeSampleData(track,buffer,info);encoded++}
                        val finished=info.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM !=0
                        encoder.releaseOutputBuffer(index,false)
                        if(finished)return true
                    }
                }
            }
            var first=0L
            var last=0L
            var progressAt=0L
            onProgress("Прокси: ${width}×${height} • без звука")
            while(true){
                val sample=synchronized(lock){while(latest==null && !stopping)lock.wait(150);latest.also{latest=null}}
                if(sample==null){if(stopping)break;drain(false);continue}
                if(first==0L)first=sample.time
                val timestamp=(sample.time-first).coerceAtLeast(last+1)
                egl.draw(sample.bitmap,timestamp)
                last=timestamp;drain(false)
                if(timestamp-progressAt>1_000_000_000L){progressAt=timestamp;onProgress("Прокси ${timestamp/1_000_000_000L}с • $encoded кадров • без звука")}
                if(stopping)break
            }
            encoder.signalEndOfInputStream();drain(true)
            check(muxerStarted && encoded>0){"Нет кадров для MP4"}
            muxer!!.stop();muxerStarted=false
            muxer!!.release();muxer=null
            descriptor?.close();descriptor=null
            uri?.let{context.contentResolver.update(it,ContentValues().apply{put(MediaStore.Video.Media.IS_PENDING,0)},null,null)}
            success=true
            var metadata=""
            if(uri!=null){
                val reader=MediaMetadataRetriever()
                try{
                    reader.setDataSource(context,uri);metadata="${reader.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_WIDTH)}x${reader.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_HEIGHT)}, ${reader.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)}ms"
                    val decoded=reader.getFrameAtTime(0,MediaMetadataRetriever.OPTION_CLOSEST_SYNC)
                    PtpLog.add("Прокси: проверка декодирования первого кадра = ${decoded!=null}")
                    decoded?.recycle()
                }finally{reader.release()}
            }
            PtpLog.add("Прокси MP4 сохранён: $name, $metadata, кадров=$encoded")
            onFinished("Прокси сохранён: $name ($metadata), без звука")
        }catch(t:Throwable){PtpLog.add("Прокси: ${t.message}");onFinished("Прокси: ${t.message}")}
        finally{
            egl?.close()
            if(encoderStarted)try{codec?.stop()}catch(_:Throwable){}
            codec?.release()
            if(muxerStarted)try{muxer?.stop()}catch(_:Throwable){}
            muxer?.release();descriptor?.close()
            if(!success)uri?.let{context.contentResolver.delete(it,null,null)}
        }
    }
}

private class EglEncoder(private val surface:android.view.Surface,private val width:Int,private val height:Int){
    private val display=EGL14.eglGetDisplay(EGL14.EGL_DEFAULT_DISPLAY)
    private val context:android.opengl.EGLContext
    private val target:android.opengl.EGLSurface
    private val program:Int
    private val texture:Int
    private val vertices=ByteBuffer.allocateDirect(64).order(ByteOrder.nativeOrder()).asFloatBuffer().apply{put(floatArrayOf(-1f,-1f,0f,1f,1f,-1f,1f,1f,-1f,1f,0f,0f,1f,1f,1f,0f));position(0)}
    private var sourceWidth=0
    private var sourceHeight=0
    init {
        check(EGL14.eglInitialize(display,IntArray(1),0,IntArray(1),0))
        val configs=arrayOfNulls<android.opengl.EGLConfig>(1)
        val attrs=intArrayOf(EGL14.EGL_RED_SIZE,8,EGL14.EGL_GREEN_SIZE,8,EGL14.EGL_BLUE_SIZE,8,EGL14.EGL_RENDERABLE_TYPE,EGL14.EGL_OPENGL_ES2_BIT,0x3142,1,EGL14.EGL_NONE)
        val count=IntArray(1);check(EGL14.eglChooseConfig(display,attrs,0,configs,0,1,count,0) && count[0]>0)
        context=EGL14.eglCreateContext(display,configs[0],EGL14.EGL_NO_CONTEXT,intArrayOf(EGL14.EGL_CONTEXT_CLIENT_VERSION,2,EGL14.EGL_NONE),0)
        check(context!=EGL14.EGL_NO_CONTEXT)
        target=EGL14.eglCreateWindowSurface(display,configs[0],surface,intArrayOf(EGL14.EGL_NONE),0)
        check(target!=EGL14.EGL_NO_SURFACE);check(EGL14.eglMakeCurrent(display,target,target,context))
        fun shader(type:Int,text:String):Int{val s=glCreateShader(type);glShaderSource(s,text);glCompileShader(s);val ok=IntArray(1);glGetShaderiv(s,GL_COMPILE_STATUS,ok,0);check(ok[0]!=0){glGetShaderInfoLog(s)};return s}
        val vs=shader(GL_VERTEX_SHADER,"attribute vec2 p;attribute vec2 t;varying vec2 uv;void main(){uv=t;gl_Position=vec4(p,0.,1.);}")
        val fs=shader(GL_FRAGMENT_SHADER,"precision mediump float;uniform sampler2D image;varying vec2 uv;void main(){gl_FragColor=texture2D(image,uv);}")
        program=glCreateProgram();glAttachShader(program,vs);glAttachShader(program,fs);glLinkProgram(program);glDeleteShader(vs);glDeleteShader(fs)
        val linked=IntArray(1);glGetProgramiv(program,GL_LINK_STATUS,linked,0);check(linked[0]!=0){glGetProgramInfoLog(program)}
        val textures=IntArray(1);glGenTextures(1,textures,0);texture=textures[0]
        glBindTexture(GL_TEXTURE_2D,texture);glTexParameteri(GL_TEXTURE_2D,GL_TEXTURE_MIN_FILTER,GL_LINEAR);glTexParameteri(GL_TEXTURE_2D,GL_TEXTURE_MAG_FILTER,GL_LINEAR);glTexParameteri(GL_TEXTURE_2D,GL_TEXTURE_WRAP_S,GL_CLAMP_TO_EDGE);glTexParameteri(GL_TEXTURE_2D,GL_TEXTURE_WRAP_T,GL_CLAMP_TO_EDGE)
    }
    fun draw(bitmap:Bitmap,time:Long){
        check(EGL14.eglMakeCurrent(display,target,target,context))
        glViewport(0,0,width,height);glClearColor(0f,0f,0f,1f);glClear(GL_COLOR_BUFFER_BIT)
        val scale=minOf(width.toFloat()/bitmap.width,height.toFloat()/bitmap.height)
        val w=(bitmap.width*scale).toInt();val h=(bitmap.height*scale).toInt()
        glViewport((width-w)/2,(height-h)/2,w,h);glUseProgram(program);glActiveTexture(GL_TEXTURE0);glBindTexture(GL_TEXTURE_2D,texture)
        if(sourceWidth!=bitmap.width || sourceHeight!=bitmap.height){GLUtils.texImage2D(GL_TEXTURE_2D,0,bitmap,0);sourceWidth=bitmap.width;sourceHeight=bitmap.height}else GLUtils.texSubImage2D(GL_TEXTURE_2D,0,0,0,bitmap)
        glUniform1i(glGetUniformLocation(program,"image"),0)
        val position=glGetAttribLocation(program,"p");val uv=glGetAttribLocation(program,"t")
        vertices.position(0);glVertexAttribPointer(position,2,GL_FLOAT,false,16,vertices);glEnableVertexAttribArray(position)
        vertices.position(2);glVertexAttribPointer(uv,2,GL_FLOAT,false,16,vertices);glEnableVertexAttribArray(uv)
        glDrawArrays(GL_TRIANGLE_STRIP,0,4);EGLExt.eglPresentationTimeANDROID(display,target,time);check(EGL14.eglSwapBuffers(display,target))
    }
    fun close(){
        EGL14.eglMakeCurrent(display,EGL14.EGL_NO_SURFACE,EGL14.EGL_NO_SURFACE,EGL14.EGL_NO_CONTEXT)
        EGL14.eglDestroySurface(display,target);EGL14.eglDestroyContext(display,context)
        EGL14.eglReleaseThread()
        // The process also owns GLSurfaceView's display: do not eglTerminate it here.
        surface.release()
    }
}
