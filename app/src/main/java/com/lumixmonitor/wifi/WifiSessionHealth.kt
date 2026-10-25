package com.lumixmonitor.wifi

/** Control timeouts do not invalidate a still-live UDP stream. */
internal object WifiSessionHealth {
    fun expired(now:Long,lastFrameAt:Long,lastControlReplyAt:Long):Boolean =
        now-lastFrameAt>=15_000 && now-lastControlReplyAt>=20_000
}
