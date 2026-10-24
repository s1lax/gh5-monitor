package com.lumixmonitor.lumix

/** At most one UI runnable is queued. It takes the newest decoded frame when it runs. */
internal class LatestPresentation<T> {
    private var value: T? = null
    private var scheduled = false

    @Synchronized fun offer(item: T): Boolean {
        value = item
        if (scheduled) return false
        scheduled = true
        return true
    }

    @Synchronized fun take(): T? {
        val result = value
        value = null
        scheduled = false
        return result
    }

    @Synchronized fun clear() {
        value = null
        scheduled = false
    }
}
