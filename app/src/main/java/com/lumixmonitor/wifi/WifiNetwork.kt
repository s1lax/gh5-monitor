package com.lumixmonitor.wifi

import android.content.Context
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.net.wifi.WifiManager
import android.os.SystemClock
import com.lumixmonitor.ptp.PtpLog
import java.io.ByteArrayOutputStream
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.HttpURLConnection
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.URL
import java.net.Proxy
import java.net.Socket
import java.net.NetworkInterface
import java.net.MulticastSocket
import java.util.Collections
import java.util.concurrent.atomic.AtomicReference

/** Bind only camera sockets to Wi-Fi; never change the process/default route or ADB. */
class WifiNetwork(private val context:Context,val endpoint:CameraEndpoint) {
    val network:Network?
    val localAddress:InetAddress?
    private val activeHttp=AtomicReference<HttpURLConnection?>()
    private val activeSocket=AtomicReference<Socket?>()
    @Volatile private var closed=false
    init {
        val cm=context.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
        val target=InetAddress.getByName(endpoint.host)
        val candidates=cm.allNetworks.filter{cm.getNetworkCapabilities(it)?.hasTransport(NetworkCapabilities.TRANSPORT_WIFI)==true}
        val hotspot=hotspotAddresses().firstOrNull{(address,prefix)->sameIpv4Subnet(address.address,target.address,prefix)}?.first
        val direct=candidates.firstOrNull{n->cm.getLinkProperties(n)?.routes?.any{it.destination.prefixLength>0 && it.matches(target)}==true}
        network=if(endpoint.host.startsWith("127."))null else direct ?: if(hotspot==null)candidates.firstOrNull() else null
        require(network!=null || hotspot!=null || endpoint.host.startsWith("127.")){"Connect to camera Wi-Fi, a shared router, or enter the camera IP assigned by this phone's hotspot"}
        localAddress=if(network!=null)cm.getLinkProperties(network)?.linkAddresses?.map{it.address}?.firstOrNull{it.address.size==4} else hotspot
    }
    fun socket():DatagramSocket=DatagramSocket(null).apply{
        reuseAddress=true;receiveBufferSize=262144
        network?.bindSocket(this)
        try{bind(InetSocketAddress(localAddress,49199))}catch(_:java.net.BindException){bind(InetSocketAddress(localAddress,0))}
    }
    fun request(mode:String,params:Map<String,String> = emptyMap(),timeoutMs:Int=1800):WifiReply {
        val begin=SystemClock.elapsedRealtime()
        try {
            val reply=WifiReply.parse(get(URL(endpoint.cgi(mode,params)),timeoutMs))
            val elapsed=SystemClock.elapsedRealtime()-begin
            if(mode !in listOf("getstate","getsetting","getinfo") || !reply.ok || elapsed>500)
                PtpLog.add("WIFI HTTP $mode ${params["type"] ?: ""}: ${reply.result} ${elapsed}ms")
            return reply
        }catch(t:Throwable){PtpLog.add("WIFI HTTP $mode ${params["type"] ?: ""}: ${t.javaClass.simpleName} ${t.message}");throw t}
    }
    fun description():String=get(URL("http://${endpoint.host}:60606/Lumix/Server0/ddd"),1000)
    private fun get(url:URL,timeoutMs:Int):String {
        check(!closed){"Wi-Fi session closed"}
        if(network==null && localAddress!=null && !endpoint.host.startsWith("127."))return localRequest(url,timeoutMs)
        val conn=(network?.openConnection(url,Proxy.NO_PROXY) ?: url.openConnection(Proxy.NO_PROXY)) as HttpURLConnection
        activeHttp.set(conn)
        try {
            conn.connectTimeout=timeoutMs;conn.readTimeout=timeoutMs;conn.instanceFollowRedirects=false
            conn.useCaches=false;conn.setRequestProperty("User-Agent","Panasonic Android/1.0 (GH5 Monitor)")
            conn.setRequestProperty("Accept","text/xml, text/plain, */*")
            check(!closed){"Wi-Fi session closed"}
            require(conn.responseCode==200){"Camera HTTP ${conn.responseCode}"}
            require(conn.contentLength<=2_097_152){"Camera reply too large"}
            val bytes=ByteArrayOutputStream()
            conn.inputStream.use{stream->val b=ByteArray(8192);while(true){val n=stream.read(b);if(n<0)break;require(bytes.size()+n<=2_097_152){"Camera reply too large"};bytes.write(b,0,n)}}
            return bytes.toString("UTF-8")
        }finally{activeHttp.compareAndSet(conn,null);conn.disconnect()}
    }
    private fun localRequest(url:URL,timeoutMs:Int):String {
        val socket=Socket();activeSocket.set(socket)
        try{
            socket.soTimeout=timeoutMs;socket.tcpNoDelay=true
            socket.bind(InetSocketAddress(localAddress,0));check(!closed){"Wi-Fi session closed"}
            socket.connect(InetSocketAddress(url.host,if(url.port>0)url.port else 80),timeoutMs)
            val request="GET ${url.file} HTTP/1.1\r\nHost: ${url.authority}\r\nUser-Agent: Panasonic Android/1.0 (GH5 Monitor)\r\nAccept: text/xml, text/plain, */*\r\nConnection: close\r\n\r\n"
            socket.getOutputStream().apply{write(request.toByteArray(Charsets.US_ASCII));flush()}
            return LocalCameraHttp.body(socket.getInputStream())
        }finally{activeSocket.compareAndSet(socket,null);socket.close()}
    }
    fun close(){closed=true;activeHttp.getAndSet(null)?.disconnect();runCatching{activeSocket.getAndSet(null)?.close()}}
}

/** Hotspot interfaces do not necessarily have a ConnectivityManager Network. */
internal fun hotspotAddresses():List<Pair<InetAddress,Int>> = runCatching{
    Collections.list(NetworkInterface.getNetworkInterfaces()).filter{it.isUp && !it.isLoopback &&
        listOf("wlan","ap","swlan","softap").any{prefix->it.name.startsWith(prefix)}}
        .flatMap{it.interfaceAddresses}.filter{it.address.address.size==4 && it.address.isSiteLocalAddress && it.networkPrefixLength in 1..32}
        .map{it.address to it.networkPrefixLength.toInt()}
}.getOrDefault(emptyList())

data class WifiDiscoveredCamera(val host:String,val name:String)

object WifiDiscovery {
    /** SSDP only, no subnet scanning; filter reply headers for Panasonic/LUMIX. */
    fun find(context:Context):List<WifiDiscoveredCamera> {
        val cm=context.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
        val manager=context.applicationContext.getSystemService(Context.WIFI_SERVICE) as WifiManager
        val multicast=manager.createMulticastLock("gh5-discovery").apply{setReferenceCounted(false);acquire()}
        val found=linkedMapOf<String,WifiDiscoveredCamera>()
        try {
            val wifiLinks=cm.allNetworks.filter{cm.getNetworkCapabilities(it)?.hasTransport(NetworkCapabilities.TRANSPORT_WIFI)==true}.mapNotNull{network->
                cm.getLinkProperties(network)?.linkAddresses?.firstOrNull{it.address.address.size==4}?.address?.let{network to it}
            }
            val links:List<Pair<Network?,InetAddress>> = (wifiLinks+hotspotAddresses().map{null to it.first}).distinctBy{it.second}.take(3)
            links.forEach{(network,local)->
                val receiver=if(network==null)MulticastSocket(null).apply{networkInterface=NetworkInterface.getByInetAddress(local)} else DatagramSocket(null)
                receiver.use{socket->
                    network?.bindSocket(socket);socket.bind(InetSocketAddress(local,0));socket.soTimeout=250
                    val request=("M-SEARCH * HTTP/1.1\r\nHOST: 239.255.255.250:1900\r\nMAN: \"ssdp:discover\"\r\nMX: 1\r\nST: urn:schemas-upnp-org:device:MediaServer:1\r\n\r\n").toByteArray()
                    socket.send(DatagramPacket(request,request.size,InetAddress.getByName("239.255.255.250"),1900))
                    val until=System.nanoTime()+2_000_000_000L;val buffer=ByteArray(8192)
                    while(System.nanoTime()<until){
                        try{val packet=DatagramPacket(buffer,buffer.size);socket.receive(packet)
                            val host=packet.address.hostAddress ?: continue
                            val text=String(packet.data,0,packet.length,Charsets.US_ASCII)
                            if(text.contains("panasonic",true) || text.contains("lumix",true))found[host]=WifiDiscoveredCamera(host,"LUMIX · $host")
                        }catch(_:java.net.SocketTimeoutException){}
                    }
                }
            }
        }finally{if(multicast.isHeld)multicast.release()}
        return found.values.toList()
    }
}
