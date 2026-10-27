package com.lumixmonitor.lumix

/** Visual center guide inferred from GH5's White Set screen, not a reported sensor ROI. */
data class WhiteBalanceRegion(val left:Int,val top:Int,val width:Int,val height:Int){
    val step:Int get()=maxOf((width+255)/256,(height+255)/256,1)
    companion object {
        const val FRACTION=1f/3f
        fun center(width:Int,height:Int):WhiteBalanceRegion{
            require(width>0 && height>0)
            val w=(width*FRACTION).toInt().coerceAtLeast(1);val h=(height*FRACTION).toInt().coerceAtLeast(1)
            return WhiteBalanceRegion((width-w)/2,(height-h)/2,w,h)
        }
    }
}

/** A preview-based warning, never a camera exposure meter or proof of a neutral reference. */
data class WhiteBalanceReference(val meanPercent:Int,val clippedPercent:Int){
    val text:String get()=when{
        meanPercent<8->"Тёмный центр кадра ($meanPercent%). Добавьте свет, откройте диафрагму или поднимите ISO."
        clippedPercent>=10->"В центре возможен пересвет ($clippedPercent% пикселей). Уменьшите экспозицию."
        else->"Яркость центра: $meanPercent% · оценка по USB-кадру"
    }
    companion object {
        fun analyze(pixels:IntArray):WhiteBalanceReference? {
            if(pixels.isEmpty() || pixels.size>65536)return null
            var sum=0.0;var clipped=0
            pixels.forEach{p->val r=p ushr 16 and 255;val g=p ushr 8 and 255;val b=p and 255
                sum+=.2126*r+.7152*g+.0722*b;if(maxOf(r,g,b)>=250)clipped++}
            return WhiteBalanceReference((sum/pixels.size/255*100).toInt(),clipped*100/pixels.size)
        }
    }
}

object WhiteBalanceGrid {
    fun point(x:Float,y:Float):Pair<Int,Int> =
        kotlin.math.round((x.coerceIn(0f,1f)-.5f)*18).toInt() to kotlin.math.round((.5f-y.coerceIn(0f,1f))*18).toInt()
    fun x(ab:Int)=(.5f+ab.coerceIn(-9,9)/18f)
    fun y(gm:Int)=(.5f-gm.coerceIn(-9,9)/18f)
    fun text(ab:Int,gm:Int)=listOf(when{ab>0->"B$ab";ab<0->"A${-ab}";else->"A/B 0"},when{gm>0->"G$gm";gm<0->"M${-gm}";else->"G/M 0"}).joinToString(" · ")
}
