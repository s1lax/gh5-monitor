package com.lumixmonitor.wifi

/** Read-only mode metadata. Creative Movie and its exposure program are separate fields. */
object WifiShootingMode {
    fun fields(reply:WifiReply):Map<String,String> {
        if(!reply.ok)return emptyMap()
        val nodes=reply.root?.getElementsByTagName("item") ?: return emptyMap()
        val reported=mutableMapOf<String,String>()
        for(i in 0 until nodes.length){val e=nodes.item(i) as? org.w3c.dom.Element ?: continue
            if(e.hasAttribute("value"))reported[e.getAttribute("id")]=e.getAttribute("value")
        }
        val mode=reported["menu_item_id_recmode"] ?: return emptyMap()
        return mapOf("recmode" to mode,"recmode_detail" to reported["menu_item_id_recmode_$mode"].orEmpty())
    }
    fun label(mode:String?,detail:String?=null):String {
        fun exposure(value:String?)=when(value){"program_ae"->"P";"aperture_ae"->"A";"shutter_ae"->"S";"manual_exposure"->"M";else->null}
        exposure(mode)?.let{return it}
        return when(mode){
            "creative_movie"->exposure(detail)?.let{"Видео $it"} ?: "Видео"
            "ia"->if(detail=="iaplus")"iA+" else "iA"
            "c1"->"C1";"c2"->"C2";"c3"->when(detail){"c3_1"->"C3-1";"c3_2"->"C3-2";"c3_3"->"C3-3";else->"C3"}
            "creative_ctrl"->"Фильтр"
            else->"—"
        }
    }
}
