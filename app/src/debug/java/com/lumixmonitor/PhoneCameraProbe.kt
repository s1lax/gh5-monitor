package com.lumixmonitor

import android.Manifest
import android.app.Activity
import android.content.pm.PackageManager
import android.graphics.ImageFormat
import android.hardware.camera2.*
import android.media.ImageReader
import android.os.Handler
import android.os.HandlerThread
import com.lumixmonitor.ptp.PtpLog
import java.io.File
import java.util.concurrent.atomic.AtomicBoolean

/** A bounded debug-only snapshot. Keeps the USB monitor Activity in the foreground. */
object PhoneCameraProbe {
    private val busy=AtomicBoolean(false)
    @JvmStatic fun capture(activity:Activity) {
        if(!busy.compareAndSet(false,true))return
        if(activity.checkSelfPermission(Manifest.permission.CAMERA)!=PackageManager.PERMISSION_GRANTED){
            PtpLog.add("Phone camera probe: CAMERA permission missing");busy.set(false);return
        }
        val thread=HandlerThread("phone-camera-probe").apply{start()}
        val handler=Handler(thread.looper)
        var device:CameraDevice?=null
        var session:CameraCaptureSession?=null
        var preview:ImageReader?=null
        var jpeg:ImageReader?=null
        val closed=AtomicBoolean(false)
        fun finish(message:String){
            if(!closed.compareAndSet(false,true))return
            PtpLog.add("Phone camera probe: $message")
            handler.removeCallbacksAndMessages(null)
            runCatching{session?.close()};runCatching{device?.close()}
            runCatching{preview?.close()};runCatching{jpeg?.close()}
            busy.set(false);thread.quitSafely()
        }
        handler.postDelayed({finish("timeout")},10000)
        handler.post {
            try {
                val manager=activity.getSystemService(CameraManager::class.java)
                val id=manager.cameraIdList.first{manager.getCameraCharacteristics(it).get(CameraCharacteristics.LENS_FACING)==CameraCharacteristics.LENS_FACING_BACK}
                val characteristics=manager.getCameraCharacteristics(id)
                val sizes=characteristics.get(CameraCharacteristics.SCALER_STREAM_CONFIGURATION_MAP)!!
                val size=sizes.getOutputSizes(ImageFormat.JPEG).filter{it.width.toLong()*it.height<=3_200_000}.maxByOrNull{it.width.toLong()*it.height}
                    ?: sizes.getOutputSizes(ImageFormat.JPEG).minBy{it.width.toLong()*it.height}
                val small=sizes.getOutputSizes(ImageFormat.YUV_420_888).minBy{ kotlin.math.abs(it.width-640)+kotlin.math.abs(it.height-480) }
                preview=ImageReader.newInstance(small.width,small.height,ImageFormat.YUV_420_888,2).apply{
                    setOnImageAvailableListener({reader->runCatching{reader.acquireLatestImage()?.close()}},handler)
                }
                jpeg=ImageReader.newInstance(size.width,size.height,ImageFormat.JPEG,2).apply{
                    setOnImageAvailableListener({reader->
                        try {
                            val image=reader.acquireLatestImage() ?: return@setOnImageAvailableListener
                            val bytes=image.use{im->im.planes[0].buffer.let{b->ByteArray(b.remaining()).also{b.get(it)}}}
                            val file=File(activity.filesDir,"phone-camera-probe.jpg")
                            file.writeBytes(bytes)
                            finish("saved ${size.width}x${size.height}, ${bytes.size} bytes")
                        }catch(t:Throwable){finish("image failed: ${t.message}")}
                    },handler)
                }
                val afModes=characteristics.get(CameraCharacteristics.CONTROL_AF_AVAILABLE_MODES) ?: intArrayOf()
                val af=if(CaptureRequest.CONTROL_AF_MODE_CONTINUOUS_PICTURE in afModes)CaptureRequest.CONTROL_AF_MODE_CONTINUOUS_PICTURE else CaptureRequest.CONTROL_AF_MODE_OFF
                manager.openCamera(id,object:CameraDevice.StateCallback(){
                    override fun onOpened(camera:CameraDevice){
                        if(closed.get()){camera.close();return}
                        device=camera
                        try {
                            camera.createCaptureSession(listOf(preview!!.surface,jpeg!!.surface),object:CameraCaptureSession.StateCallback(){
                                override fun onConfigured(configured:CameraCaptureSession){
                                    if(closed.get()){configured.close();return}
                                    session=configured
                                    try {
                                        configured.setRepeatingRequest(camera.createCaptureRequest(CameraDevice.TEMPLATE_PREVIEW).apply{
                                            addTarget(preview!!.surface);set(CaptureRequest.CONTROL_AF_MODE,af)
                                        }.build(),null,handler)
                                        handler.postDelayed({
                                            if(!closed.get())try{
                                                configured.capture(camera.createCaptureRequest(CameraDevice.TEMPLATE_STILL_CAPTURE).apply{
                                                    addTarget(jpeg!!.surface);set(CaptureRequest.CONTROL_AF_MODE,af)
                                                    set(CaptureRequest.JPEG_QUALITY,95.toByte())
                                                    set(CaptureRequest.JPEG_ORIENTATION,characteristics.get(CameraCharacteristics.SENSOR_ORIENTATION) ?: 0)
                                                }.build(),null,handler)
                                            }catch(t:Throwable){finish("capture failed: ${t.message}")}
                                        },1600)
                                    }catch(t:Throwable){finish("preview failed: ${t.message}")}
                                }
                                override fun onConfigureFailed(session:CameraCaptureSession){finish("session configuration failed")}
                            },handler)
                        }catch(t:Throwable){finish("session failed: ${t.message}")}
                    }
                    override fun onDisconnected(camera:CameraDevice){camera.close();finish("camera disconnected")}
                    override fun onError(camera:CameraDevice,error:Int){camera.close();finish("camera error $error")}
                },handler)
            }catch(t:Throwable){finish("open failed: ${t.message}")}
        }
    }
}
