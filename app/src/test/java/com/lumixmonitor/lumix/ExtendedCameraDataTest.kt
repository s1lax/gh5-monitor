package com.lumixmonitor.lumix

import org.junit.Assert.*
import org.junit.Test
import java.nio.ByteBuffer
import java.nio.ByteOrder

class ExtendedCameraDataTest {
    private fun fixture(name:String)=javaClass.getResourceAsStream("/gh5/$name.bin")!!.readBytes()
    @Test fun realCameraDescriptorsKeepTheirOwnTypesAndBoundaries(){
        val iso=CameraPropertyParser.parse(fixture("9108-02000020"))[0x02000021]!!
        assertEquals(200,iso.current);assertEquals(4,iso.valueSize);assertTrue(-1 in iso.choices)
        val aperture=CameraPropertyParser.parse(fixture("9108-02000040"))[0x02000041]!!
        assertEquals(220,aperture.current);assertEquals(2,aperture.valueSize);assertEquals(78,aperture.choices.size)
        assertEquals(220,PropDescParser.parse(fixture("9108-02000040"),2)!!.current)
        val profile=CameraPropertyParser.parse(fixture("9108-02000010"))
        assertEquals(-1,PanasonicData.signedMagnitude(profile[0x02000013]!!.current))
        assertTrue(profile[0x02000016]!!.choices.isEmpty())
    }
    @Test fun unsupportedOrConditionalValuesAreNotInvented(){
        val wb=CameraPropertyParser.parse(fixture("9108-02000050"))
        assertEquals(0x8008,wb[0x02000051]!!.current)
        assertTrue(wb[0x02000052]!!.choices.isEmpty())
        assertTrue(wb[0x02000053]!!.choices.isEmpty())
        assertEquals(listOf(0,1),CameraPropertyParser.parse(fixture("9108-02000170"))[0x02000172]!!.choices)
        assertTrue(CameraPropertyParser.parse(fixture("9108-06000010")).containsKey(0x06000013))
    }
    @Test fun truncatedAndOverflowingCameraRecordsNeverEscapeBounds(){
        val b=fixture("9108-02000040")
        for(n in b.indices)CameraPropertyParser.parse(b.copyOf(n))
        for(at in listOf(4,20,24,32,48,52,60,80,84)){
            val bad=b.copyOf();ByteBuffer.wrap(bad).order(ByteOrder.LITTLE_ENDIAN).putInt(at,Int.MAX_VALUE)
            CameraPropertyParser.parse(bad)
        }
        assertTrue(CameraPropertyParser.parse(b.copyOf(50)).isEmpty())
    }
    @Test fun verifiedWbRangeIsRestrictedToTheTestedCameraModel(){
        val raw=CameraPropertyParser.parse(fixture("9108-02000050"))
        val gh5=Gh5Compatibility.settings("DC-GH5",raw)[0x02000053]!!
        assertEquals(19,gh5.choices.size);assertTrue(0x8009 in gh5.choices && 9 in gh5.choices)
        assertTrue(gh5.verifiedGh5Range)
        assertTrue(Gh5Compatibility.settings("DC-S5",raw)[0x02000053]!!.choices.isEmpty())
    }
    @Test fun telemetryDistinguishesManualIsoAndUnknownMeterFromRealReadings(){
        val t=CameraTelemetryParser.parse(fixture("9414-12000010"),fixture("9414-12000020"),fixture("9414-16000010"),fixture("9401-01000010"))
        assertEquals(0,t.status);assertEquals(false,t.recording);assertEquals(0L,t.elapsedSeconds)
        assertEquals(100,t.battery);assertEquals(220,t.actualAperture);assertEquals(40000,t.actualShutter)
        assertNull(t.actualIso);assertNull(t.meterThirds);assertEquals(65535,t.afStatus)
        assertTrue(t.remainingImages!!>0 && t.remainingSeconds!!>0)
    }
    @Test fun truncatedTelemetryDoesNotBecomeZeroExposureOrFakeRecording(){
        val t=CameraTelemetryParser.parse(byteArrayOf(),byteArrayOf(),byteArrayOf())
        assertNull(t.recording);assertNull(t.actualIso);assertNull(t.actualAperture);assertNull(t.battery)
        assertTrue(PanasonicData.blocks(byteArrayOf(1,0,0,0,-1,-1,-1,127)).isEmpty())
    }
    @Test fun recordingPacketReportsActualSecondsAndKeepsUnknownPhotoCapacityUnknown(){
        val t=CameraTelemetryParser.parse(fixture("recording-12000010"),byteArrayOf(),byteArrayOf())
        assertEquals(true,t.recording);assertEquals(2L,t.elapsedSeconds)
        assertNull(t.remainingImages);assertTrue(t.remainingSeconds!!>0)
    }
    @Test fun objectInfoRejectsPathsAndTruncatedNames(){
        fun info(name:String):ByteArray {
            val s=(name+"\u0000").toByteArray(Charsets.UTF_16LE)
            return ByteArray(53+s.size).also{b->b[52]=(name.length+1).toByte();s.copyInto(b,53);ByteBuffer.wrap(b).order(ByteOrder.LITTLE_ENDIAN).putInt(8,5000000)}
        }
        assertEquals("PANA7353.JPG",CameraObjectParser.parse(7,info("PANA7353.JPG"))!!.name)
        assertNull(CameraObjectParser.parse(7,info("../bad.JPG")))
        assertNull(CameraObjectParser.parse(7,info("PANA7353.JPG").copyOf(60)))
    }
}
