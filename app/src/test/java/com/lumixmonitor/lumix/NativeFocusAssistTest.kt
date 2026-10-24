package com.lumixmonitor.lumix

import org.junit.Assert.assertArrayEquals
import org.junit.Test

class NativeFocusAssistTest {
    @Test fun startDefaultsToCenterWithoutInvalidFFFFCoordinates() {
        assertArrayEquals(byteArrayOf(
            0x51, 0, 0, 3, 10, 0, 0, 0,
            1, 0, 0, 0, -12, 1, -12, 1, 0, 0,
        ), NativeFocusAssist.payload(true))
    }

    @Test fun startCarriesTheSelectedPointBeforeTheCameraBeginsMagnifying() {
        // GH5 display verified: (750,250) starts in the upper-right quarter.
        assertArrayEquals(byteArrayOf(
            0x51, 0, 0, 3, 10, 0, 0, 0,
            1, 0, 0, 0, -18, 2, -6, 0, 0, 0,
        ),NativeFocusAssist.payload(true,750,250))
    }

    @Test fun endUsesItsOwnControlAndAnEmptyDataBody() {
        assertArrayEquals(byteArrayOf(0x54, 0, 0, 3, 0, 0, 0, 0), NativeFocusAssist.payload(false))
    }
}
