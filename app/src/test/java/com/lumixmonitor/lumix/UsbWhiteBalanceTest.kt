package com.lumixmonitor.lumix

import java.nio.ByteBuffer
import java.nio.ByteOrder
import org.junit.Assert.*
import org.junit.Test

class UsbWhiteBalanceTest {
    @Test fun captureSettlesOnlyAfterAcknowledgedSampleWithoutAnEnd(){
        val calls=mutableListOf<String>()
        UsbWhiteBalance.captureSelected(0x800b,{calls+="select";true},{calls+="read";0x800b},{calls+=it.toString();true},{calls+="settle"})
        assertEquals(listOf("select","read",UsbWhiteBalance.START.toString(),UsbWhiteBalance.SAMPLE.toString(),"settle"),calls)
        var settled=false
        try{UsbWhiteBalance.captureSelected(0x800b,{true},{0x800e},{true},{settled=true});fail("wrong target accepted")}catch(_:IllegalStateException){}
        assertFalse(settled)
    }
    @Test fun sampleConfirmsEachRequestedSlotAndDoesNotEndSuccessfulCapture(){
        for(mode in 0x800b..0x800e){
            var actual=0x800e;val operations=mutableListOf<Int>()
            UsbWhiteBalance.captureSelected(mode,{actual=it;true},{actual},{operations+=it;true})
            assertEquals(mode,actual);assertEquals(listOf(UsbWhiteBalance.START,UsbWhiteBalance.SAMPLE),operations)
        }
    }
    @Test fun missingSlotAcknowledgementNeverStartsOrSamples(){
        val controls=mutableListOf<Int>()
        for(accepted in listOf(false,true)){
            try{UsbWhiteBalance.captureSelected(0x800b,{accepted},{0x800e},{controls+=it;true});fail("wrong cell accepted")}catch(_:IllegalStateException){}
        }
        assertTrue(controls.isEmpty())
    }
    @Test fun rejectedCaptureCancelsWithoutRetryingTheSample(){
        for(rejected in listOf(UsbWhiteBalance.START,UsbWhiteBalance.SAMPLE)){
            val calls=mutableListOf<Int>()
            try{UsbWhiteBalance.captureSelected(0x800b,{true},{0x800b},{calls+=it;it!=rejected});fail("rejection ignored")}catch(_:IllegalStateException){}
            assertEquals(UsbWhiteBalance.END,calls.last());assertEquals(1,calls.count{it==rejected})
        }
    }
    @Test fun familiesKeepCustomAndKelvinSlotsOutOfPresets(){
        for(mode in 0x800b..0x800e)assertEquals("Замер",UsbWhiteBalance.family(mode))
        for(mode in 0x8010..0x8013)assertEquals("Кельвины",UsbWhiteBalance.family(mode))
        for(mode in listOf(null,2,4,0x800f,0x8014,0x8015))assertEquals("Пресеты",UsbWhiteBalance.family(mode))
    }
    @Test fun referenceGuideAndSamplesStayCenteredAndBoundedAtEverySourceSize(){
        for((w,h) in listOf(1 to 1,1280 to 720,1280 to 960,3840 to 2160)){
            val r=WhiteBalanceRegion.center(w,h)
            assertTrue(r.left>=0 && r.top>=0 && r.left+r.width<=w && r.top+r.height<=h)
            assertTrue(kotlin.math.abs(r.left*2+r.width-w)<=1)
            assertTrue(kotlin.math.abs(r.top*2+r.height-h)<=1)
            assertTrue(((r.width+r.step-1)/r.step)*((r.height+r.step-1)/r.step)<=65536)
        }
    }
    @Test fun sampleUsesUInt32CoordinatesRatherThanFocusUInt16(){
        val p=ByteBuffer.wrap(UsbWhiteBalance.payload(UsbWhiteBalance.SAMPLE)).order(ByteOrder.LITTLE_ENDIAN)
        assertEquals(16,p.remaining());assertEquals(0x03000092,p.int);assertEquals(8,p.int)
        assertEquals(500,p.int);assertEquals(500,p.int)
    }
    @Test fun startAndEndHaveNoCoordinatePayload(){
        for(code in listOf(UsbWhiteBalance.START,UsbWhiteBalance.END)){
            val p=ByteBuffer.wrap(UsbWhiteBalance.payload(code)).order(ByteOrder.LITTLE_ENDIAN)
            assertEquals(8,p.remaining());assertEquals(code,p.int);assertEquals(0,p.int)
        }
    }
    @Test fun refusesOtherControlCommandsAndOutOfFrameTargets(){
        for(action in listOf({UsbWhiteBalance.payload(0x03000011)},{UsbWhiteBalance.payload(UsbWhiteBalance.SAMPLE,-1,500)},{UsbWhiteBalance.payload(UsbWhiteBalance.SAMPLE,500,1001)})){
            try{action();fail("invalid command accepted")}catch(_:IllegalArgumentException){}
        }
    }
    @Test fun kelvinIsNeverPresentedAsAnAutomaticOrDifferentSlotTemperature(){
        assertEquals(5200,UsbWhiteBalance.temperature(0x8010,0x8010,5200))
        assertNull(UsbWhiteBalance.temperature(0x8011,0x8010,5200))
        assertNull(UsbWhiteBalance.temperature(2,2,5200))
        assertNull(UsbWhiteBalance.temperature(0x800e,0x800e,5200))
        assertNull(UsbWhiteBalance.temperature(0x8010,0x8010,0))
    }
    @Test fun usbMovieExposureSubtypeAndUnknownReadout(){
        assertEquals("Видео M",UsbShootingMode.label("video",3))
        assertEquals("Видео A",UsbShootingMode.label("video",1))
        assertEquals("Видео",UsbShootingMode.label("video",null))
        assertEquals("Видео",UsbShootingMode.label("video",65535))
        assertEquals("M",UsbShootingMode.label("M",3))
    }
    @Test fun alreadyClosedMeasurementIsOnlyBenignForEnd(){
        assertTrue(UsbWhiteBalance.accepted(UsbWhiteBalance.END,0x201d))
        assertFalse(UsbWhiteBalance.accepted(UsbWhiteBalance.SAMPLE,0x201d))
        assertFalse(UsbWhiteBalance.accepted(UsbWhiteBalance.START,0x201d))
        assertFalse(UsbWhiteBalance.accepted(UsbWhiteBalance.END,0x2002))
    }
    @Test fun colorGridMapsCenterEdgesAndOffGridDragging(){
        assertEquals(0 to 0,WhiteBalanceGrid.point(.5f,.5f))
        assertEquals(-9 to 9,WhiteBalanceGrid.point(0f,0f))
        assertEquals(9 to -9,WhiteBalanceGrid.point(2f,2f))
        assertEquals("A3 · G2",WhiteBalanceGrid.text(-3,2))
        for(a in -9..9)for(g in -9..9)assertEquals(a to g,WhiteBalanceGrid.point(WhiteBalanceGrid.x(a),WhiteBalanceGrid.y(g)))
        assertEquals(0x8009,UsbWhiteBalance.encodeShift(-9));assertEquals(9,UsbWhiteBalance.encodeShift(9))
    }
    @Test fun referenceWarningDistinguishesDarkMidtoneAndClipping(){
        assertTrue(WhiteBalanceReference.analyze(IntArray(64){0xff000000.toInt()})!!.text.startsWith("Тёмный"))
        assertTrue(WhiteBalanceReference.analyze(IntArray(64){0xffffffff.toInt()})!!.text.contains("пересвет"))
        val mid=WhiteBalanceReference.analyze(IntArray(64){0xff808080.toInt()})!!
        assertEquals(50,mid.meanPercent);assertEquals(0,mid.clippedPercent)
        assertNull(WhiteBalanceReference.analyze(intArrayOf()))
    }
    @Test fun unrelatedVendorEventsAreNotWhiteSetFailures(){
        assertTrue(UsbWhiteBalance.isFailureEvent(0xc212,0x12800042))
        assertFalse(UsbWhiteBalance.isFailureEvent(0xc212,0x12000010))
        assertFalse(UsbWhiteBalance.isFailureEvent(0xc211,0x12800042))
    }
}
