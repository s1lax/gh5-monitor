package com.lumixmonitor

import android.app.Application
import android.hardware.usb.UsbDevice
import com.lumixmonitor.lumix.LumixCamera
import com.lumixmonitor.lumix.LumixProp
import com.lumixmonitor.ptp.PtpLog
import org.json.JSONObject
import java.io.File

/** Optional one-shot rollback for local hardware tests interrupted by a USB detach.
 * No file ships with the app. Only an exact USB serial match may apply the two
 * explicitly recorded settings; there are no capture, recording or arbitrary opcodes.
 */
object DebugDeviceRestore {
    @JvmStatic fun restore(app:Application,camera:LumixCamera,device:UsbDevice){
        val file=File(app.filesDir,"debug-device-restore.json")
        if(!file.exists() || file.length()>2048)return
        val job=JSONObject(file.readText())
        if(job.optLong("expiresAt")<System.currentTimeMillis()){file.delete();return}
        val serial=job.optString("serial")
        if(serial.isBlank() || serial!=device.serialNumber)return
        val iso=job.getInt("iso");val wb=job.getInt("wb")
        val allowedIso=camera.getDevicePropDesc(LumixProp.ISO,4)?.allowed ?: intArrayOf()
        val allowedWb=camera.getDevicePropDesc(LumixProp.WHITE_BALANCE,2)?.allowed ?: intArrayOf()
        require(iso in allowedIso && wb in allowedWb){"Rollback values not advertised by this camera"}
        check(camera.setIso(iso) && camera.getDevicePropDesc(LumixProp.ISO,4)?.current==iso){"ISO rollback not confirmed"}
        check(camera.setWhiteBalance(wb) && camera.getDevicePropDesc(LumixProp.WHITE_BALANCE,2)?.current==wb){"WB rollback not confirmed"}
        check(file.delete()){"Rollback applied but job file could not be removed"}
        PtpLog.add("Debug device restore: original ISO and WB selection restored; job removed")
    }
}
