package com.lumixmonitor.lumix

/** Only these known setting identifiers may be written by the extended controls. */
data class RemoteSetting(val code: Int, val label: String, val section: String, val signed: Boolean = false) {
    fun text(value: Int): String = when(code) {
        0x02000052 -> "${value}K"
        0x02000172 -> if(value == 1) "Вкл." else "Выкл."
        0x06000011 -> listOf("P", "A", "S", "M").getOrNull(value) ?: "—"
        0x06000012 -> listOf("Без понижения", "1080p", "Авто").getOrNull(value) ?: "—"
        0x06000014 -> listOf("AVCHD", "MJPEG", "MP4", "MOV", "MP4 LPCM", "MP4 HEVC").getOrNull(value) ?: "Формат $value"
        0x06000013 -> VideoQualityNames.values[value]?.replace("QFHD","UHD")?.replace("MBPS","Mbit/s")?.removePrefix("MP4 ")?.removePrefix("MOV ") ?: "Режим $value"
        else -> if(signed) PanasonicData.signedMagnitude(value).let { if(it > 0) "+$it" else it.toString() } else value.toString()
    }
    companion object {
        /** Movie configuration uses a separate Panasonic operation from still-camera properties. */
        fun writeOpcode(code:Int):Int=when(code ushr 24){
            2->0x9403
            6->0x9409
            else->throw IllegalArgumentException("Unsupported property namespace")
        }
        val all = listOf(
            RemoteSetting(0x02000052,"Температура ББ","ББ"),
            RemoteSetting(0x02000053,"ББ A/B","ББ",true), RemoteSetting(0x02000054,"ББ G/M","ББ",true),
            RemoteSetting(0x02000012,"Контраст","Профиль",true), RemoteSetting(0x02000013,"Резкость","Профиль",true),
            RemoteSetting(0x02000014,"Шумоподавление","Профиль",true), RemoteSetting(0x02000015,"Насыщенность","Профиль",true),
            RemoteSetting(0x02000017,"Цветовой тон","Профиль",true),
            RemoteSetting(0x02000172,"Постоянный предпросмотр","Предпросмотр"),
            RemoteSetting(0x06000011,"Экспозиция видео","Видео"), RemoteSetting(0x06000014,"Формат записи","Видео"),
            RemoteSetting(0x06000013,"Качество записи","Видео"), RemoteSetting(0x06000012,"HDMI","Видео"),
        )
        val groups = listOf(0x02000010, 0x02000050, 0x02000170, 0x06000010)
    }
}
