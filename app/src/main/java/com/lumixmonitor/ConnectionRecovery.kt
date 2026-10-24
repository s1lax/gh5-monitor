package com.lumixmonitor

/** Retry timing uses elapsed time, so a change to the phone's clock cannot stall recovery. */
internal class ConnectionRecovery {
    private var failures = 0
    private var nextAttemptAt = 0L
    private var lastFrameAt = 0L
    private var blockedDevice: String? = null

    @get:Synchronized val requiresUsbChange: Boolean get() = blockedDevice != null

    @Synchronized fun blockUntilUsbChanges(deviceName: String) { blockedDevice = deviceName }

    @Synchronized fun observeDevice(deviceName: String?) {
        if (blockedDevice != null && blockedDevice != deviceName) {
            blockedDevice = null
            failures = 0
            nextAttemptAt = 0
        }
    }

    @Synchronized fun canAttempt(now: Long) = blockedDevice == null && now >= nextAttemptAt

    @Synchronized fun attemptStarted(now: Long) {
        val delay = (1_500L shl failures.coerceAtMost(3)).coerceAtMost(12_000L)
        failures++
        nextAttemptAt = now + delay
    }

    @Synchronized fun opened(now: Long) {
        lastFrameAt = now
    }

    @Synchronized fun frameReceived(now: Long) {
        lastFrameAt = now
        failures = 0
        nextAttemptAt = 0L
    }

    @Synchronized fun streamFailed(now: Long) {
        // Give the body time to finish a lens change or leave playback.
        nextAttemptAt = maxOf(nextAttemptAt, now + 1_500L)
    }

    @Synchronized fun needsRecovery(now: Long, streamRunning: Boolean): Boolean =
        blockedDevice == null && now >= nextAttemptAt && (!streamRunning || now - lastFrameAt >= 8_000L)
}
