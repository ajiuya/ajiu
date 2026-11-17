package com.aicode.feature.player.data

import com.aicode.core.net.AppProxy
import com.aicode.feature.player.domain.PlayerRequestCodec
import okhttp3.Call
import okhttp3.Dns
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import java.io.IOException
import java.net.InetAddress
import java.net.Proxy
import java.util.concurrent.TimeUnit

internal class PlayerUpstream(
    private val client: OkHttpClient = OkHttpClient.Builder()
        .proxySelector(AppProxy.selector).proxyAuthenticator(AppProxy.okHttpAuthenticator)
        .followRedirects(false).followSslRedirects(false)
        .connectTimeout(15, TimeUnit.SECONDS).readTimeout(30, TimeUnit.SECONDS)
        .writeTimeout(15, TimeUnit.SECONDS).callTimeout(120, TimeUnit.SECONDS)
        .connectionPool(okhttp3.ConnectionPool(4, 30, TimeUnit.SECONDS)).build(),
    private val dns: Dns = Dns.SYSTEM
) {
    fun open(resources: PlayerResources, url: String, method: String = "GET", referer: String = "",
        headers: Map<String, String> = emptyMap(), streaming: Boolean = false): PlayerExchange {
        var target = remoteUrl(url)
        val safeReferer = referer.takeIf { it.isNotBlank() }?.let { remoteUrl(it).toString() }
        repeat(8) {
            resources.checkOpen()
            val proxies = client.proxy?.let(::listOf) ?: client.proxySelector.select(target.toUri())
            if (proxies.isNullOrEmpty() || proxies.any { it.type() != Proxy.Type.DIRECT })
                throw PlayerHttpException(403, "Proxy destination cannot be pinned")
            val addresses = dns.lookup(target.host)
            if (addresses.isEmpty() || addresses.any { !publicAddress(it) }) throw PlayerHttpException(403, "Private upstream address")
            // Proxy-side DNS cannot be pinned; retain the direct route approved above.
            val pinned = client.newBuilder().proxy(Proxy.NO_PROXY).followRedirects(false).followSslRedirects(false)
                .callTimeout(if (streaming) 0L else 120L, TimeUnit.SECONDS)
                .dns(PinnedDns(target.host, addresses, dns)).build()
            val request = Request.Builder().url(target).method(method, null)
                .header("User-Agent", "Ajiu-Player/1.0 (Android)")
                .header("Accept-Encoding", headers["accept-encoding"] ?: "identity")
                .apply { safeReferer?.let { header("Referer", it) }; headers.forEach { (key, value) -> header(key, value) } }.build()
            val call = pinned.newCall(request)
            resources.add(call)
            val response = try { call.execute() } catch (e: Exception) { resources.remove(call); throw e }
            if (response.code !in setOf(301, 302, 303, 307, 308)) return PlayerExchange(response, call, resources)
            val location = response.header("Location") ?: return PlayerExchange(response, call, resources)
            val next = response.request.url.resolve(location)
            response.close(); resources.remove(call)
            target = remoteUrl(next?.toString() ?: throw PlayerHttpException(502, "Invalid redirect"))
        }
        throw PlayerHttpException(502, "Too many redirects")
    }
}

private data class PinnedDns(val host: String, val addresses: List<InetAddress>, val delegate: Dns) : Dns {
    override fun lookup(hostname: String): List<InetAddress> = if (hostname.equals(host, true)) addresses else delegate.lookup(hostname)
}
internal class PlayerExchange(val response: Response, private val call: Call, private val resources: PlayerResources) : AutoCloseable {
    override fun close() { response.close(); resources.remove(call) }
}
internal fun remoteUrl(raw: String): HttpUrl {
    val url = raw.toHttpUrlOrNull() ?: throw PlayerHttpException(400, "HTTP(S) URL required")
    if (url.scheme !in setOf("http", "https") || url.username.isNotEmpty() || url.password.isNotEmpty() || '\\' in raw || raw.any { it.code < 32 })
        throw PlayerHttpException(400, "Invalid upstream URL")
    val host = url.host.lowercase().trimEnd('.')
    if (!host.contains('.') && ':' !in host || host == "localhost" ||
        listOf(".localhost", ".local", ".internal", ".test", ".invalid").any(host::endsWith)) throw PlayerHttpException(403, "Private upstream hostname")
    if (':' in host || Regex("[0-9.]+").matches(host)) {
        val literal = runCatching { InetAddress.getByName(host) }.getOrNull() ?: throw PlayerHttpException(400, "Invalid upstream address")
        if (!publicAddress(literal)) throw PlayerHttpException(403, "Private upstream address")
    }
    return url
}
internal fun publicAddress(address: InetAddress): Boolean = PlayerRequestCodec.publicAddress(address)

internal class PlayerResources : AutoCloseable {
    private var closed = false
    private val calls = mutableSetOf<Call>()
    private val connections = mutableSetOf<AutoCloseable>()
    @Synchronized fun checkOpen() { if (closed) throw IOException("Player session closed") }
    @Synchronized fun add(call: Call) { if (closed) { call.cancel(); throw IOException("Player session closed") }; calls += call }
    @Synchronized fun remove(call: Call) { calls -= call }
    @Synchronized fun add(connection: AutoCloseable) { if (closed) { connection.close(); throw IOException("Player session closed") }; connections += connection }
    @Synchronized fun remove(connection: AutoCloseable) { connections -= connection }
    override fun close() {
        val snapshot = synchronized(this) {
            if (closed) return
            closed = true
            (calls.toList() to connections.toList()).also { calls.clear(); connections.clear() }
        }
        snapshot.first.forEach { it.cancel() }
        snapshot.second.forEach { runCatching { it.close() } }
    }
}
internal fun java.io.InputStream.readLimited(limit: Int = 4 * 1024 * 1024): ByteArray {
    val output = java.io.ByteArrayOutputStream()
    val buffer = ByteArray(8192)
    while (true) {
        val count = read(buffer)
        if (count < 0) break
        if (output.size() + count > limit) throw PlayerHttpException(502, "Upstream document too large")
        output.write(buffer, 0, count)
    }
    return output.toByteArray()
}
