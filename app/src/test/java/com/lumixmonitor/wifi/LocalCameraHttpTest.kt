package com.lumixmonitor.wifi

import org.junit.Assert.*
import org.junit.Test
import java.net.InetAddress

class LocalCameraHttpTest {
    private fun body(text:String)=LocalCameraHttp.body(text.byteInputStream())
    @Test fun fixedLengthAndCloseDelimitedCameraReplies(){
        assertEquals("ok",body("HTTP/1.1 200 OK\r\nContent-Length: 2\r\n\r\nokignored"))
        assertEquals("ok",body("HTTP/1.0 200 OK\r\n\r\nok"))
    }
    @Test fun chunkedRepliesAndExtensions(){
        assertEquals("ok,GH5",body("HTTP/1.1 200 OK\r\nTransfer-Encoding: chunked\r\n\r\n2;x=y\r\nok\r\n4\r\n,GH5\r\n0\r\n\r\n"))
    }
    @Test fun truncationAndHttpErrorsAreNotSuccess(){
        for(text in listOf("HTTP/1.1 500 Error\r\n\r\nok","HTTP/1.1 200 OK\r\nContent-Length: 9\r\n\r\nok","HTTP/1.1 200 OK\r\nTransfer-Encoding: chunked\r\n\r\n8\r\nok"))assertTrue(runCatching{body(text)}.isFailure)
    }
    @Test fun framingAndSizeLimits(){
        for(headers in listOf("Content-Length: 9999999999","Content-Length: -1","Content-Length: 2\r\nContent-Length: 2","Transfer-Encoding: gzip","Transfer-Encoding: chunked\r\nContent-Length: 2"))assertTrue(runCatching{body("HTTP/1.1 200 OK\r\n$headers\r\n\r\nok")}.isFailure)
        assertTrue(runCatching{body("HTTP/1.1 200 OK\r\nX: "+"x".repeat(8192))}.isFailure)
    }
    @Test fun hotspotRouteMustActuallyContainTheCamera(){
        fun ip(v:String)=InetAddress.getByName(v).address
        assertTrue(sameIpv4Subnet(ip("192.168.43.1"),ip("192.168.43.212"),24))
        assertFalse(sameIpv4Subnet(ip("192.168.1.39"),ip("192.168.43.212"),24))
        assertFalse(sameIpv4Subnet(ip("192.168.43.1"),ip("192.168.43.212"),0))
        assertTrue(sameIpv4Subnet(ip("172.20.10.1"),ip("172.20.10.6"),28))
    }
}
