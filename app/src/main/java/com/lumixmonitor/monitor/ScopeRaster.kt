package com.lumixmonitor.monitor

import android.graphics.Bitmap
import kotlin.math.ln

/** Rasterised off the UI thread, replacing thousands of Canvas calls per redraw. */
data class ScopeRaster(val waveform:Bitmap,val vectors:Bitmap) {
    companion object {
        fun from(data:Scopes):ScopeRaster {
            fun image(values:IntArray,width:Int,height:Int):Bitmap {
                val maximum=(values.maxOrNull() ?: 1).coerceAtLeast(1)
                val denominator=ln(1f+maximum)
                val pixels=IntArray(values.size){i->
                    val n=values[i]
                    if(n==0)0 else (((.25f+.75f*ln(1f+n)/denominator)*255f).toInt().coerceIn(0,255) shl 24) or 0xB4E4CC
                }
                return Bitmap.createBitmap(pixels,width,height,Bitmap.Config.ARGB_8888)
            }
            return ScopeRaster(image(data.waveform,data.width,data.height),image(data.vectors,64,64))
        }
    }
}
