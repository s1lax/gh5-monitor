package com.lumixmonitor

import org.junit.Assert.assertEquals
import org.junit.Test

class SettingScaleTest {
    @Test fun isoKeepsAutoThenAscendingAvailableSteps(){
        assertEquals(listOf(-1,100,400,1600),SettingScale.ordered(SettingKind.ISO,intArrayOf(1600,400,-1,100,400)))
    }
    @Test fun apertureUsesAscendingFNumbersWithoutAddingStops(){
        assertEquals(listOf(35,56,80),SettingScale.ordered(SettingKind.APERTURE,intArrayOf(80,35,56)))
    }
    @Test fun extendedIsoUsesItsNumericSensitivityAndUnknownApertureIsNotSelectable(){
        assertEquals(listOf(-1,0x10000064,0x100000a0,200,400),SettingScale.ordered(SettingKind.ISO,intArrayOf(400,200,-1,0x100000a0,0x10000064,-3)))
        assertEquals(listOf(35,80),SettingScale.ordered(SettingKind.APERTURE,intArrayOf(0,80,0xfffe,35)))
    }
    @Test fun exposureCrossesZeroInPhysicalOrder(){
        assertEquals(listOf(0x8006,0x8001,0,1,3),SettingScale.ordered(SettingKind.EXPOSURE,intArrayOf(3,0,0x8001,1,0x8006)))
    }
    @Test fun shutterOrdersSecondsRatherThanEncodedNumbers(){
        assertEquals(listOf(0x0fffffff,125000,60000,1000,Int.MIN_VALUE or 2000,-1),
            SettingScale.ordered(SettingKind.SHUTTER,intArrayOf(-1,1000,60000,Int.MIN_VALUE or 2000,125000,0x0fffffff)))
    }
    @Test fun namedOptionsRetainCameraOrderAndEmptyListStaysEmpty(){
        assertEquals(listOf(4,2,7),SettingScale.ordered(SettingKind.WHITE_BALANCE,intArrayOf(4,2,7,4)))
        assertEquals(emptyList<Int>(),SettingScale.ordered(SettingKind.ISO,intArrayOf()))
    }
}
