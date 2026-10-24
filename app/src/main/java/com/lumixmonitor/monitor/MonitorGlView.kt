package com.lumixmonitor.monitor

import android.content.Context
import android.graphics.Bitmap
import android.opengl.GLES20.*
import android.opengl.GLSurfaceView
import android.opengl.GLUtils
import com.lumixmonitor.MonitorTools
import com.lumixmonitor.ptp.PtpLog
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.FloatBuffer
import javax.microedition.khronos.egl.EGLConfig
import javax.microedition.khronos.opengles.GL10

data class ViewTransform(val zoom: Float = 1f, val panX: Float = 0f, val panY: Float = 0f)

class MonitorGlView(context: Context) : GLSurfaceView(context) {
    private val monitor = MonitorRenderer()
    init {
        setEGLContextClientVersion(2)
        preserveEGLContextOnPause = true
        setRenderer(monitor)
        renderMode = RENDERMODE_WHEN_DIRTY
    }
    fun display(bitmap: Bitmap?, tools: MonitorTools, transform: ViewTransform, lut: CubeLut?, calibration: DisplayCalibration = DisplayCalibration(), quarterTurns:Int=0) {
        monitor.input = MonitorRenderer.Input(bitmap,tools,transform,lut,calibration,quarterTurns)
        requestRender()
    }
}

private class MonitorRenderer : GLSurfaceView.Renderer {
    data class Input(val bitmap: Bitmap?, val tools: MonitorTools, val transform: ViewTransform, val lut: CubeLut?, val calibration: DisplayCalibration,val quarterTurns:Int)
    @Volatile var input = Input(null,MonitorTools(),ViewTransform(),null,DisplayCalibration(),0)
    private var program = 0
    private var textures = IntArray(2)
    private var lastBitmap: Bitmap? = null
    private var lastLut: CubeLut? = null
    private var textureWidth = 0
    private var textureHeight = 0
    private val uniforms = mutableMapOf<String,Int>()
    private val vertices: FloatBuffer = ByteBuffer.allocateDirect(16*4).order(ByteOrder.nativeOrder()).asFloatBuffer().apply {
        put(floatArrayOf(-1f,-1f,0f,1f, 1f,-1f,1f,1f, -1f,1f,0f,0f, 1f,1f,1f,0f));position(0)
    }
    private var measureStart = System.nanoTime()
    private var uploaded = 0
    private var drawNs = 0L
    private var lastUploadNs=0L
    private var maxGapNs=0L

    override fun onSurfaceCreated(gl: GL10?, config: EGLConfig?) {
        program = glCreateProgram()
        fun compile(type: Int, source: String): Int {
            val shader=glCreateShader(type);glShaderSource(shader,source);glCompileShader(shader)
            val ok=IntArray(1);glGetShaderiv(shader,GL_COMPILE_STATUS,ok,0)
            check(ok[0] != 0) { glGetShaderInfoLog(shader) }
            return shader
        }
        val vs=compile(GL_VERTEX_SHADER,VERTEX);val fs=compile(GL_FRAGMENT_SHADER,FRAGMENT)
        glAttachShader(program,vs);glAttachShader(program,fs);glLinkProgram(program)
        val ok=IntArray(1);glGetProgramiv(program,GL_LINK_STATUS,ok,0);check(ok[0]!=0){glGetProgramInfoLog(program)}
        glDeleteShader(vs);glDeleteShader(fs)
        uniforms.clear();glGenTextures(2,textures,0)
        for(texture in textures){glBindTexture(GL_TEXTURE_2D,texture);glTexParameteri(GL_TEXTURE_2D,GL_TEXTURE_MIN_FILTER,GL_LINEAR);glTexParameteri(GL_TEXTURE_2D,GL_TEXTURE_MAG_FILTER,GL_LINEAR);glTexParameteri(GL_TEXTURE_2D,GL_TEXTURE_WRAP_S,GL_CLAMP_TO_EDGE);glTexParameteri(GL_TEXTURE_2D,GL_TEXTURE_WRAP_T,GL_CLAMP_TO_EDGE)}
        glTexImage2D(GL_TEXTURE_2D,0,GL_RGBA,1,1,0,GL_RGBA,GL_UNSIGNED_BYTE,ByteBuffer.allocateDirect(4))
        lastBitmap=null;lastLut=null;textureWidth=0;textureHeight=0
        PtpLog.add("GPU монитор: ${glGetString(GL_RENDERER)}, ${glGetString(GL_VERSION)}")
    }
    override fun onSurfaceChanged(gl: GL10?,width:Int,height:Int){glViewport(0,0,width,height)}
    private fun u(name:String)=uniforms.getOrPut(name){glGetUniformLocation(program,name)}
    override fun onDrawFrame(gl: GL10?) {
        val state=input
        val bitmap=state.bitmap ?: return
        val start=System.nanoTime()
        glUseProgram(program)
        glActiveTexture(GL_TEXTURE0);glBindTexture(GL_TEXTURE_2D,textures[0])
        if(lastBitmap !== bitmap) {
            if(lastUploadNs!=0L)maxGapNs=maxOf(maxGapNs,start-lastUploadNs)
            lastUploadNs=start
            if(textureWidth != bitmap.width || textureHeight != bitmap.height){
                GLUtils.texImage2D(GL_TEXTURE_2D,0,bitmap,0);textureWidth=bitmap.width;textureHeight=bitmap.height
            } else GLUtils.texSubImage2D(GL_TEXTURE_2D,0,0,0,bitmap)
            lastBitmap=bitmap;uploaded++
        }
        glUniform1i(u("image"),0)
        val lut=state.lut
        if(lut != null && lastLut !== lut) {
            val max=IntArray(1);glGetIntegerv(GL_MAX_TEXTURE_SIZE,max,0)
            if(lut.size*lut.size <= max[0]) {
                val atlas=lut.atlas();val bytes=ByteBuffer.allocateDirect(atlas.size).put(atlas).apply{position(0)}
                glActiveTexture(GL_TEXTURE1);glBindTexture(GL_TEXTURE_2D,textures[1])
                glTexImage2D(GL_TEXTURE_2D,0,GL_RGBA,lut.size*lut.size,lut.size,0,GL_RGBA,GL_UNSIGNED_BYTE,bytes)
                lastLut=lut
            }
        }
        glActiveTexture(GL_TEXTURE1);glBindTexture(GL_TEXTURE_2D,textures[1]);glUniform1i(u("look"),1)
        glUniform1f(u("lutSize"),(lut?.size ?: 2).toFloat())
        glUniform1f(u("lutAmount"),if(state.tools.lutEnabled && lut != null && lastLut===lut)state.tools.lutAmount else 0f)
        glUniform3fv(u("domainMin"),1,lut?.domainMin ?: floatArrayOf(0f,0f,0f),0)
        glUniform3fv(u("domainMax"),1,lut?.domainMax ?: floatArrayOf(1f,1f,1f),0)
        glUniform1f(u("zoom"),state.transform.zoom)
        glUniform1i(u("quarterTurns"),state.quarterTurns)
        glUniform2f(u("pan"),state.transform.panX,state.transform.panY)
        glUniform2f(u("pixel"),1f/bitmap.width,1f/bitmap.height)
        glUniform1f(u("zebra"),if(state.tools.zebra)state.tools.zebraThreshold else 2f)
        glUniform1f(u("peaking"),if(state.tools.focusPeaking)state.tools.peakingThreshold else 100f)
        glUniform1i(u("falseColor"),if(state.tools.falseColor)1 else 0)
        val calibration=state.calibration
        glUniform1i(u("correctDisplay"),if(calibration.isAdjusted)1 else 0)
        glUniform3f(u("calibration"),calibration.ev,calibration.gamma,calibration.contrast)
        val position=glGetAttribLocation(program,"position");val uv=glGetAttribLocation(program,"texCoord")
        vertices.position(0);glVertexAttribPointer(position,2,GL_FLOAT,false,16,vertices);glEnableVertexAttribArray(position)
        vertices.position(2);glVertexAttribPointer(uv,2,GL_FLOAT,false,16,vertices);glEnableVertexAttribArray(uv)
        glDrawArrays(GL_TRIANGLE_STRIP,0,4)
        drawNs+=System.nanoTime()-start
        val elapsed=System.nanoTime()-measureStart
        if(elapsed>=2_000_000_000L){
            PtpLog.add(java.lang.String.format(java.util.Locale.US,"GPU fps=%.1f submit_avg_ms=%.2f gap_max_ms=%.1f",uploaded*1e9/elapsed,drawNs/1e6/uploaded.coerceAtLeast(1),maxGapNs/1e6))
            measureStart=System.nanoTime();uploaded=0;drawNs=0;maxGapNs=0
        }
    }
    companion object {
        private const val VERTEX="""
            attribute vec2 position; attribute vec2 texCoord; varying vec2 uv;
            void main(){uv=texCoord;gl_Position=vec4(position,0.0,1.0);}
        """
        private const val FRAGMENT="""
            precision highp float;
            varying vec2 uv; uniform sampler2D image; uniform sampler2D look;
            uniform vec2 pixel; uniform vec2 pan; uniform float zoom; uniform int quarterTurns;
            uniform float zebra; uniform float peaking; uniform int falseColor;
            uniform int correctDisplay; uniform vec3 calibration;
            uniform float lutSize; uniform float lutAmount; uniform vec3 domainMin; uniform vec3 domainMax;
            float y(vec3 c){return dot(c,vec3(0.2126,0.7152,0.0722));}
            vec3 sampleLook(vec3 c){
                c=clamp((c-domainMin)/(domainMax-domainMin),0.0,1.0);
                float b=c.b*(lutSize-1.0);float lo=floor(b);float hi=min(lo+1.0,lutSize-1.0);
                vec2 a=vec2((lo*lutSize+c.r*(lutSize-1.0)+0.5)/(lutSize*lutSize),(c.g*(lutSize-1.0)+0.5)/lutSize);
                vec2 d=vec2((hi*lutSize+c.r*(lutSize-1.0)+0.5)/(lutSize*lutSize),a.y);
                return mix(texture2D(look,a).rgb,texture2D(look,d).rgb,fract(b));
            }
            vec3 exposure(float v){
                if(v<0.03)return vec3(0.5,0.0,0.65);
                if(v<0.10)return vec3(0.1,0.2,0.9);
                if(v<0.40)return vec3(v);
                if(v<0.50)return vec3(0.2,0.7,0.3);
                if(v<0.60)return vec3(1.0,0.55,0.65);
                if(v<0.90)return vec3(v);
                if(v<0.95)return vec3(1.0,0.9,0.1);
                return vec3(1.0,0.1,0.0);
            }
            void main(){
                vec2 p=clamp((uv-0.5)/zoom+0.5+pan,vec2(0.0),vec2(1.0));
                if(quarterTurns==1)p=vec2(p.y,1.0-p.x);
                else if(quarterTurns==2)p=vec2(1.0-p.x,1.0-p.y);
                else if(quarterTurns==3)p=vec2(1.0-p.y,p.x);
                vec3 source=texture2D(image,p).rgb;float v=y(source);vec3 result=source;
                if(lutAmount>0.0)result=mix(source,sampleLook(source),lutAmount);
                if(correctDisplay==1){
                    result=pow(max(result,vec3(0.0)),vec3(2.2))*exp2(calibration.x);
                    result=pow(max(result,vec3(0.0)),vec3(1.0/2.2));
                    result=pow(clamp(result,0.0,1.0),vec3(1.0/calibration.y));
                    result=clamp((result-0.5)*calibration.z+0.5,0.0,1.0);
                }
                if(falseColor==1)result=exposure(v);
                if(peaking<10.0){
                    float gx=y(texture2D(image,p+vec2(pixel.x,0.0)).rgb)-y(texture2D(image,p-vec2(pixel.x,0.0)).rgb);
                    float gy=y(texture2D(image,p+vec2(0.0,pixel.y)).rgb)-y(texture2D(image,p-vec2(0.0,pixel.y)).rgb);
                    if(length(vec2(gx,gy))>peaking)result=vec3(1.0,0.25,0.1);
                }
                if(v>=zebra){float stripe=step(4.0,mod(gl_FragCoord.x+gl_FragCoord.y,8.0));result=mix(result,vec3(stripe),0.8);}
                gl_FragColor=vec4(result,1.0);
            }
        """
    }
}
