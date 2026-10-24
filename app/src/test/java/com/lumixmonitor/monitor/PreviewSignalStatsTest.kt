package com.lumixmonitor.monitor

import org.junit.Assert.*
import org.junit.Test

class PreviewSignalStatsTest {
    @Test fun fullRangeEndpointsAreNotExpandedOrClipped(){
        val s=PreviewSignalStats.from(intArrayOf(0xff000000.toInt(),0xffffffff.toInt()))
        assertEquals(0,s.min);assertEquals(255,s.max);assertEquals(127.5,s.mean,0.001)
        assertEquals(1,s.atOrBelow16);assertEquals(1,s.atOrAbove235)
    }
    @Test fun videoLookingLevelsDoNotTriggerAutomaticRangeCorrection(){
        val s=PreviewSignalStats.from(intArrayOf(0xff101010.toInt(),0xff7e7e7e.toInt(),0xffebebeb.toInt()))
        assertEquals(16,s.min);assertEquals(126,s.median);assertEquals(235,s.max)
    }
    @Test fun midGrayIsMeasuredAsEncodedCodeValue(){
        val s=PreviewSignalStats.from(IntArray(25){0xff808080.toInt()})
        assertEquals(128,s.median);assertEquals(128.0,s.mean,0.001)
    }
    @Test(expected=IllegalArgumentException::class) fun emptyInputDoesNotInventStatistics(){PreviewSignalStats.from(intArrayOf())}
}
