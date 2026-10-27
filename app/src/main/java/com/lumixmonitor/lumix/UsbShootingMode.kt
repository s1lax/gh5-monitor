package com.lumixmonitor.lumix

object UsbShootingMode {
    fun label(mode:String,movieExposure:Int?):String=if(mode=="video")
        listOf("P","A","S","M").getOrNull(movieExposure ?: -1)?.let{"Видео $it"} ?: "Видео" else mode
}
