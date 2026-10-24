package com.lumixmonitor.monitor

import kotlin.math.min

data class ImageFit(val width:Float,val height:Float,val nativeZoom:Float)
object ImageGeometry {
    fun sensorPoint(x:Float,y:Float,quarterTurns:Int):Pair<Float,Float> = when((quarterTurns%4+4)%4) {
        1 -> y to 1f-x; 2 -> 1f-x to 1f-y; 3 -> 1f-y to x; else -> x to y
    }
    fun screenPoint(x:Float,y:Float,quarterTurns:Int):Pair<Float,Float> = sensorPoint(x,y,4-quarterTurns%4)
    fun fit(availableWidth:Float,availableHeight:Float,sourceWidth:Int,sourceHeight:Int,desqueeze:Float,native:Boolean,quarterTurns:Int=0):ImageFit {
        require(availableWidth>0 && availableHeight>0 && sourceWidth>0 && sourceHeight>0 && desqueeze>=1f)
        val sideways=quarterTurns % 2 != 0
        val sourceW=if(sideways)sourceHeight.toFloat() else sourceWidth*desqueeze
        val sourceH=if(sideways)sourceWidth*desqueeze else sourceHeight.toFloat()
        val fitted=min(availableWidth/sourceW,availableHeight/sourceH)
        val scale=if(native)min(fitted,1f) else fitted
        return ImageFit(sourceW*scale,sourceH*scale,if(native && scale<1f)1f/scale else 1f)
    }
}
