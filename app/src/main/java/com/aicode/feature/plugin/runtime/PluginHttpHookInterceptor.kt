package com.aicode.feature.plugin.runtime

import com.aicode.feature.plugin.domain.PluginJson
import java.io.IOException
import java.nio.ByteBuffer
import java.nio.charset.CodingErrorAction
import javax.inject.Inject
import javax.inject.Provider
import javax.inject.Singleton
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.serialization.json.JsonObject
import okhttp3.Headers
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.Interceptor
import okhttp3.Request
import retrofit2.Invocation
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.Response
import okio.Buffer
import okio.Sink
import okio.Timeout
import okio.buffer

@Singleton
class PluginHttpHookInterceptor @Inject constructor(
    private val dispatcher: Provider<PluginHookDispatcher>
) : Interceptor {
    override fun intercept(chain: Interceptor.Chain): Response {
        val request = chain.request()
        val body = request.body
        if (request.method != "POST" || body == null || body.isOneShot() || body.isDuplex() || !isProviderRequest(request)) {
            return chain.proceed(request)
        }
        val hooks = dispatcher.get()
        if (!hooks.hasHttpHooks()) return chain.proceed(request)
        val media = body.contentType()
        if (media?.subtype?.let { it == "json" || it.endsWith("+json") } != true) return chain.proceed(request)
        val contentLength = try { body.contentLength() } catch (_: Exception) { return chain.proceed(request) }
        if (contentLength > PluginHookTransform.MAX_HTTP_BODY_BYTES) return chain.proceed(request)
        val text = try {
            val buffer = Buffer()
            val sink = object : Sink {
                override fun write(source: Buffer, byteCount: Long) {
                    if (buffer.size + byteCount > PluginHookTransform.MAX_HTTP_BODY_BYTES) throw IOException("HTTP hook body limit")
                    buffer.write(source, byteCount)
                }
                override fun flush() = Unit
                override fun close() = Unit
                override fun timeout() = Timeout.NONE
            }.buffer()
            sink.use { body.writeTo(it) }
            Charsets.UTF_8.newDecoder().onMalformedInput(CodingErrorAction.REPORT)
                .onUnmappableCharacter(CodingErrorAction.REPORT).decode(ByteBuffer.wrap(buffer.readByteArray())).toString()
                .also { if (PluginJson.parse(it, maxDepth = 128) !is JsonObject) throw IOException("HTTP hook JSON object required") }
        } catch (_: Exception) { return chain.proceed(request) }
        val call = chain.call()
        if (call.isCanceled()) throw IOException("Canceled")
        val originalHeaders = request.headers.toMultimap().mapValues { (_, values) -> values.joinToString(", ") }
        val result = try {
            runBlocking {
                val transform = async(Dispatchers.IO) {
                    withTimeoutOrNull(2500) { hooks.beforeHttpRequest(request.url.toString(), request.method, originalHeaders, text) }
                        ?: HttpRequestHookResult()
                }
                val cancellation = launch {
                    while (transform.isActive) {
                        if (call.isCanceled()) { transform.cancel(); break }
                        delay(20)
                    }
                }
                try { transform.await() } finally { cancellation.cancelAndJoin() }
            }
        } catch (_: Exception) {
            if (call.isCanceled()) throw IOException("Canceled")
            return chain.proceed(request)
        }
        if (call.isCanceled()) throw IOException("Canceled")
        if (result.url == null && result.headers == null && result.body == null) return chain.proceed(request)
        val transformed = try {
            result.checkCurrent()
            val target = result.url?.toHttpUrlOrNull() ?: request.url
            require((result.url == null || result.url.toHttpUrlOrNull() != null) && target.username.isEmpty() && target.password.isEmpty())
            val finalBody = result.body ?: text
            require(finalBody.toByteArray(Charsets.UTF_8).size <= PluginHookTransform.MAX_HTTP_BODY_BYTES &&
                PluginJson.parse(finalBody, maxDepth = 128) is JsonObject)
            val headers = PluginHttpHeaders.merge(request.url.toString(), originalHeaders, result.headers.orEmpty(), target.toString())
            val rebuilt = request.newBuilder().url(target).headers(Headers.Builder().apply {
                headers.forEach { (name, value) -> if (!name.equals("content-length", true) && !name.equals("transfer-encoding", true)) add(name, value) }
            }.build()).method(request.method, finalBody.toRequestBody(media)).build()
            result.checkCurrent()
            if (call.isCanceled()) throw IOException("Canceled")
            rebuilt
        } catch (_: Exception) {
            if (call.isCanceled()) throw IOException("Canceled")
            request
        }
        return chain.proceed(transformed)
    }

    private fun isProviderRequest(request: Request): Boolean {
        val invocation = request.tag(Invocation::class.java)
        return if (invocation != null) invocation.method().declaringClass.name.startsWith("com.aicode.feature.agent.data.remote.")
            else isProviderEndpoint(request.url.encodedPath)
    }

    private fun isProviderEndpoint(path: String): Boolean = path.endsWith("/chat/completions") || path.endsWith("/responses") ||
        path.endsWith("/messages") || path.endsWith("/images/generations") || path.endsWith(":generateContent") || path.endsWith(":streamGenerateContent")
}
