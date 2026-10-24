package com.lumixmonitor.monitor

/** Encoded JPEG brightness values, not sensor exposure or calibrated IRE. */
data class PreviewSignalStats(val samples:Int,val min:Int,val median:Int,val max:Int,val mean:Double,val atOrBelow16:Int,val atOrAbove235:Int) {
    companion object {
        fun from(pixels:IntArray):PreviewSignalStats {
            require(pixels.isNotEmpty())
            val bins=IntArray(256);var sum=0L
            pixels.forEach{val y=ScopeMath.luma(it);bins[y]++;sum+=y}
            var total=0
            val median=bins.indices.first{total+=bins[it];total>=(pixels.size+1)/2}
            return PreviewSignalStats(pixels.size,bins.indexOfFirst{it>0},median,bins.indexOfLast{it>0},sum.toDouble()/pixels.size,bins.take(17).sum(),bins.drop(235).sum())
        }
    }
}
