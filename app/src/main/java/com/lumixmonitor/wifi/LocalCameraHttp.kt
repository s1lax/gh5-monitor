package com.lumixmonitor.wifi

import java.io.ByteArrayOutputStream
import java.io.InputStream
import java.io.OutputStream

/** Bounded HTTP/1 response reader for sockets bound to the phone's hotspot address. */
internal object LocalCameraHttp {
    private const val LIMIT=2_097_152
    fun body(input:InputStream):String {
        val out=ByteArrayOutputStream();copy(input,out,LIMIT.toLong());return out.toString("UTF-8")
    }
    fun copy(input:InputStream,out:OutputStream,limit:Long,progress:(Long)->Unit={}):Long {
        require(limit in 1..1_073_741_824L);var copied=0L
        fun line():String {
            val out=ByteArrayOutputStream()
            while(true){val b=input.read();require(b>=0){"Truncated camera HTTP header"}
                if(b==10)break
                require(out.size()<8192){"Camera HTTP line too long"};out.write(b)
            }
            return out.toString("US-ASCII").removeSuffix("\r")
        }
        val status=line().split(' ',limit=3)
        require(status.size>=2 && status[0] in listOf("HTTP/1.0","HTTP/1.1") && status[1]=="200"){"Camera HTTP ${status.getOrNull(1) ?: "invalid"}"}
        val headers=mutableMapOf<String,String>();var headerBytes=0;var count=0
        while(true){val l=line();if(l.isEmpty())break
            headerBytes+=l.length;require(++count<=100 && headerBytes<=32768){"Camera HTTP headers too large"}
            require(':' in l){"Invalid camera HTTP header"}
            val name=l.substringBefore(':').trim().lowercase(java.util.Locale.ROOT)
            require(name !in headers || name !in listOf("content-length","transfer-encoding")){"Duplicate camera HTTP framing header"}
            headers[name]=l.substringAfter(':').trim()
        }
        fun exact(length:Long){
            require(length>=0 && length<=limit-copied){ "Camera reply too large" }
            val buffer=ByteArray(8192);var left=length
            while(left>0){val n=input.read(buffer,0,minOf(left,buffer.size.toLong()).toInt());require(n>0){"Truncated camera HTTP body"};out.write(buffer,0,n);left-=n;copied+=n;progress(copied)}
        }
        val encoding=headers["transfer-encoding"]?.lowercase(java.util.Locale.ROOT)
        if(encoding!=null){
            require(encoding=="chunked" && "content-length" !in headers){"Unsupported camera HTTP framing"}
            while(true){val size=line().substringBefore(';').trim().toLongOrNull(16) ?: error("Invalid camera HTTP chunk")
                require(size in 0..limit){ "Camera reply too large" }
                if(size==0L){var trailers=0;while(line().isNotEmpty()){require(++trailers<=32){"Camera HTTP trailers too large"}};break}
                exact(size);require(line().isEmpty()){ "Invalid camera HTTP chunk terminator" }
            }
        }else if("content-length" in headers){
            val length=headers.getValue("content-length").toLongOrNull() ?: error("Invalid camera body length")
            require(length in 0..limit){ "Camera reply too large" };exact(length)
        }else{
            val buffer=ByteArray(8192)
            while(true){val n=input.read(buffer);if(n<0)break;require(copied+n<=limit){"Camera reply too large"};out.write(buffer,0,n);copied+=n;progress(copied)}
        }
        return copied
    }
}

internal fun sameIpv4Subnet(local:ByteArray,target:ByteArray,prefix:Int):Boolean {
    if(local.size!=4 || target.size!=4 || prefix !in 1..32)return false
    for(i in 0..3){val bits=(prefix-i*8).coerceIn(0,8);val mask=(255 shl (8-bits)) and 255
        if((local[i].toInt() and mask)!=(target[i].toInt() and mask))return false
    }
    return true
}
