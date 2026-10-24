package com.lumixmonitor

/** Display order for a discrete wheel; values are never generated or interpolated. */
internal object SettingScale {
    fun ordered(kind:SettingKind,values:IntArray):List<Int> {
        val unique=values.distinct()
        return when(kind){
            SettingKind.ISO -> unique.filter{it != -3}.sortedWith(compareBy<Int>{if(it<0)0 else 1}.thenBy{it and 0x0fffffff})
            SettingKind.APERTURE -> unique.filter{it!=0 && it!=0xfffe}.sortedWith(compareBy<Int>{if(it==0xffff)0 else 1}.thenBy{it})
            SettingKind.EXPOSURE -> unique.sortedBy{if(it and 0x8000!=0)-(it and 0x7fff) else it}
            SettingKind.SHUTTER -> unique.sortedBy{shutterSeconds(it)}
            else -> unique
        }
    }

    private fun shutterSeconds(value:Int):Double=when(value){
        0x0fffffff,0x0ffffffe -> -1.0
        -1 -> Double.POSITIVE_INFINITY
        else -> if(value and Int.MIN_VALUE!=0)(value and Int.MAX_VALUE)/1000.0
            else if(value==0)0.0 else 1000.0/value
    }
}
