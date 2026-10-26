package com.lumixmonitor.wifi

/** Preserve camera precision: a four-bar battery is not an exact percentage. */
data class WifiBattery(val current:Int,val maximum:Int,val percent:Boolean=false){
    val label:String get()=if(percent)"$current%" else "${"▮".repeat(current)}${"▯".repeat(maximum-current)}"
    companion object {
        fun parse(raw:String?,percentage:String?=null):WifiBattery? {
            percentage?.toIntOrNull()?.takeIf{it in 0..100}?.let{return WifiBattery(it,100,true)}
            val parts=raw?.split('/') ?: return null
            if(parts.size!=2)return null
            val a=parts[0].toIntOrNull() ?: return null;val b=parts[1].toIntOrNull() ?: return null
            return if(b in 1..8 && a in 0..b)WifiBattery(a,b) else null
        }
    }
}
data class WifiCardStatus(val slot:Int,val present:Boolean?,val full:Boolean?,val writing:Boolean?,val writable:Boolean?){
    val label:String get()="SD$slot: "+when{present==false->"нет карты";full==true->"заполнена";writing==true->"запись…";writable==false->"защищена";present==true->"готова";else->"—"}
}
data class WifiCameraStatus(val battery:WifiBattery?=null,val grip:WifiBattery?=null,val activeSlot:Int?=null,
    val cards:List<WifiCardStatus> = emptyList(),val temperature:String?=null,val warning:String?=null,
    val operation:String?=null,val recording:Boolean?=null,val remainingVideo:Long?=null,val remainingPhotos:Long?=null){
    companion object {
        fun from(s:Map<String,String>):WifiCameraStatus {
            fun flag(key:String)=when(s[key]){"on","true","set"->true;"off","false","unset"->false;else->null}
            fun number(key:String)=s[key]?.toLongOrNull()?.takeIf{it in 0..Int.MAX_VALUE.toLong()}
            val cards=(1..2).map{slot->val p=if(slot==1)"sd" else "sd2"
                WifiCardStatus(slot,flag(p+"_memory"),flag(p+"_full"),flag(p+"_access"),when(s[if(slot==1)"sdcardstatus" else "sd2_cardstatus"]){"write_enable"->true;"write_disable","write_protect"->false;else->null})}
            return WifiCameraStatus(WifiBattery.parse(s["batt"],s["batt_per"]),WifiBattery.parse(s["batt_grip"]),
                s["current_sd"]?.removePrefix("sd")?.toIntOrNull()?.takeIf{it in 1..2},cards,s["temperature"],
                s["warn_disp"]?.takeUnless{it in listOf("no_disp","off","none","")},s["operate"],
                when(s["rec"]){"on"->true;"off"->false;else->null},number("video_remaincapacity"),number("remaincapacity"))
        }
    }
}

data class WifiAssistRange(val minimum:Int=300,val maximum:Int=2000){
    fun clamp(value:Int)=value.coerceIn(minimum,maximum)
    companion object {
        fun from(reply:WifiReply):WifiAssistRange? {
            if(!reply.ok)return null
            val parent=reply.root?.getElementsByTagName("asst_disp")?.item(0) as? org.w3c.dom.Element ?: return null
            val mag=parent.getElementsByTagName("disp_mag").item(0) as? org.w3c.dom.Element ?: return null
            val lo=mag.getAttribute("min").toIntOrNull() ?: return null;val hi=mag.getAttribute("max").toIntOrNull() ?: return null
            return if(lo in 100..2000 && hi in lo..4000)WifiAssistRange(lo,hi) else null
        }
    }
}

data class WifiAssistState(val display:String="off",val magnification:Int?=null,val x:Int?=null,val y:Int?=null){
    val active:Boolean get()=display in listOf("full","pinp")
    companion object {
        fun parse(reply:WifiReply,previous:WifiAssistState=WifiAssistState()):WifiAssistState? {
            if(!reply.ok)return null
            val p=reply.text.trim().split(',');if(p.firstOrNull()!="ok")return null
            val mode=p.getOrNull(1)?.takeIf{it in listOf("off","full","pinp")}
            val at=if(mode!=null)2 else 1
            val mag=p.getOrNull(at)?.trim()?.toIntOrNull()?.takeIf{it in 100..4000} ?: return null
            val x=p.getOrNull(at+1)?.trim()?.toIntOrNull()?.takeIf{it in 0..1000} ?: return null
            val y=p.getOrNull(at+2)?.trim()?.toIntOrNull()?.takeIf{it in 0..1000} ?: return null
            return WifiAssistState(mode ?: previous.display,mag,x,y)
        }
    }
}
