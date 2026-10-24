package com.lumixmonitor

import android.app.Activity
import android.content.pm.ActivityInfo
import android.graphics.BitmapFactory
import android.graphics.Color
import android.os.Bundle
import android.view.Gravity
import android.view.View
import android.view.WindowManager
import android.widget.*
import com.lumixmonitor.monitor.MonitorGlView
import com.lumixmonitor.monitor.ViewTransform
import java.io.File
import android.os.Handler
import android.os.Looper
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

/** Offline display comparison, available only in debug builds. No USB commands. */
class DisplayProbeActivity:Activity(){
    private lateinit var root:LinearLayout
    private lateinit var images:LinearLayout
    private var fileName="display-reference.jpg"
    private var mode="split"
    @Volatile private var source:ByteArray?=null
    @Volatile private var gpuView:MonitorGlView?=null
    @Volatile private var resumed=false
    @Volatile private var animate=true
    private val main=Handler(Looper.getMainLooper())
    private val neutral=MonitorTools(showThirds=false,showLevel=false,showHistogram=false,showCenterMarker=false)
    private val feed=Executors.newSingleThreadScheduledExecutor()
    override fun onCreate(savedInstanceState:Bundle?){
        super.onCreate(savedInstanceState)
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        window.colorMode=ActivityInfo.COLOR_MODE_DEFAULT
        window.attributes=window.attributes.apply{screenBrightness=.49f}
        window.decorView.systemUiVisibility=5894 or 1024 or 512
        root=LinearLayout(this).apply{orientation=LinearLayout.VERTICAL;setBackgroundColor(Color.BLACK)}
        setContentView(root)
        val controls=LinearLayout(this)
        for((label,value) in listOf("GH5 JPEG" to "display-reference.jpg","VIDEO" to "footage-reference.png","Оба" to "split","Canvas" to "canvas","GPU" to "gpu")){
            controls.addView(Button(this).apply{text=label;setOnClickListener{if(value.contains('.'))fileName=value else mode=value;showImage()}},LinearLayout.LayoutParams(0,dp(45),1f))
        }
        root.addView(controls)
        controls.addView(Button(this).apply{text="30 FPS";setOnClickListener{animate=!animate;text=if(animate)"30 FPS" else "СТОП"}},LinearLayout.LayoutParams(0,dp(45),1f))
        images=LinearLayout(this)
        root.addView(images,LinearLayout.LayoutParams(-1,0,1f))
        showImage()
        feed.scheduleAtFixedRate({
            val data=source;val view=gpuView
            if(resumed && animate && data!=null && view!=null){
                val bitmap=BitmapFactory.decodeByteArray(data,0,data.size)
                if(bitmap!=null)main.post{if(resumed && gpuView===view)view.display(bitmap,neutral,ViewTransform(),null)}
            }
        },0,33,TimeUnit.MILLISECONDS)
    }
    override fun onResume(){super.onResume();resumed=true}
    override fun onPause(){resumed=false;super.onPause()}
    override fun onDestroy(){resumed=false;feed.shutdownNow();super.onDestroy()}
    private fun dp(v:Int)=(v*resources.displayMetrics.density).toInt()
    private fun showImage(){
        val data=File(filesDir,fileName).takeIf{it.exists()}?.readBytes() ?: return
        val image=BitmapFactory.decodeByteArray(data,0,data.size) ?: return
        source=data;gpuView=null
        File(filesDir,"probe-decoded.png").outputStream().use{image.compress(android.graphics.Bitmap.CompressFormat.PNG,100,it)}
        images.removeAllViews()
        for(gpu in if(mode=="split")listOf(false,true) else listOf(mode=="gpu")){
            val column=LinearLayout(this).apply{orientation=LinearLayout.VERTICAL}
            column.addView(TextView(this).apply{text=if(gpu)"GLES / SurfaceView" else "Android Canvas · исходный Bitmap";setTextColor(Color.WHITE);gravity=Gravity.CENTER},LinearLayout.LayoutParams(-1,dp(28)))
            val holder=FrameLayout(this)
            column.addView(holder,LinearLayout.LayoutParams(-1,0,1f))
            images.addView(column,LinearLayout.LayoutParams(0,-1,1f))
            if(!gpu)holder.addView(ImageView(this).apply{setImageBitmap(image);scaleType=ImageView.ScaleType.FIT_CENTER},FrameLayout.LayoutParams(-1,-1))
            else holder.post{
                val scale=minOf(holder.width.toFloat()/image.width,holder.height.toFloat()/image.height)
                val view=MonitorGlView(this)
                holder.addView(view,FrameLayout.LayoutParams((image.width*scale).toInt(),(image.height*scale).toInt(),Gravity.CENTER))
                view.display(image,neutral,ViewTransform(),null)
                gpuView=view
            }
        }
    }
}
