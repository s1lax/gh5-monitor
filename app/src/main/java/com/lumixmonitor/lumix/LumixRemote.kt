package com.lumixmonitor.lumix

import com.lumixmonitor.ptp.PtpLog
import com.lumixmonitor.ptp.PtpResponseCode
import com.lumixmonitor.ptp.PtpUsbTransport
import java.io.OutputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder

/** Extended, bounded operations; shares liveview's fair transaction lock. */
class LumixRemote(private val transport: PtpUsbTransport) {
    private val soft = intArrayOf(0x2001,0x2002,0x2005,0x2006,0x200a,0x200f,0x2019,0x201d)
    private fun read(op:Int,params:IntArray,maxBytes:Int=64*1024):ByteArray? {
        val r=transport.receiveData(op,params,soft,maxBytes,1500,1)
        return if(r.resCode==0x2001)r.data else null
    }
    /** One-shot observational reads only. No settings writes, shutter or REC commands. */
    fun previewDiagnosis():List<PreviewStatePacket> {
        val queries=listOf(0x02000010,0x02000020,0x02000030,0x02000040,0x02000050,0x02000060,0x02000080,0x02000170)
            .map{0x9402 to it}+listOf(0x9408 to 0x06000010,0x9414 to 0x12000020,0x9414 to 0x12000010)
        return queries.map{(op,code)->
            val response=transport.receiveData(op,intArrayOf(code),soft,64*1024,1500,1)
            PreviewStatePacket(op,code,response.resCode,response.data)
        }
    }
    fun capabilities(model:String?=null):Map<Int,CameraProperty> = Gh5Compatibility.settings(model,buildMap {
        RemoteSetting.groups.forEach { code -> read(0x9108,intArrayOf(code))?.let { putAll(CameraPropertyParser.parse(it)) } }
    })
    fun whiteBalanceCapabilities(model:String?):Map<Int,CameraProperty> = Gh5Compatibility.settings(model,
        read(0x9108,intArrayOf(LumixProp.WHITE_BALANCE))?.let(CameraPropertyParser::parse).orEmpty())
    fun telemetry(battery:Boolean,lens:Boolean):CameraTelemetry {
        fun get(op:Int,code:Int)=read(op,intArrayOf(code)) ?: byteArrayOf()
        val t=CameraTelemetryParser.parse(get(0x9414,0x12000010),get(0x9414,0x12000020),
            if(battery)get(0x9414,0x16000010) else byteArrayOf(),get(0x9401,0x01000010),
            if(lens)get(0x9414,0x120100a0) else byteArrayOf())
        if(t.recording==true && t.elapsedSeconds==null){
            val separate=PanasonicData.blocks(get(0x9414,0x12000014))[0x12000014]
            return t.copy(elapsedSeconds=separate?.let{PanasonicData.u32(it,0)}?.takeIf{it<0xfffffffdL})
        }
        return t
    }
    fun setProperty(p:CameraProperty,value:Int):Boolean {
        require(RemoteSetting.all.any{it.code==p.code} && value in p.choices)
        val payload=ByteBuffer.allocate(8+p.valueSize).order(ByteOrder.LITTLE_ENDIAN).apply {
            putInt(p.code);putInt(p.valueSize)
            when(p.valueSize){1->put(value.toByte());2->putShort(value.toShort());4->putInt(value);else->error("Invalid property size")}
        }.array()
        val r=transport.sendData(RemoteSetting.writeOpcode(p.code),intArrayOf(p.code),payload,soft)
        PtpLog.add("Настройка 0x%08X=%d: %s".format(p.code,value,PtpResponseCode.name(r.resCode)))
        return r.isOk
    }
    fun whiteBalanceMeasure(code:Int):Boolean {
        val payload=UsbWhiteBalance.payload(code)
        val r=transport.sendData(0x9416,intArrayOf(code),payload,soft)
        PtpLog.add("WB measure 0x%08X: %s".format(code,PtpResponseCode.name(r.resCode)))
        return UsbWhiteBalance.accepted(code,r.resCode)
    }
    private fun control(code:Int,values:IntArray):Boolean {
        require(code in setOf(0x03000026,0x03000052,0x03000053,0x03010011))
        val payload=ByteBuffer.allocate(8+values.size*2).order(ByteOrder.LITTLE_ENDIAN).apply {
            putInt(code);putInt(values.size*2);values.forEach{require(it in 0..65535);putShort(it.toShort())}
        }.array()
        val r=transport.sendData(0x9416,intArrayOf(code),payload,soft)
        PtpLog.add("Фокус 0x%08X ${values.joinToString()}: ${PtpResponseCode.name(r.resCode)}".format(code))
        return r.isOk
    }
    fun focusPoint(x:Int,y:Int):Boolean {require(x in 0..1000 && y in 0..1000);return control(0x03000026,intArrayOf(x,y,0))}
    fun moveAssist(x:Int,y:Int):Boolean {require(x in 0..1000 && y in 0..1000);return control(0x03000053,intArrayOf(x,y))}
    // Ratio is expressed in hundredths: 10 was only 0.1x despite the old UI label.
    fun assistStep(increase:Boolean)=control(0x03000052,intArrayOf(if(increase)1 else 0,0,100))
    fun stopManualFocus()=control(0x03010011,intArrayOf(0))
    fun aeLock(enabled:Boolean)=transport.sendCommand(0x9405,intArrayOf(if(enabled)0x03000021 else 0x03000025),soft).isOk

    fun imageObjects():List<CameraObject> {
        val total=transport.sendCommand(0x1006,intArrayOf(-1,0,0),soft,1500,1)
        check(total.isOk){"GH5 не разрешила получить список файлов"}
        val announced=total.parameters.firstOrNull()?.toLong()?.and(0xffffffffL) ?: error("GH5 не сообщила число файлов")
        check(announced<=60000){"Слишком много файлов для общего списка: $announced"}
        val raw=read(0x1007,intArrayOf(-1,0,0),256*1024) ?: return emptyList()
        val count=PanasonicData.u32(raw,0) ?: return emptyList()
        if(count>(raw.size-4L)/4 || count>65535)return emptyList()
        return (0 until count.toInt()).reversed().take(40).mapNotNull { i ->
            val handle=PanasonicData.u32(raw,4+i*4)!!.toInt()
            read(0x1008,intArrayOf(handle),4096)?.let{CameraObjectParser.parse(handle,it)}
        }.filter{it.isStill}.take(16)
    }
    fun download(item:CameraObject,out:OutputStream,cancelled:()->Boolean,progress:(Long)->Unit) {
        require(item.isStill && item.bytes in 1..512L*1024*1024)
        var offset=0L
        while(offset<item.bytes){
            check(!cancelled()){ "Загрузка отменена" }
            val requested=minOf(256*1024L,item.bytes-offset).toInt()
            val chunk=read(0x101b,intArrayOf(item.handle,offset.toInt(),requested),requested+16)
                ?: error("GH5 отклонила чтение оригинала")
            check(chunk.isNotEmpty() && chunk.size<=requested){"Неверная длина части файла"}
            out.write(chunk);offset+=chunk.size;progress(offset)
        }
        check(offset==item.bytes){"Оригинал получен не полностью"}
    }
}

data class PreviewStatePacket(val opcode:Int,val parameter:Int,val response:Int,val data:ByteArray)

data class CameraObject(val handle:Int,val name:String,val bytes:Long,val width:Int,val height:Int) {
    val isStill:Boolean get()=listOf(".jpg",".jpeg",".rw2").any{name.endsWith(it,true)}
    val mime:String get()=if(name.endsWith(".rw2",true))"image/x-panasonic-rw2" else "image/jpeg"
}
object CameraObjectParser {
    fun parse(handle:Int,b:ByteArray):CameraObject? {
        if(b.size<53)return null
        val count=b[52].toInt() and 255
        if(count !in 1..255 || count*2>b.size-53)return null
        val name=String(b,53,(count-1)*2,Charsets.UTF_16LE)
        if(name.isBlank() || name.any{it=='/' || it=='\\' || it.code<32} || name=="..")return null
        return CameraObject(handle,name,PanasonicData.u32(b,8) ?: return null,
            PanasonicData.u32(b,26)?.toInt() ?: 0,PanasonicData.u32(b,30)?.toInt() ?: 0)
    }
}
