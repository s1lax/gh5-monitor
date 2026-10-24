package com.lumixmonitor

import org.junit.Assert.*
import org.junit.Test

class ConnectionRecoveryTest {
    @Test fun brokenDataPhaseNeverReopensTheSameUsbDeviceEvenAfterLongBackoff() {
        val policy = ConnectionRecovery()
        policy.frameReceived(10_000)
        policy.blockUntilUsbChanges("/usb/001/002")
        policy.observeDevice("/usb/001/002")
        assertTrue(policy.requiresUsbChange)
        assertFalse(policy.canAttempt(1_000_000))
        assertFalse(policy.needsRecovery(1_000_000, false))
    }

    @Test fun actualDetachAllowsTheSameAddressToBeUsedAgain() {
        val policy = ConnectionRecovery()
        policy.attemptStarted(10_000)
        policy.blockUntilUsbChanges("/usb/001/002")
        policy.observeDevice(null)
        policy.observeDevice("/usb/001/002")
        assertFalse(policy.requiresUsbChange)
        assertTrue(policy.canAttempt(10_001))
    }

    @Test fun reenumeratedCameraUnblocksWithoutAnObservedAbsentPollingInterval() {
        val policy = ConnectionRecovery()
        policy.blockUntilUsbChanges("/usb/001/002")
        policy.observeDevice("/usb/001/003")
        assertFalse(policy.requiresUsbChange)
        assertTrue(policy.canAttempt(0))
    }

    @Test fun cameraReturningAfterSeveralFailuresDoesNotCauseAnImmediateRetryStorm() {
        val policy = ConnectionRecovery()
        policy.attemptStarted(0)
        assertFalse(policy.canAttempt(1_499))
        policy.attemptStarted(1_500)
        assertFalse(policy.canAttempt(4_499))
        policy.opened(4_500)
        // A session that opens but never supplies frames must still be recovered.
        assertFalse(policy.needsRecovery(12_499, true))
        assertTrue(policy.needsRecovery(12_500, true))
    }

    @Test fun aLiveFrameResetsBackoffButALensChangeGetsATemporaryGracePeriod() {
        val policy = ConnectionRecovery()
        repeat(8) { policy.attemptStarted(it * 20_000L) }
        policy.frameReceived(200_000)
        assertFalse(policy.needsRecovery(200_100, true))
        policy.streamFailed(200_500)
        assertFalse(policy.needsRecovery(201_999, false))
        assertTrue(policy.needsRecovery(202_000, false))
        policy.attemptStarted(202_000)
        assertTrue(policy.canAttempt(203_500))
    }

    @Test fun framesContinuingToArriveDoNotTriggerReconnects() {
        val policy = ConnectionRecovery()
        for (now in 0L..100_000L step 40L) {
            policy.frameReceived(now)
            assertFalse(policy.needsRecovery(now + 39, true))
        }
        assertTrue(policy.needsRecovery(108_000, true))
    }
}
