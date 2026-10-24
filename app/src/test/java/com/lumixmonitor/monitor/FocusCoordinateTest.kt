package com.lumixmonitor.monitor

import org.junit.Assert.*
import org.junit.Test

class FocusCoordinateTest {
    @Test fun tapCoordinatesFollowTheSameRotationAsTheShader(){
        assertEquals(.7f,ImageGeometry.sensorPoint(.2f,.7f,1).first,.0001f)
        assertEquals(.8f,ImageGeometry.sensorPoint(.2f,.7f,1).second,.0001f)
        for(q in 0..3)for(p in listOf(0f to 0f,1f to 1f,.2f to .7f)){
            val sensor=ImageGeometry.sensorPoint(p.first,p.second,q)
            val screen=ImageGeometry.screenPoint(sensor.first,sensor.second,q)
            assertEquals(p.first,screen.first,.0001f);assertEquals(p.second,screen.second,.0001f)
        }
    }
}
