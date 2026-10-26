package com.lumixmonitor.wifi

import org.junit.Assert.*
import org.junit.Test
import java.io.ByteArrayOutputStream

class WifiCardFilesTest {
    private val host="192.168.54.1"
    private fun soap(didl:String)= "<s:Envelope xmlns:s=\"http://schemas.xmlsoap.org/soap/envelope/\"><s:Body><u:BrowseResponse xmlns:u=\"urn:schemas-upnp-org:service:ContentDirectory:1\"><Result>${didl.replace("&","&amp;").replace("<","&lt;").replace(">","&gt;")}</Result><NumberReturned>1</NumberReturned><TotalMatches>42</TotalMatches></u:BrowseResponse></s:Body></s:Envelope>"
    @Test fun originalIsDistinctFromPreviewAndTitlesAreDecodedOnce(){
        val page=WifiCardFiles.parse(soap("""<DIDL-Lite xmlns:dc="urn:dc"><item id="abc"><dc:title>PANA0001 &amp; test</dc:title><res protocolInfo="http-get:*:image/jpeg:*" size="123456" resolution="5184x3888">http://$host:50001/DO0001.JPG</res><res protocolInfo="http-get:*:image/jpeg:*">http://$host:50001/DL0001.JPG</res></item></DIDL-Lite>"""),host)
        val f=page.items.single();assertEquals(42,page.total);assertEquals("PANA0001 & test.JPG",f.name)
        assertEquals(123456L,f.original?.size);assertTrue(f.preview!!.name.startsWith("DL"));assertFalse(f.preview!!.original)
    }
    @Test fun cameraCannotRedirectDownloadsToOtherHostsOrPaths(){
        for(url in listOf("http://evil.com:50001/DO.JPG","https://$host:50001/DO.JPG","http://$host:80/DO.JPG","http://user@$host:50001/DO.JPG","http://$host:50001/a/%2e%2e/DO.JPG","http://$host:50001/DO.JPG?q=x","http://$host:50001/DO.JPG#x"))assertTrue(url,runCatching{WifiCardFiles.resource(url,host)}.isFailure)
        val f=WifiCardFiles.parse(soap("<DIDL-Lite><item id=\"x\"><res>http://evil.com:50001/DO.JPG</res></item></DIDL-Lite>"),host).items.single()
        assertNull(f.original);assertNull(f.preview)
    }
    @Test fun metadataErrorsAndXmlEntitiesAreRejected(){
        assertTrue(runCatching{WifiCardFiles.parse("<Envelope><errorCode>701</errorCode></Envelope>",host)}.isFailure)
        assertTrue(runCatching{WifiCardFiles.parse(soap("<!DOCTYPE x [<!ENTITY e SYSTEM 'file:///secret'>]><DIDL-Lite/>"),host)}.isFailure)
        assertTrue(WifiCardFiles.browseBody("a<&",0).contains("a&lt;&amp;"))
        assertTrue(runCatching{WifiCardFiles.browseBody("0",-1)}.isFailure)
    }
    @Test fun binaryDownloadPreservesBytesAndRejectsOverflowOrTruncation(){
        val bytes=byteArrayOf(0,-1,10,13,0,127)
        val header="HTTP/1.1 200 OK\r\nContent-Length: ${bytes.size}\r\n\r\n".toByteArray()
        val output=ByteArrayOutputStream();val progress=mutableListOf<Long>()
        assertEquals(6L,LocalCameraHttp.copy((header+bytes).inputStream(),output,6){progress+=it})
        assertArrayEquals(bytes,output.toByteArray());assertEquals(6L,progress.last())
        assertTrue(runCatching{LocalCameraHttp.copy((header+bytes).inputStream(),ByteArrayOutputStream(),5)}.isFailure)
        assertTrue(runCatching{LocalCameraHttp.copy((header+bytes.dropLast(1)).inputStream(),ByteArrayOutputStream(),6)}.isFailure)
    }
}
