package com.lumixmonitor.wifi

import org.junit.Assert.*
import org.junit.Test

class WifiCameraStatusTest {
    @Test fun batteryKeepsCameraPrecisionAndRejectsAbsentGrip(){
        assertEquals("▮▮▮▯",WifiBattery.parse("3/4")?.label)
        assertFalse(WifiBattery.parse("3/4")!!.percent)
        assertEquals("73%",WifiBattery.parse("3/4","73")?.label)
        for(s in listOf("-1/0","5/4","4/0","auto","100/100"))assertNull(WifiBattery.parse(s))
    }
    @Test fun dualCardsAndUnknownFieldsStayTruthful(){
        val s=WifiCameraStatus.from(mapOf("batt" to "4/4","batt_grip" to "-1/0","current_sd" to "sd1","sd_memory" to "set","sd_access" to "on","sd_full" to "false","sd2_memory" to "unset","temperature" to "low","rec" to "off","warn_disp" to "no_disp","video_remaincapacity" to "14609"))
        assertEquals(1,s.activeSlot);assertNull(s.grip);assertNull(s.warning);assertEquals(false,s.recording)
        assertEquals(true,s.cards[0].writing);assertEquals(false,s.cards[1].present)
        assertNull(s.cards[1].full);assertEquals(14609L,s.remainingVideo);assertNull(s.remainingPhotos)
        assertNull(WifiCameraStatus.from(emptyMap()).recording)
    }
    @Test fun nativeAssistReadsQuantizedCameraCoordinatesAndScaleOnlyReplies(){
        val initial=WifiAssistState.parse(WifiReply.parse("ok,full,680,554,596"))!!
        assertTrue(initial.active);assertEquals(680,initial.magnification);assertEquals(554,initial.x)
        val changed=WifiAssistState.parse(WifiReply.parse("ok,780,649,500"),initial)!!
        assertEquals("full",changed.display);assertEquals(780,changed.magnification);assertEquals(649,changed.x)
        assertFalse(WifiAssistState.parse(WifiReply.parse("ok,off,600,649,500"),changed)!!.active)
        for(s in listOf("err_reject","ok","ok,full,680,1001,0","ok,full,0,0,0","ok,wat,600,0,0"))assertNull(WifiAssistState.parse(WifiReply.parse(s)))
    }
    @Test fun capabilityLimitsAreAdvertisedRatherThanGuessed(){
        val good=WifiReply.parse("<camrply><result>ok</result><asst_disp><disp_mag min=\"300\" max=\"2000\"/></asst_disp></camrply>")
        assertEquals(WifiAssistRange(300,2000),WifiAssistRange.from(good))
        assertNull(WifiAssistRange.from(WifiReply.parse("<camrply><result>ok</result></camrply>")))
        assertNull(WifiAssistRange.from(WifiReply.parse(good.text.replace("max=\"2000\"","max=\"200\""))))
    }
}
