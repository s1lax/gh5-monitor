package com.lumixmonitor.lumix

import org.junit.Assert.*
import org.junit.Test

class LatestPresentationTest {
    @Test fun aBusyUiOnlyReceivesTheLatestOfAHundredFrames() {
        val mailbox = LatestPresentation<Int>()
        assertTrue(mailbox.offer(1))
        for (frame in 2..100) assertFalse(mailbox.offer(frame))
        assertEquals(100, mailbox.take())
        assertTrue(mailbox.offer(101))
        assertEquals(101, mailbox.take())
    }

    @Test fun stopDiscardsUndeliveredFrames() {
        val mailbox = LatestPresentation<Int>()
        mailbox.offer(42)
        mailbox.clear()
        assertNull(mailbox.take())
    }
}
