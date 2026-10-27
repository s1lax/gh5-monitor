package com.lumixmonitor.wifi

import org.junit.Assert.*
import org.junit.Test

class WifiShootingModeTest {
    private fun menu(mode:String,detail:String?=null)=WifiReply.parse("""<camrply><result>ok</result><menuinfo><item id="menu_item_id_recmode" value="$mode"/>${detail?.let{"""<item id="menu_item_id_recmode_$mode" value="$it"/>"""}.orEmpty()}</menuinfo></camrply>""")
    @Test fun photoAndVideoManualAreDifferentReadouts(){
        assertEquals("M",WifiShootingMode.label("manual_exposure"))
        assertEquals("Видео M",WifiShootingMode.label("creative_movie","manual_exposure"))
        for((raw,expected) in listOf("program_ae" to "P","aperture_ae" to "A","shutter_ae" to "S")){
            assertEquals(expected,WifiShootingMode.label(raw));assertEquals("Видео $expected",WifiShootingMode.label("creative_movie",raw))
        }
    }
    @Test fun actualGh5CreativeMovieMenuIncludesSeparateExposureProgram(){
        val c=WifiSettingsCatalog();c.applyCurrent(menu("creative_movie","manual_exposure"))
        assertEquals("creative_movie",c.values["recmode"]);assertEquals("manual_exposure",c.values["recmode_detail"])
    }
    @Test fun modeChangeCannotReuseThePreviousMovieExposure(){
        val c=WifiSettingsCatalog();c.applyCurrent(menu("creative_movie","manual_exposure"));c.applyCurrent(menu("manual_exposure"));c.applyCurrent(menu("creative_movie"))
        assertEquals("",c.values["recmode_detail"]);assertEquals("Видео",WifiShootingMode.label(c.values["recmode"],c.values["recmode_detail"]))
    }
    @Test fun inactiveMovieEntryDoesNotOverrideSelectedPhotoMode(){
        val r=WifiReply.parse("""<camrply><result>ok</result><item id="menu_item_id_recmode_creative_movie" value="manual_exposure"/><item id="menu_item_id_recmode" value="aperture_ae"/></camrply>""")
        val f=WifiShootingMode.fields(r);assertEquals("A",WifiShootingMode.label(f["recmode"],f["recmode_detail"]))
        assertEquals("—",WifiShootingMode.label("unreported"));assertEquals("Видео",WifiShootingMode.label("creative_movie","unknown"))
    }
    @Test fun customAndAutomaticDetailsArePreserved(){
        assertEquals("C3-2",WifiShootingMode.label("c3","c3_2"));assertEquals("C3",WifiShootingMode.label("c3"))
        assertEquals("iA+",WifiShootingMode.label("ia","iaplus"));assertEquals("iA",WifiShootingMode.label("ia","ia"))
        assertTrue(WifiShootingMode.fields(WifiReply.parse("err_reject")).isEmpty())
    }
}
