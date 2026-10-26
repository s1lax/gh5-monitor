package com.lumixmonitor.wifi

import org.w3c.dom.Element
import java.net.URI

data class WifiFileResource(val url:String,val size:Long?,val mime:String,val resolution:String){
    val name:String get()=URI(url).path.substringAfterLast('/')
    val original:Boolean get()=name.startsWith("DO",true) || name.endsWith(".RW2",true)
}
data class WifiCardFile(val id:String,val title:String,val container:Boolean,val resources:List<WifiFileResource>){
    val original:WifiFileResource? get()=resources.firstOrNull{it.original}
    val preview:WifiFileResource? get()=resources.firstOrNull{it.name.startsWith("DL",true)} ?: resources.firstOrNull{it.name.startsWith("DT",true)} ?: original?.takeIf{it.mime=="image/jpeg"}
    val thumbnail:WifiFileResource? get()=resources.firstOrNull{it.name.startsWith("DT",true)} ?: preview
    val name:String get(){
        val ext=original?.name?.substringAfterLast('.',"")?.uppercase().orEmpty()
        return if(ext.isNotEmpty() && !title.endsWith(".$ext",true))"$title.$ext" else title
    }
}
data class WifiFilePage(val items:List<WifiCardFile>,val returned:Int,val total:Int)

/** SOAP/DIDL are camera input. Never follow resource URLs off this camera. */
object WifiCardFiles {
    fun resource(url:String,host:String):URI {
        val u=URI(url)
        require(u.scheme=="http" && u.host==host && u.port==50001 && u.userInfo==null && u.fragment==null){"Invalid camera file URL"}
        require(u.rawPath.length in 2..1024 && !u.path.split('/').contains("..") && u.rawQuery==null){"Invalid camera file path"}
        return u
    }
    private fun escaped(s:String)=s.replace("&","&amp;").replace("<","&lt;").replace(">","&gt;").replace("\"","&quot;").replace("'","&apos;")
    fun browseBody(id:String,start:Int,count:Int=30):String {
        require(id.length<=256 && start>=0 && count in 1..50)
        return """<?xml version="1.0" encoding="utf-8"?><s:Envelope xmlns:s="http://schemas.xmlsoap.org/soap/envelope/" s:encodingStyle="http://schemas.xmlsoap.org/soap/encoding/"><s:Body><u:Browse xmlns:u="urn:schemas-upnp-org:service:ContentDirectory:1" xmlns:pana="urn:schemas-panasonic-com:pana"><ObjectID>${escaped(id)}</ObjectID><BrowseFlag>BrowseDirectChildren</BrowseFlag><Filter>*</Filter><StartingIndex>$start</StartingIndex><RequestedCount>$count</RequestedCount><SortCriteria></SortCriteria><pana:X_FromCP>LumixLink2.0</pana:X_FromCP></u:Browse></s:Body></s:Envelope>"""
    }
    fun parse(text:String,host:String):WifiFilePage {
        val root=WifiReply.parse(text).root ?: error("Invalid camera file list")
        fun local(e:Element)=e.tagName.substringAfter(':')
        fun find(e:Element,name:String):String? {val all=e.getElementsByTagName("*");for(i in 0 until all.length){val n=all.item(i) as? Element ?: continue;if(local(n)==name)return n.textContent};return null}
        find(root,"errorCode")?.let{error("GH5 file list: $it ${find(root,"errorDescription").orEmpty()}")}
        val didl=WifiReply.parse(find(root,"Result") ?: error("Camera returned no file list")).root ?: error("Invalid camera file metadata")
        val items=mutableListOf<WifiCardFile>();val nodes=didl.childNodes
        for(i in 0 until nodes.length){val e=nodes.item(i) as? Element ?: continue;val kind=local(e);if(kind !in listOf("item","container"))continue
            val resources=mutableListOf<WifiFileResource>();val children=e.getElementsByTagName("*")
            for(k in 0 until children.length){val r=children.item(k) as? Element ?: continue;if(local(r)!="res")continue
                val url=r.textContent.trim();if(runCatching{resource(url,host)}.isFailure)continue
                val mime=r.getAttribute("protocolInfo").split(':').getOrNull(2).orEmpty()
                resources+=WifiFileResource(url,r.getAttribute("size").toLongOrNull()?.takeIf{it>=0},mime,r.getAttribute("resolution"))
            }
            items+=WifiCardFile(e.getAttribute("id"),find(e,"title")?.take(200) ?: "Файл",kind=="container",resources)
        }
        return WifiFilePage(items,find(root,"NumberReturned")?.toIntOrNull()?.coerceAtLeast(0) ?: items.size,find(root,"TotalMatches")?.toIntOrNull()?.coerceAtLeast(0) ?: items.size)
    }
}
