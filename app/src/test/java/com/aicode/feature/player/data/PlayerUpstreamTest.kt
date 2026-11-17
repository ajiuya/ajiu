package com.aicode.feature.player.data

import okhttp3.Call
import okhttp3.Dns
import okhttp3.EventListener
import okhttp3.OkHttpClient
import okhttp3.Protocol
import okhttp3.Response
import okhttp3.ResponseBody.Companion.toResponseBody
import org.junit.Assert.*
import org.junit.Test
import java.io.IOException
import java.net.*
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import javax.net.SocketFactory

class PlayerUpstreamTest {
    private fun selector(proxies: List<Proxy>, selected: (URI) -> Unit = {}) = object : ProxySelector() {
        override fun select(uri: URI): List<Proxy> { selected(uri); return proxies }
        override fun connectFailed(uri: URI?, address: SocketAddress?, failure: IOException?) = Unit
    }
    private fun publicDns() = object : Dns {
        override fun lookup(hostname: String) = listOf(InetAddress.getByName("8.8.8.8"))
    }

    @Test fun httpAndSocksSelectorsFailClosedBeforeAnyUpstreamExchange() {
        for (type in listOf(Proxy.Type.HTTP, Proxy.Type.SOCKS)) {
            var exchanges = 0
            val proxy = Proxy(type, InetSocketAddress.createUnresolved("proxy.example", 8080))
            val client = OkHttpClient.Builder().proxySelector(selector(listOf(proxy))).addInterceptor {
                exchanges++
                error("Indirect proxy must be rejected before executing a call")
            }.build()
            PlayerResources().use { resources ->
                assertEquals(type.name, 403, assertThrows(PlayerHttpException::class.java) {
                    PlayerUpstream(client, publicDns()).open(resources, "https://cdn.example/movie.mp4")
                }.status)
            }
            assertEquals(0, exchanges)
        }
    }

    @Test fun explicitProxyAndMixedProxyChoicesCannotFallBackToDirect() {
        val proxy = Proxy(Proxy.Type.HTTP, InetSocketAddress.createUnresolved("proxy.example", 8080))
        for (explicit in listOf(false, true)) {
            var exchanges = 0
            val builder = OkHttpClient.Builder().addInterceptor { exchanges++; error("Unexpected exchange") }
            val client = if (explicit) builder.proxy(proxy).build() else builder.proxySelector(selector(listOf(Proxy.NO_PROXY, proxy))).build()
            PlayerResources().use { resources ->
                assertEquals(403, assertThrows(PlayerHttpException::class.java) { PlayerUpstream(client, publicDns()).open(resources, "https://cdn.example/a.mp4") }.status)
            }
            assertEquals(0, exchanges)
        }
    }

    @Test fun directConnectionUsesTheValidatedDnsListWithoutResolvingAgain() {
        val listener = ServerSocket(0, 1, InetAddress.getByName("127.0.0.1"))
        val pool = Executors.newSingleThreadExecutor()
        val addresses = listOf(InetAddress.getByName("8.8.8.8"), InetAddress.getByName("1.1.1.1"))
        val dnsResults = mutableListOf<List<InetAddress>>()
        val endpoints = mutableListOf<InetSocketAddress>()
        var validations = 0
        val client = OkHttpClient.Builder().proxySelector(selector(listOf(Proxy.NO_PROXY)))
            .dns(object : Dns { override fun lookup(hostname: String): List<InetAddress> = error("Validated target must not be resolved by the original client") })
            .socketFactory(object : SocketFactory() {
                override fun createSocket(): Socket = object : Socket() {
                    override fun connect(endpoint: SocketAddress, timeout: Int) {
                        endpoints += endpoint as InetSocketAddress
                        super.connect(InetSocketAddress(InetAddress.getByName("127.0.0.1"), listener.localPort), timeout)
                    }
                }
                override fun createSocket(host: String, port: Int): Socket = error("Expected deferred connect")
                override fun createSocket(host: String, port: Int, localHost: InetAddress, localPort: Int): Socket = error("Expected deferred connect")
                override fun createSocket(host: InetAddress, port: Int): Socket = error("Expected deferred connect")
                override fun createSocket(host: InetAddress, port: Int, localHost: InetAddress, localPort: Int): Socket = error("Expected deferred connect")
            }).eventListener(object : EventListener() {
                override fun dnsEnd(call: Call, domainName: String, inetAddressList: List<InetAddress>) { dnsResults += inetAddressList.toList() }
            }).build()
        val serving = pool.submit {
            listener.accept().use { socket ->
                socket.soTimeout = 3000
                val request = PlayerHttpRequest.read(socket.getInputStream())
                assertEquals("/movie.mp4", request.rawPath)
                PlayerHttpResponse(socket.getOutputStream(), false).text(200, "media")
            }
        }
        try {
            PlayerResources().use { resources ->
                PlayerUpstream(client, object : Dns {
                    override fun lookup(hostname: String): List<InetAddress> { validations++; return addresses }
                }).open(resources, "http://cdn.example/movie.mp4").use { exchange ->
                    assertEquals(200, exchange.response.code)
                    assertEquals("media", exchange.response.body!!.string())
                }
            }
            serving.get(2, TimeUnit.SECONDS)
            assertEquals(1, validations)
            assertEquals(listOf(addresses), dnsResults)
            assertEquals(addresses.first(), endpoints.single().address)
        } finally { listener.close(); pool.shutdownNow(); client.connectionPool.evictAll() }
    }

    @Test fun redirectRechecksProxyPolicyBeforeContactingTheNextHost() {
        var exchanges = 0
        val selections = mutableListOf<String>()
        val proxy = Proxy(Proxy.Type.SOCKS, InetSocketAddress.createUnresolved("proxy.example", 1080))
        val proxySelector = object : ProxySelector() {
            override fun select(uri: URI): List<Proxy> {
                selections += uri.host
                return if (uri.host == "source.example") listOf(Proxy.NO_PROXY) else listOf(proxy)
            }
            override fun connectFailed(uri: URI?, address: SocketAddress?, failure: IOException?) = Unit
        }
        val client = OkHttpClient.Builder().proxySelector(proxySelector).addInterceptor {
            exchanges++
            Response.Builder().request(it.request()).protocol(Protocol.HTTP_1_1).code(302).message("fixture")
                .header("Location", "https://other.example/final.mp4").body(byteArrayOf().toResponseBody()).build()
        }.build()
        PlayerResources().use { resources ->
            assertEquals(403, assertThrows(PlayerHttpException::class.java) { PlayerUpstream(client, publicDns()).open(resources, "https://source.example/start.mp4") }.status)
        }
        assertEquals(1, exchanges)
        assertEquals(listOf("source.example", "other.example"), selections)
    }
}
