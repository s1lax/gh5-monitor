package com.lumixmonitor.wifi

import org.junit.Assert.*
import org.junit.Test

class WifiSessionHealthTest {
    @Test fun threeHttpTimeoutsCannotKillFreshVideo(){
        assertFalse(WifiSessionHealth.expired(30000,29500,0))
    }
    @Test fun controlRepliesKeepAStalledPreviewSessionAlive(){
        assertFalse(WifiSessionHealth.expired(30000,0,29000))
    }
    @Test fun boundedGracePeriodExpiresOnlyWhenBothPathsAreSilent(){
        assertFalse(WifiSessionHealth.expired(19999,0,0))
        assertTrue(WifiSessionHealth.expired(20000,0,0))
        assertFalse(WifiSessionHealth.expired(30000,16000,0))
        assertTrue(WifiSessionHealth.expired(31000,16000,0))
    }
}
