package com.lumixmonitor.wifi

import org.junit.Assert.*
import org.junit.Test

class WifiStreamRecoveryTest {
    @Test fun missingFirstFrameDoesNotImmediatelyDuplicateStart(){
        val r=WifiStreamRecovery(100_000)
        assertFalse(r.shouldRestart(100_020,0,true,false,false))
        assertFalse(r.shouldRestart(104_999,0,true,false,false))
        assertTrue(r.shouldRestart(105_000,0,true,false,false))
    }
    @Test fun recoveryBacksOffAndHasBoundedBudget(){
        val r=WifiStreamRecovery(0)
        assertTrue(r.shouldRestart(5000,0,true,false,false))
        assertFalse(r.shouldRestart(14999,0,true,false,false))
        assertTrue(r.shouldRestart(15000,0,true,false,false))
        assertTrue(r.shouldRestart(35000,0,true,false,false))
        assertFalse(r.shouldRestart(100000,0,true,false,false))
    }
    @Test fun playbackRecordingUnknownAndBusyNeverTriggerStart(){
        for(args in listOf(Triple(false,false,false),Triple(true,true,false),Triple(true,null,false),Triple(true,false,true))){
            assertFalse(WifiStreamRecovery(0).shouldRestart(10000,0,args.first,args.second,args.third))
        }
    }
    @Test fun receivedFramesResetBudgetButMustBecomeStaleAgain(){
        val r=WifiStreamRecovery(0)
        assertTrue(r.shouldRestart(5000,0,true,false,false))
        assertFalse(r.shouldRestart(7000,6900,true,false,false))
        assertFalse(r.shouldRestart(11899,6900,true,false,false))
        assertTrue(r.shouldRestart(11900,6900,true,false,false))
    }
    @Test fun acceptedQualityRestartGetsANewSettleWindow(){
        val r=WifiStreamRecovery(0);r.started(20000)
        assertFalse(r.shouldRestart(20020,10000,true,false,false))
        assertFalse(r.shouldRestart(24999,10000,true,false,false))
        assertTrue(r.shouldRestart(25000,10000,true,false,false))
    }
}
