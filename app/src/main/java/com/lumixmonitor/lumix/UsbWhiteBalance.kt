package com.lumixmonitor.lumix

import java.nio.ByteBuffer
import java.nio.ByteOrder

/** Native white-set controls. Sampling uses two UINT32 coordinates, unlike MF Assist. */
object UsbWhiteBalance {
    const val START=0x03000091
    const val SAMPLE=0x03000092
    const val END=0x03000093
    fun isKelvin(mode:Int?)=mode!=null && mode in 0x8010..0x8013
    fun isCustom(mode:Int?)=mode!=null && mode in 0x800b..0x800e
    fun family(mode:Int?)=when{isKelvin(mode)->"Кельвины";isCustom(mode)->"Замер";else->"Пресеты"}
    fun slot(mode:Int)=mode-0x800a
    /** Match Tether's select -> START -> SAMPLE path; END is cancellation/error cleanup. */
    fun captureSelected(mode:Int,select:(Int)->Boolean,current:()->Int?,control:(Int)->Boolean,settle:()->Unit={}){
        require(isCustom(mode)){"Выберите WB1–WB4"}
        check(select(mode)){"GH5 отклонила выбор WB${slot(mode)}"}
        check(current()==mode){"GH5 не подтвердила WB${slot(mode)} — замер не отправлен"}
        try{
            check(control(START)){"GH5 отклонила вход в замер"}
            check(control(SAMPLE)){"GH5 отклонила замер"}
        }catch(t:Throwable){runCatching{control(END)};throw t}
        settle()
    }
    fun temperature(mode:Int?,reportedMode:Int?,kelvin:Int?):Int?=
        kelvin?.takeIf{isKelvin(mode) && mode==reportedMode && it in 1000..100000}
    fun accepted(code:Int,result:Int)=result==0x2001 || (code==END && result==0x201d)
    fun isFailureEvent(code:Int,parameter:Int)=code==0xc212 && parameter==0x12800042
    fun encodeShift(value:Int):Int {require(value in -9..9);return if(value<0)0x8000 or -value else value}
    fun payload(code:Int,x:Int=500,y:Int=500):ByteArray {
        require(code in listOf(START,SAMPLE,END))
        require(x in 0..1000 && y in 0..1000)
        val size=if(code==SAMPLE)8 else 0
        return ByteBuffer.allocate(8+size).order(ByteOrder.LITTLE_ENDIAN).apply{
            putInt(code);putInt(size);if(size!=0){putInt(x);putInt(y)}
        }.array()
    }
}
