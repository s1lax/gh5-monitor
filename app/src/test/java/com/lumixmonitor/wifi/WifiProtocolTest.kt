package com.lumixmonitor.wifi

import org.junit.Assert.*
import org.junit.Test

class WifiProtocolTest {
    @Test fun apexLabelsUseNominalCameraValues(){
        assertEquals("f/3.5",WifiSettingsCatalog.label("focal","939/256"))
        assertEquals("1/60",WifiSettingsCatalog.label("shtrspeed","1536/256"))
        assertEquals("1/50",WifiSettingsCatalog.label("shtrspeed","1451/256"))
        assertEquals("1.0s",WifiSettingsCatalog.label("shtrspeed","0/256"))
        assertEquals("—",WifiSettingsCatalog.label("focal","32767/256"))
        assertEquals("—",WifiSettingsCatalog.label("shtrspeed","32767/256"))
    }
    @Test fun localAddressHasExplicitPortAndEncodedParameters(){
        val e=CameraEndpoint.parse("192.168.54.1:8080")
        assertEquals("http://192.168.54.1:8080/cam.cgi?mode=accctrl&value2=GH5+Monitor",e.cgi("accctrl",mapOf("value2" to "GH5 Monitor")))
    }
    @Test fun publicUrlsCredentialsAndBadPortsAreRejected(){
        for(value in listOf("https://192.168.54.1","8.8.8.8","192.168.54.1@evil.com","192.168.54.1:0","192.168.54.256","192.168.54.255","192.168.54.1/cam.cgi")){
            assertTrue(value,runCatching{CameraEndpoint.parse(value)}.isFailure)
        }
    }
    @Test fun http200DoesNotMeanCameraCommandSucceeded(){
        assertFalse(WifiReply.parse("<camrply><result>err_busy</result></camrply>").ok)
        assertFalse(WifiReply.parse("err_reject").ok)
        assertTrue(WifiReply.parse("ok,GH5,remote,0").ok)
        assertFalse(WifiReply.parse("okay").ok)
    }
    @Test fun approvalPendingIsNotAcceptedAsConnected(){
        val reply=WifiReply.parse("ok_under_research_no_msg")
        assertTrue(reply.waitingApproval);assertFalse(reply.ok)
    }
    @Test fun readsStateAndSettingAttributesWithoutInventingValues(){
        val reply=WifiReply.parse("<camrply><result>ok</result><state><rec>on</rec></state><settingvalue focal=\"939/256\" iso=\"auto\" /></camrply>")
        assertEquals("on",reply.values["rec"]);assertEquals("939/256",reply.values["focal"]);assertNull(reply.values["rec_time"])
    }
    @Test fun xmlCannotReadExternalFiles(){
        assertTrue(runCatching{WifiReply.parse("<!DOCTYPE x [<!ENTITY x SYSTEM 'file:///not-allowed'>]><camrply><result>&x;</result></camrply>")}.isFailure)
    }
    @Test fun menuAcceptsAdvertisedConcreteValuesOnly(){
        val c=WifiSettingsCatalog();c.parseMenu(menu())
        assertEquals(listOf("100","200"),c.available("iso").map{it.value})
        assertEquals("ISO 100",c.available("iso").first().label)
        assertTrue(c.available("focal").isEmpty())
        assertTrue(c.available("whitebalance").isEmpty())
        assertTrue(c.options.none{it.type=="poweroff"})
    }
    @Test fun menuOptionIdsStayStableAcrossRefresh(){
        val c=WifiSettingsCatalog();c.parseMenu(menu());val a=c.available("iso").first().id
        c.parseMenu(menu());assertEquals(a,c.available("iso").first().id)
    }
    @Test fun disabledCameraChoicesAreNotWritable(){
        val c=WifiSettingsCatalog();c.parseMenu(menu())
        c.applyCurrent(WifiReply.parse("<camrply><result>ok</result><menuinfo><item id=\"iso-parent\" value=\"100\"/><item id=\"iso200\" enable=\"no\"/></menuinfo></camrply>"))
        assertEquals(listOf("100"),c.available("iso").map{it.value});assertEquals("100",c.current("iso")?.value)
    }
    @Test fun lensDerivedApertureChoicesStayWithinReportedBounds(){
        val c=WifiSettingsCatalog();c.applyLens(WifiReply.parse("ok,2304/256,939/256,3328/256,-1536/256"))
        assertTrue(c.available("focal").isNotEmpty())
        assertTrue(c.available("focal").all{(WifiSettingsCatalog.apex(it.value)!!*256).toInt() in 939..2304})
        assertEquals("939/256",c.available("focal").first().value)
        assertTrue(c.available("shtrspeed").none{it.value.startsWith("16384")})
    }
    @Test fun settingReadbackUsesWifiTokensNotPtpIntegers(){
        val c=WifiSettingsCatalog();c.parseMenu(menu());c.applyValues(mapOf("iso" to "200","sspeed" to "1536/256"))
        assertEquals("200",c.current("iso")?.value);assertEquals("1536/256",c.values["shtrspeed"])
        assertNotEquals(200,c.current("iso")?.id)
    }
    @Test fun packetHeaderAndTrailerAreExcluded(){
        val jpeg=jpeg();val packet=ByteArray(254)+jpeg+byteArrayOf(1,2,3)
        packet[30]=0;packet[31]=222.toByte()
        assertArrayEquals(jpeg,WifiJpeg.extract(packet))
    }
    @Test fun fallbackFindsCompleteJpegWithoutAssumingS5Header(){
        val jpeg=jpeg();assertArrayEquals(jpeg,WifiJpeg.extract(ByteArray(40)+jpeg))
    }
    @Test fun falseEndMarkerInsideAppSegmentIsNotFrameEnd(){
        val jpeg=byteArrayOf(0xff.toByte(),0xd8.toByte(),0xff.toByte(),0xe1.toByte(),0,6,0xff.toByte(),0xd9.toByte(),3,4,0xff.toByte(),0xda.toByte(),0,2,6,7,0xff.toByte(),0,5,0xff.toByte(),0xd9.toByte())
        assertArrayEquals(jpeg,WifiJpeg.extract(jpeg))
    }
    @Test fun truncatedAndUnboundedDatagramsAreDropped(){
        val jpeg=jpeg();assertNull(WifiJpeg.extract(jpeg.copyOf(jpeg.size-1)))
        assertNull(WifiJpeg.extract(ByteArray(65508)))
        assertNull(WifiJpeg.extract(ByteArray(100)))
        assertNull(WifiJpeg.extract(jpeg,jpeg.size+1))
    }
    private fun jpeg()=byteArrayOf(0xff.toByte(),0xd8.toByte(),0xff.toByte(),0xda.toByte(),0,2,10,11,12,0xff.toByte(),0xd9.toByte())
    private fun menu()=WifiReply.parse("""<camrply><result>ok</result><menuset model="GH5">
        <record><menu><item id="iso-parent"><item id="iso100" title_id="t100" cmd_mode="setsetting" cmd_type="iso" cmd_value="100"/><item id="iso200" cmd_mode="setsetting" cmd_type="iso" cmd_value="200"/></item>
        <item cmd_mode="setsetting" cmd_type="focal" cmd_value="#REPLACEME#"/><item cmd_mode="camcmd" cmd_type="poweroff" cmd_value="off"/>
        <item cmd_mode="setsetting" cmd_type="whitebalance" cmd_value="color_temp1" cmd_value2="__value__"/></menu></record>
        <titlelist><language code="en"><title id="t100">ISO 100</title></language></titlelist></menuset></camrply>""")
}
