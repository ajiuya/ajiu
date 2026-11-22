package com.aicode.feature.plugin.runtime

import android.Manifest
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import com.aicode.MainActivity
import com.aicode.R
import com.aicode.core.util.FileLogger
import com.aicode.feature.agent.domain.shizuku.ShizukuManager
import com.aicode.feature.agent.domain.shizuku.ShizukuState
import com.aicode.feature.agent.domain.permission.ShellCommandParser
import com.aicode.feature.plugin.domain.PluginCapability
import dagger.hilt.android.qualifiers.ApplicationContext
import java.util.Locale
import javax.inject.Inject
import javax.inject.Provider
import javax.inject.Singleton
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.longOrNull
import kotlinx.serialization.json.put
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull

@Singleton
class PluginSystemHostApi @Inject constructor(
    @param:ApplicationContext private val app: Context,
    private val gateway: Provider<PluginHostGateway>,
    private val secrets: PluginSecretStore,
    private val http: PluginHttpService,
    private val shizuku: ShizukuManager
) : PluginHostApi {
    override val methods = setOf("app.info", "clipboard.read", "clipboard.write", "share.text", "ui.openUrl", "log",
        "notify", "secrets.get", "secrets.set", "secrets.delete", "secrets.list", "http.request", "shizuku.exec")

    override suspend fun call(context: PluginInvocationContext, method: String, params: JsonObject): JsonElement {
        context.checkIntegrity()
        return when (method) {
            "app.info" -> {
                val info = app.packageManager.getPackageInfo(app.packageName, 0)
                buildJsonObject {
                    put("packageName", app.packageName); put("versionName", info.versionName.orEmpty())
                    put("versionCode", info.longVersionCode); put("sdkInt", Build.VERSION.SDK_INT)
                    put("languageTag", Locale.getDefault().toLanguageTag())
                    put("isForeground", gateway.get().call(context, "app.isForeground", JsonObject(emptyMap())))
                }
            }
            "clipboard.read" -> {
                approve(context, method, JsonObject(emptyMap()), "Read clipboard")
                withContext(Dispatchers.Main.immediate) {
                    context.checkCurrent()
                    val clip = app.getSystemService(ClipboardManager::class.java).primaryClip
                    val text = if (clip != null && clip.itemCount > 0) clip.getItemAt(0).text?.toString().orEmpty() else ""
                    if (text.toByteArray(Charsets.UTF_8).size > 128 * 1024) tooLarge()
                    buildJsonObject { put("text", text) }
                }
            }
            "clipboard.write" -> {
                val text = string(params, "text", 128 * 1024, true)
                approve(context, method, buildJsonObject { put("characters", text.length) }, "Write clipboard (${text.length} characters)")
                withContext(Dispatchers.Main.immediate) {
                    context.checkCurrent()
                    app.getSystemService(ClipboardManager::class.java).setPrimaryClip(ClipData.newPlainText(app.getString(R.string.app_name), text))
                }
                ok()
            }
            "share.text" -> {
                val text = string(params, "text", 256 * 1024, true)
                val title = optional(params, "title", 200)
                approve(context, method, buildJsonObject { put("characters", text.length); put("title", title) }, "Share text (${text.length} characters)")
                withContext(Dispatchers.Main.immediate) {
                    context.checkCurrent()
                    val send = Intent(Intent.ACTION_SEND).setType("text/plain").putExtra(Intent.EXTRA_TEXT, text)
                    title?.let { send.putExtra(Intent.EXTRA_SUBJECT, it) }
                    app.startActivity(Intent.createChooser(send, title).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
                }
                ok()
            }
            "ui.openUrl" -> {
                val raw = string(params, "url", 4096)
                val url = raw.toHttpUrlOrNull()?.takeIf { it.username.isEmpty() && it.password.isEmpty() } ?: invalid()
                approve(context, method, buildJsonObject { put("url", raw) }, "Open URL\n$url")
                withContext(Dispatchers.Main.immediate) {
                    context.checkCurrent()
                    app.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url.toString())).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
                }
                ok()
            }
            "log" -> {
                val message = string(params, "message", 4096, true)
                val level = string(params, "level", 16)
                val tag = "plugin:${context.capture.id}" + optional(params, "tag", 64)?.let { ":$it" }.orEmpty()
                val safe = message.replace(Regex("(?i)(authorization|bearer|api[_-]?key|token|password)\\s*[:= ]+[^\\s,;]+"), "$1=[redacted]")
                when (level) {
                    "debug" -> FileLogger.d(tag, safe)
                    "info" -> FileLogger.i(tag, safe)
                    "warn" -> FileLogger.w(tag, safe)
                    "error" -> FileLogger.e(tag, safe)
                    else -> invalid()
                }
                ok()
            }
            "http.request" -> {
                val request = http.parseRequest(params)
                val summary = buildJsonObject { put("url", request.url.toString()); put("method", request.method)
                    put("bytes", request.body?.toByteArray(Charsets.UTF_8)?.size ?: 0)
                    put("headers", JsonArray(request.headers.keys.map(::JsonPrimitive))) }
                approve(context, method, summary, summary.toString())
                http.execute(context, request)
            }
            "notify" -> {
                val title = string(params, "title", 100)
                val body = string(params, "body", 1000, true)
                val key = optional(params, "key", 64)
                approve(context, method, buildJsonObject { put("title", title); put("body", body) }, "$title\n$body")
                withContext(Dispatchers.Main.immediate) {
                    context.checkCurrent()
                    val manager = NotificationManagerCompat.from(app)
                    if (!manager.areNotificationsEnabled() || Build.VERSION.SDK_INT >= 33 && ContextCompat.checkSelfPermission(app,
                            Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) {
                        throw PluginHostException("NOTIFICATION_PERMISSION_DENIED", "Notifications are not authorized")
                    }
                    manager.createNotificationChannel(NotificationChannel(CHANNEL, app.getString(R.string.app_name), NotificationManager.IMPORTANCE_DEFAULT))
                    if (manager.getNotificationChannel(CHANNEL)?.importance == NotificationManager.IMPORTANCE_NONE) {
                        throw PluginHostException("NOTIFICATION_DISABLED", "Plugin notifications are disabled")
                    }
                    val intent = PendingIntent.getActivity(app, 0, Intent(app, MainActivity::class.java)
                        .addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP), PendingIntent.FLAG_IMMUTABLE)
                    val notification = NotificationCompat.Builder(app, CHANNEL).setSmallIcon(android.R.drawable.ic_menu_info_details)
                        .setContentTitle(title).setContentText(body).setStyle(NotificationCompat.BigTextStyle().bigText(body))
                        .setContentIntent(intent).setAutoCancel(true).setVisibility(NotificationCompat.VISIBILITY_PRIVATE).build()
                    manager.notify("plugin:${context.capture.id}:${key.orEmpty()}", 1, notification)
                }
                ok()
            }
            "shizuku.exec" -> {
                gateway.get().requireCapability(context, PluginCapability.SHIZUKU)
                val command = string(params, "command", 4096)
                if (ShellCommandParser.requiresRootApproval(command)) throw PluginHostException("SYSTEM_PROTECTED", "Use the native Root tool for host root operations")
                val timeout = (params["timeout"] as? JsonPrimitive)?.takeUnless { it.isString }?.longOrNull?.takeIf { it in 1..1800 }
                    ?: if ("timeout" in params) invalid() else 120L
                gateway.get().authorize(context, "Shizuku", buildJsonObject { put("command", command); put("timeout", timeout) },
                    PluginApprovalOperation.EXECUTE_COMMAND, command)
                gateway.get().requireCapability(context, PluginCapability.SHIZUKU)
                if (shizuku.state.value != ShizukuState.READY) throw PluginHostException("SHIZUKU_NOT_READY", "A shell-UID Shizuku connection is required")
                val result = shizuku.runCommand(command, timeout * 1000)
                context.checkIntegrity()
                val bounded = com.aicode.feature.agent.domain.container.BoundedOutput().apply { append(result.output) }
                buildJsonObject { put("output", bounded.build()); put("exitCode", result.exitCode); put("truncated", bounded.truncated) }
            }
            "secrets.get", "secrets.set", "secrets.delete", "secrets.list" -> {
                val operation = method.substringAfter('.')
                val key = if (operation == "list") null else string(params, "key", 128)
                val value = if (operation == "set") string(params, "value", 64 * 1024, true) else null
                val result = secrets.call(context.capture, operation, key, value)
                when (operation) {
                    "get" -> JsonObject(result.mapValues { JsonPrimitive(it.value) })
                    "list" -> buildJsonObject { put("keys", JsonArray(result.keys.sorted().map(::JsonPrimitive))) }
                    else -> ok()
                }
            }
            else -> throw PluginHostException("METHOD_NOT_SUPPORTED", "Unknown system API")
        }.also { context.checkIntegrity() }
    }

    private suspend fun approve(context: PluginInvocationContext, method: String, params: JsonObject, details: String) =
        gateway.get().authorize(context, method, params, PluginApprovalOperation.EXECUTE_TOOL, details)
    private fun string(params: JsonObject, name: String, max: Int, empty: Boolean = false): String =
        (params[name] as? JsonPrimitive)?.takeIf { it.isString && (empty || it.content.isNotBlank()) && it.content.length <= max && '\u0000' !in it.content }?.content ?: invalid()
    private fun optional(params: JsonObject, name: String, max: Int): String? = if (params[name] == null || params[name] == kotlinx.serialization.json.JsonNull) null else string(params, name, max)
    private fun invalid(): Nothing = throw PluginHostException("PARAM_INVALID", "Invalid system API parameters")
    private fun tooLarge(): Nothing = throw PluginHostException("TOO_LARGE", "System API response exceeds its limit")
    private fun ok() = buildJsonObject { put("ok", true) }
    private companion object { const val CHANNEL = "plugin_events" }
}
