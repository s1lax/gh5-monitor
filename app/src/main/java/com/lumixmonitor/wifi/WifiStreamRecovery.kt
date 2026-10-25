package com.lumixmonitor.wifi

/** A fresh start is not a stale stream. Never duplicate START while GH5 is settling. */
internal class WifiStreamRecovery(startedAtMs:Long) {
    private var startedAt=startedAtMs
    private var lastRequest=startedAtMs
    private var delay=5000L
    private var attempts=0
    fun started(now:Long){startedAt=now;lastRequest=now;delay=5000;attempts=0}
    fun shouldRestart(now:Long,lastFrameAt:Long,shooting:Boolean,recording:Boolean?,cameraBusy:Boolean):Boolean {
        if(lastFrameAt>0 && now-lastFrameAt<3500){delay=5000;attempts=0;return false}
        if(!shooting || recording!=false || cameraBusy || attempts>=3)return false
        if(now-maxOf(startedAt,lastFrameAt)<5000 || now-lastRequest<delay)return false
        lastRequest=now;delay=(delay*2).coerceAtMost(60_000);attempts++
        return true
    }
}
