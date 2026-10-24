package com.lumixmonitor.monitor

/** Viewing correction only. Never applied to USB data, scopes or saved previews. */
data class DisplayCalibration(
    val enabled: Boolean = false,
    val ev: Float = 0f,
    val gamma: Float = 1f,
    val contrast: Float = 1f,
) {
    val isAdjusted: Boolean get() = enabled && (ev != 0f || gamma != 1f || contrast != 1f)
}
