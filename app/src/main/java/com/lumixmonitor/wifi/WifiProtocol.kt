package com.lumixmonitor.wifi

import java.io.StringReader
import java.net.URLEncoder
import java.util.Locale
import javax.xml.parsers.DocumentBuilderFactory
import org.w3c.dom.Element
import org.xml.sax.InputSource
import kotlin.math.pow
import kotlin.math.roundToInt

/** A local camera address, never an arbitrary URL, path, credentials or public server. */
data class CameraEndpoint(val host:String,val port:Int=80) {
    val address:String get()=host+if(port==80)"" else ":$port"
    fun cgi(mode:String,params:Map<String,String> = emptyMap()):String {
        fun enc(s:String)=URLEncoder.encode(s,"UTF-8")
        return "http://$host:$port/cam.cgi?"+(mapOf("mode" to mode)+params).entries.joinToString("&"){enc(it.key)+"="+enc(it.value)}
    }
    companion object {
        fun parse(text:String):CameraEndpoint {
            val parts=text.trim().split(':');require(parts.size in 1..2){"Enter a local camera IPv4 address, optionally :port"}
            val octets=parts[0].split('.').map{it.toIntOrNull() ?: -1}
            require(octets.size==4 && octets.all{it in 0..255}){"Invalid camera IPv4 address"}
            val local=octets[0]==10 || (octets[0]==172 && octets[1] in 16..31) ||
                (octets[0]==192 && octets[1]==168) || (octets[0]==169 && octets[1]==254) || octets[0]==127
            require(local && octets.last() !in listOf(0,255)){"Use a local camera address"}
            val port=if(parts.size==2)parts[1].toIntOrNull() ?: 0 else 80
            require(port in 1..65535){"Invalid camera port"}
            return CameraEndpoint(octets.joinToString("."),port)
        }
    }
}

data class WifiReply(val result:String,val text:String,val root:Element?,val values:Map<String,String>) {
    val ok:Boolean get()=result=="ok"
    val waitingApproval:Boolean get()=result.startsWith("ok_under_research")
    companion object {
        fun parse(text:String):WifiReply {
            require(text.length<=2_097_152){"Camera reply too large"}
            if(!text.trimStart().startsWith('<'))return WifiReply(text.trim().substringBefore(','),text,null,emptyMap())
            require(!Regex("<!\\s*(DOCTYPE|ENTITY)",RegexOption.IGNORE_CASE).containsMatchIn(text)){"External XML declarations are forbidden"}
            val f=DocumentBuilderFactory.newInstance().apply{isNamespaceAware=false;isExpandEntityReferences=false}
            val builder=f.newDocumentBuilder().apply{setEntityResolver{_,_->InputSource(StringReader(""))}}
            val root=builder.parse(InputSource(StringReader(text))).documentElement
            val result=root.getElementsByTagName("result").item(0)?.textContent?.trim() ?: "unknown"
            val values=linkedMapOf<String,String>()
            val nodes=root.getElementsByTagName("*")
            for(i in 0 until nodes.length){val e=nodes.item(i) as? Element ?: continue
                if(e.childNodes.length==1 && e.firstChild.nodeType==org.w3c.dom.Node.TEXT_NODE)values[e.tagName]=e.textContent.trim()
                if(e.tagName=="settingvalue")for(n in 0 until e.attributes.length){val a=e.attributes.item(n);values[a.nodeName]=a.nodeValue}
            }
            return WifiReply(result,text,root,values)
        }
    }
}

data class WifiOption(val id:Int,val type:String,val value:String,val label:String,val optionId:String="",val value2:String?=null,val settingId:String="")

/** Stable opaque choice IDs: no PTP numeric value is ever written to the HTTP API. */
class WifiSettingsCatalog {
    private val ids=mutableMapOf<String,Int>();private var next=1
    var options:List<WifiOption> = emptyList();private set
    var values:Map<String,String> = emptyMap();private set
    private var enabled=emptyMap<String,Boolean>()
    fun parseMenu(reply:WifiReply){
        if(!reply.ok || reply.root==null)return
        val root=reply.root;val titleMap=mutableMapOf<String,String>()
        val langs=root.getElementsByTagName("language")
        val lang=(0 until langs.length).map{langs.item(it) as Element}.firstOrNull{it.getAttribute("code")=="en"}
        if(lang!=null){val titles=lang.getElementsByTagName("title");for(i in 0 until titles.length){val e=titles.item(i) as Element;titleMap[e.getAttribute("id")]=e.textContent.trim()}}
        val found=mutableListOf<WifiOption>();val items=root.getElementsByTagName("item")
        for(i in 0 until items.length){val e=items.item(i) as Element
            if(e.getAttribute("cmd_mode")!="setsetting")continue
            val type=e.getAttribute("cmd_type");val value=e.getAttribute("cmd_value")
            val value2=e.getAttribute("cmd_value2")
            fun concrete(v:String)=v.length<=128 && '#' !in v && !v.contains("__")
            if(type !in TYPES || value.isBlank() || !concrete(value) || !concrete(value2))continue
            var parent=e.parentNode as? Element
            while(parent!=null && parent.tagName!="item")parent=parent.parentNode as? Element
            val label=titleMap[e.getAttribute("title_id")]?.takeIf{it.isNotBlank()} ?: label(type,value)
            found+=option(type,value,label,e.getAttribute("id"),value2.takeIf{it.isNotBlank()},parent?.getAttribute("id") ?: "")
        }
        options=found.distinctBy{it.id}
    }
    private fun option(type:String,value:String,label:String,optionId:String="",value2:String?=null,settingId:String=""):WifiOption {
        val key="$type\u0000$value\u0000$value2";val id=ids.getOrPut(key){next++}
        return WifiOption(id,type,value,label,optionId,value2,settingId)
    }
    fun applyCurrent(reply:WifiReply){
        if(!reply.ok || reply.root==null)return
        val flags=mutableMapOf<String,Boolean>();val current=values.toMutableMap();val nodes=reply.root.getElementsByTagName("item")
        for(i in 0 until nodes.length){val e=nodes.item(i) as Element;val id=e.getAttribute("id")
            flags[id]=e.getAttribute("enable")!="no"
            if(id=="menu_item_id_recmode" && e.hasAttribute("value"))current["recmode"]=e.getAttribute("value")
            if(e.hasAttribute("value"))options.filter{it.settingId==id}.map{it.type}.distinct().forEach{current[it]=e.getAttribute("value")}
        }
        enabled=flags;values=current
    }
    fun applyValues(next:Map<String,String>){values=values+next+next["sspeed"]?.let{mapOf("shtrspeed" to it)}.orEmpty()}
    fun applyLens(reply:WifiReply){
        if(!reply.ok)return
        val v=reply.text.trim().split(',')
        if(v.size<5)return
        fun code(s:String)=apex(s)?.times(256)?.roundToInt()
        fun range(type:String,a:String,b:String){
            val x=code(a) ?: return;val y=code(b) ?: return
            if(x !in -2048..4096 || y !in -2048..4096)return
            if(options.any{it.type==type})return
            val lo=minOf(x,y);val hi=maxOf(x,y)
            val steps=(-24..48).map{(256.0*it/3).roundToInt()}.filter{it in lo..hi}
            options=options+steps.map{option(type,"$it/256",label(type,"$it/256"))}
        }
        range("focal",v[2],v[1]);range("shtrspeed",v[4],v[3])
    }
    fun available(type:String)=options.filter{it.type==type && enabled[it.optionId]!=false && enabled[it.settingId]!=false}
    fun current(type:String):WifiOption?=values[type]?.let{v->available(type).firstOrNull{it.value==v || it.optionId==v}}
    fun text(type:String)=current(type)?.label ?: values[type]?.let{label(type,it)} ?: "—"
    companion object {
        val TYPES=setOf("iso","focal","shtrspeed","exposure","whitebalance","photostyle","focusmode","afmode","liveviewsize")
        fun apex(raw:String):Double?{val p=raw.split('/');val n=p[0].toDoubleOrNull() ?: return null;val d=if(p.size==2)p[1].toDoubleOrNull() ?: return null else 256.0;return (n/d).takeIf{d>0 && it.isFinite()}}
        fun label(type:String,value:String):String=when(type){
            "focal"->apex(value)?.takeIf{it in -2.0..14.0}?.let{
                val k=(it*3).roundToInt();val names=listOf("1.0","1.1","1.2","1.4","1.6","1.8","2.0","2.2","2.5","2.8","3.2","3.5","4.0","4.5","5.0","5.6","6.3","7.1","8.0","9.0","10","11","13","14","16","18","20","22","25","29","32")
                if(k in names.indices && kotlin.math.abs(it*3-k)<.05)"f/${names[k]}" else String.format(Locale.US,"f/%.1f",2.0.pow(it/2))
            } ?: "—"
            "shtrspeed"->apex(value)?.takeIf{it in -12.0..20.0}?.let{
                val k=(it*3).roundToInt();val names=listOf("1.3","1.6","2","2.5","3","4","5","6","8","10","13","15","20","25","30","40","50","60","80","100","125","160","200","250","320","400","500","640","800","1000","1250","1600","2000","2500","3200","4000","5000","6400","8000","10000","13000","16000")
                if(k in 1..names.size && kotlin.math.abs(it*3-k)<.05)"1/${names[k-1]}" else if(it>0)"1/${2.0.pow(it).roundToInt()}" else String.format(Locale.US,"%.1fs",2.0.pow(-it))
            } ?: "—"
            "exposure"->value.toDoubleOrNull()?.let{String.format(Locale.US,"%+.1f",it/3)} ?: value
            "liveviewsize"->when(value){"vga"->"VGA";"qvga"->"QVGA";else->value}
            "whitebalance"->when(value){"color_temp1","color_temp2","color_temp3","color_temp4"->"K${value.last()}";"white_set1","white_set2","white_set3","white_set4"->"WB ${value.last()}";"auto"->"AWB";"auto_warm"->"AWBw";"auto_cool"->"AWBc";else->value}
            else->value
        }
    }
}

/** One complete JPEG per Panasonic UDP datagram; incomplete/lost frames are discarded. */
object WifiJpeg {
    fun extract(data:ByteArray,size:Int=data.size):ByteArray? {
        if(size !in 4..data.size || size>65507)return null
        var start=-1
        if(size>32){val off=((data[30].toInt() and 255) shl 8)+(data[31].toInt() and 255)+32
            if(off+2<size && data[off]==(-1).toByte() && data[off+1]==0xd8.toByte())start=off}
        if(start<0)for(i in 0 until minOf(size-2,2048)){if(data[i]==(-1).toByte() && data[i+1]==0xd8.toByte() && data[i+2]==(-1).toByte()){start=i;break}}
        if(start<0)return null
        var at=start+2;var scan=false
        while(at<size-1){
            if(data[at]!=(-1).toByte()){if(!scan)return null;at++;continue}
            val mark=data[at+1].toInt() and 255
            if(mark==0xff){at++;continue}
            if(mark==0xd9)return data.copyOfRange(start,at+2)
            if(scan && (mark==0 || mark in 0xd0..0xd7)){at+=2;continue}
            if(mark==0xd8)return null
            if(mark==1 || mark in 0xd0..0xd7){at+=2;continue}
            if(at+3>=size)return null
            val length=((data[at+2].toInt() and 255) shl 8)+(data[at+3].toInt() and 255)
            if(length<2 || length>size-at-2)return null
            at+=2+length;if(mark==0xda)scan=true
        }
        return null
    }
}
