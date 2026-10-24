package com.lumixmonitor

import com.lumixmonitor.lumix.LumixProp
import org.junit.Assert.*
import org.junit.Test

class SettingsRefreshPlanTest {
    @Test fun apertureEventsOnlyReadApertureRatherThanTheWholeCamera() {
        assertEquals(setOf(LumixProp.APERTURE), SettingsRefreshPlan.forEvent(0x02000040))
        assertEquals(setOf(LumixProp.APERTURE), SettingsRefreshPlan.forEvent(0x02000041))
    }

    @Test fun unshownPropertiesDoNotCauseUsbPolling() {
        assertTrue(SettingsRefreshPlan.forEvent(0x020001D0).isEmpty())
    }

    @Test fun aModeDialChangeRefreshesDependentExposureControls() {
        assertTrue(SettingsRefreshPlan.forEvent(0x02000080).containsAll(SettingsRefreshPlan.exposure))
    }
}
