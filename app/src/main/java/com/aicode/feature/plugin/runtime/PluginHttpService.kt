package com.aicode.feature.plugin.runtime

import java.io.ByteArrayOutputStream
import java.util.Locale
import java.util.concurrent.TimeUnit
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.InternalCoroutinesApi
import kotlinx.coroutines.Job
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.longOrNull
import kotlinx.serialization.json.put
import okhttp3.Headers
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody

data class PluginHttpRequest(val url: okhttp3.HttpUrl, val method: String, val headers: Map<String, String>, val body: String?, val timeoutSeconds: Long)

@Singleton
class PluginHttpService @Inject constructor(client: OkHttpClient) {
    private val plain = OkHttpClient.Builder().connectionPool(client.connectionPool).dispatcher(client.dispatcher)
        .connectTimeout(30, TimeUnit.SECONDS).readTimeout(30, TimeUnit.SECONDS).writeTimeout(30, TimeUnit.SECONDS)
        .followRedirects(false).followSslRedirects(false).build()

    internal fun parseRequest(params: JsonObject): PluginHttpRequest {
        fun optional(key: String): String? = when (val value = params[key]) {
            null, JsonNull -> null
            else -> (value as? JsonPrimitive)?.takeIf { it.isString }?.content ?: invalid()
        }
        val raw = optional("url")?.takeIf { it.length <= 4096 } ?: invalid()
        val url = raw.toHttpUrlOrNull()?.takeIf { it.scheme in setOf("http", "https") && it.username.isEmpty() && it.password.isEmpty() } ?: invalid()
        val method = optional("method")?.uppercase(Locale.ROOT) ?: "GET"
        if (method !in setOf("GET", "HEAD", "POST", "PUT", "PATCH", "DELETE", "OPTIONS")) invalid()
        val body = optional("body")
        if (body != null && (method in setOf("GET", "HEAD") || body.toByteArray(Charsets.UTF_8).size > MAX_BYTES)) invalid()
        val headers = when (val rawHeaders = params["headers"]) {
            null, JsonNull -> emptyMap()
            is JsonObject -> rawHeaders.mapValues { (_, value) -> (value as? JsonPrimitive)?.takeIf { it.isString }?.content ?: invalid() }
            else -> invalid()
        }
        if (headers.size > 64 || headers.any { (name, value) -> !HEADER_NAME.matches(name) || name.lowercase(Locale.ROOT) in FORBIDDEN ||
                value.length > 8192 || value.any(Char::isISOControl) }) invalid()
        val timeout = when (val time = params["timeout"]) {
            null, JsonNull -> 60L
            else -> (time as? JsonPrimitive)?.takeUnless { it.isString }?.longOrNull?.takeIf { it in 1..300 } ?: invalid()
        }
        return PluginHttpRequest(url, method, headers, body, timeout)
    }

    @OptIn(InternalCoroutinesApi::class)
    suspend fun execute(context: PluginInvocationContext, request: PluginHttpRequest): JsonElement = withContext(Dispatchers.IO) {
        val media = request.headers.entries.firstOrNull { it.key.equals("content-type", true) }?.value?.toMediaType()
            ?: "application/json; charset=utf-8".toMediaType()
        val body = request.body?.toRequestBody(media) ?: if (request.method in setOf("POST", "PUT", "PATCH")) "".toRequestBody(media) else null
        val builder = Request.Builder().url(request.url).method(request.method, body)
        builder.headers(Headers.Builder().apply { request.headers.forEach { (name, value) -> add(name, value) } }.build())
        val call = plain.newBuilder().callTimeout(request.timeoutSeconds, TimeUnit.SECONDS).build().newCall(builder.build())
        val owner = currentCoroutineContext()
        val cancellation = owner[Job]?.invokeOnCompletion(onCancelling = true, invokeImmediately = true) { failure -> if (failure != null) call.cancel() }
        try {
            owner.ensureActive(); context.checkIntegrity()
            call.execute().use { response ->
                val bytes = ByteArrayOutputStream()
                response.body?.byteStream()?.use { stream ->
                    val buffer = ByteArray(8192)
                    while (bytes.size() <= MAX_BYTES) {
                        owner.ensureActive(); context.checkCurrent()
                        val read = stream.read(buffer, 0, minOf(buffer.size, MAX_BYTES + 1 - bytes.size()))
                        if (read < 0) break
                        if (read == 0) throw java.io.IOException("HTTP response made no progress")
                        bytes.write(buffer, 0, read)
                    }
                }
                owner.ensureActive(); context.checkIntegrity()
                val all = bytes.toByteArray()
                buildJsonObject {
                    put("status", response.code)
                    put("headers", JsonObject(response.headers.toMultimap().mapValues { JsonPrimitive(it.value.joinToString(", ")) }))
                    put("body", all.copyOf(minOf(all.size, MAX_BYTES)).toString(Charsets.UTF_8))
                    put("truncated", all.size > MAX_BYTES)
                }
            }
        } catch (failure: java.io.IOException) {
            owner.ensureActive(); context.checkCurrent()
            throw PluginHostException("HTTP_FAILED", "The HTTP request could not complete")
        } finally { cancellation?.dispose(); call.cancel() }
    }

    private fun invalid(): Nothing = throw PluginHostException("PARAM_INVALID", "Invalid HTTP request")
    private companion object {
        const val MAX_BYTES = 1024 * 1024
        val HEADER_NAME = Regex("^[!#$%&'*+.^_`|~0-9A-Za-z-]+$")
        val FORBIDDEN = setOf("host", "content-length", "transfer-encoding", "connection", "expect", "upgrade")
    }
}
