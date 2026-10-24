package com.lumixmonitor.monitor

import android.content.SharedPreferences
import com.lumixmonitor.MonitorTools

object ToolPreferences {
    fun load(p:SharedPreferences)=MonitorTools(
        showThirds=p.getBoolean("thirds",true),showCenterMarker=p.getBoolean("center",false),
        showLevel=p.getBoolean("level",true),showHistogram=p.getBoolean("histogram",false),
        focusPeaking=p.getBoolean("peaking",false),zebra=p.getBoolean("zebra",false),
        zebraThreshold=p.getFloat("zebraThreshold",0.95f).coerceIn(0.5f,1f),
        falseColor=p.getBoolean("falseColor",false),showFramelines=p.getBoolean("framelines",false),
        desqueeze=p.getFloat("desqueeze",1f).coerceIn(1f,2f),
        showWaveform=p.getBoolean("waveform",false),showVectorscope=p.getBoolean("vectorscope",false),
        rgbHistogram=p.getBoolean("rgbHistogram",false),
        peakingThreshold=p.getFloat("peakingThreshold",0.1f).coerceIn(0.02f,0.3f),
        guideAspect=p.getFloat("guideAspect",2.39f).coerceIn(0.5f,3f),
        safeArea=p.getFloat("safeArea",0.9f).coerceIn(0.7f,1f),
        lutEnabled=p.getBoolean("lutEnabled",false),lutAmount=p.getFloat("lutAmount",1f).coerceIn(0f,1f))

    fun save(p:SharedPreferences,t:MonitorTools){p.edit()
        .putBoolean("thirds",t.showThirds).putBoolean("center",t.showCenterMarker).putBoolean("level",t.showLevel)
        .putBoolean("histogram",t.showHistogram).putBoolean("peaking",t.focusPeaking).putBoolean("zebra",t.zebra)
        .putFloat("zebraThreshold",t.zebraThreshold).putBoolean("falseColor",t.falseColor).putBoolean("framelines",t.showFramelines)
        .putFloat("desqueeze",t.desqueeze).putBoolean("waveform",t.showWaveform).putBoolean("vectorscope",t.showVectorscope)
        .putBoolean("rgbHistogram",t.rgbHistogram).putFloat("peakingThreshold",t.peakingThreshold)
        .putFloat("guideAspect",t.guideAspect).putFloat("safeArea",t.safeArea).putBoolean("lutEnabled",t.lutEnabled)
        .putFloat("lutAmount",t.lutAmount).apply()}
}
