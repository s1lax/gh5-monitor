package com.lumixmonitor.monitor

import org.junit.Assert.*
import org.junit.Test

class MonitorMathTest {
    private fun identity() = "LUT_3D_SIZE 2\n" + (0..1).flatMap { b -> (0..1).flatMap { g -> (0..1).map { r -> "$r $g $b" } } }.joinToString("\n")
    @Test fun cubeAtlasPreservesRedGreenBlueOrdering() {
        val lut=CubeLut.parse(identity().reader())
        val atlas=lut.atlas()
        fun value(x:Int,y:Int,c:Int)=atlas[(y*4+x)*4+c].toInt() and 255
        assertEquals(255,value(1,0,0));assertEquals(0,value(1,0,1));assertEquals(0,value(1,0,2))
        assertEquals(255,value(0,1,1));assertEquals(255,value(2,0,2))
    }
    @Test(expected=IllegalArgumentException::class) fun truncatedCubeRejected(){CubeLut.parse("LUT_3D_SIZE 2\n0 0 0".reader())}
    @Test(expected=IllegalArgumentException::class) fun invalidDomainRejected(){CubeLut.parse(("DOMAIN_MAX 0 1 1\n"+identity()).reader())}
    @Test(expected=IllegalArgumentException::class) fun hugeCubeRejected(){CubeLut.parse("LUT_3D_SIZE 256".reader())}
    @Test fun blackAndWhiteScopesUseFullDigitalRange(){
        val result=ScopeMath.analyse(intArrayOf(0xff000000.toInt(),0xffffffff.toInt()),2,1)
        assertEquals(1,result.histogram[0]);assertEquals(1,result.histogram[255])
        assertEquals(1,result.waveform[63*2]);assertEquals(1,result.waveform[1])
        assertEquals(2,result.vectors.sum())
    }
    @Test fun scopesUseOriginalRgbChannels(){
        val result=ScopeMath.analyse(intArrayOf(0xffff0000.toInt(),0xff00ff00.toInt(),0xff0000ff.toInt()),3,1)
        assertEquals(54,ScopeMath.luma(0xffff0000.toInt()))
        for(c in 0..2){assertEquals(1,result.rgb[c][255]);assertEquals(3,result.rgb[c].sum())}
        assertEquals(3,result.histogram.sum())
    }
    @Test fun fullFrameFitsWithoutCropping(){val fit=ImageGeometry.fit(2000f,1080f,1280,720,1f,false);assertEquals(1920f,fit.width,.1f);assertEquals(1080f,fit.height,.1f)}
    @Test fun nativePixelsDoNotUpscale(){val fit=ImageGeometry.fit(1920f,1080f,1280,720,1f,true);assertEquals(1280f,fit.width,.1f);assertEquals(720f,fit.height,.1f);assertEquals(1f,fit.nativeZoom,.001f)}
    @Test fun nativePixelsCanCropOnSmallViewport(){val fit=ImageGeometry.fit(960f,540f,1280,720,1f,true);assertEquals(960f,fit.width,.1f);assertEquals(1280f/960f,fit.nativeZoom,.001f)}
    @Test fun anamorphicFitKeepsWholeFrame(){val fit=ImageGeometry.fit(1920f,1080f,1280,720,2f,false);assertEquals(1920f,fit.width,.1f);assertEquals(540f,fit.height,.1f)}
    @Test fun portraitScreenKeepsLandscapeCameraFrameUncropped(){
        val fit=ImageGeometry.fit(1080f,1700f,1280,720,1f,false)
        assertEquals(1080f,fit.width,.1f);assertEquals(607.5f,fit.height,.1f)
    }
    @Test fun quarterTurnFitsPortraitCameraFrameWithoutStretch(){
        for(rotation in listOf(1,3)){
            val fit=ImageGeometry.fit(1080f,1700f,1280,720,1f,false,rotation)
            assertEquals(956.25f,fit.width,.1f);assertEquals(1700f,fit.height,.1f)
            assertEquals(720f/1280f,fit.width/fit.height,.001f)
        }
    }
    @Test fun rotatedAnamorphicFrameAppliesDesqueezeBeforeRotation(){
        val fit=ImageGeometry.fit(1080f,1700f,1280,720,2f,false,1)
        assertEquals(478.125f,fit.width,.1f);assertEquals(1700f,fit.height,.1f)
    }
    @Test fun rotatedNativePixelModeDoesNotEnlargeSource(){
        val fit=ImageGeometry.fit(1080f,1920f,1280,720,1f,true,3)
        assertEquals(720f,fit.width,.1f);assertEquals(1280f,fit.height,.1f)
        assertEquals(1f,fit.nativeZoom,.001f)
    }
}
