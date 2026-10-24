package com.lumixmonitor

import com.lumixmonitor.lumix.LumixProp

internal object SettingsRefreshPlan {
    val exposure = setOf(LumixProp.ISO, LumixProp.SHUTTER_SPEED, LumixProp.APERTURE, LumixProp.EXPOSURE)
    private val all = exposure + setOf(LumixProp.CAMERA_MODE_MODE_POS, LumixProp.WHITE_BALANCE,
        LumixProp.PHOTO_STYLE, LumixProp.IMAGE_MODE_ASPECT, LumixProp.IMAGE_MODE_QUALITY,
        LumixProp.AF_AREA_MODE_PARAM, LumixProp.AF_AREA_AREA_PARAM)

    fun forEvent(code: Int): Set<Int> = when (code and 0xFFFFFFF0.toInt()) {
        LumixProp.PHOTO_STYLE -> setOf(LumixProp.PHOTO_STYLE)
        LumixProp.ISO -> setOf(LumixProp.ISO)
        LumixProp.SHUTTER_SPEED -> setOf(LumixProp.SHUTTER_SPEED)
        LumixProp.APERTURE -> setOf(LumixProp.APERTURE)
        LumixProp.WHITE_BALANCE -> setOf(LumixProp.WHITE_BALANCE)
        LumixProp.EXPOSURE -> exposure
        LumixProp.AF_AREA -> setOf(LumixProp.AF_AREA_MODE_PARAM, LumixProp.AF_AREA_AREA_PARAM)
        LumixProp.IMAGE_MODE -> setOf(LumixProp.IMAGE_MODE_ASPECT, LumixProp.IMAGE_MODE_QUALITY)
        LumixProp.CAMERA_MODE -> all
        else -> emptySet()
    }
}
