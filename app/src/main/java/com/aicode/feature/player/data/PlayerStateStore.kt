package com.aicode.feature.player.data

import android.content.SharedPreferences
import kotlinx.serialization.json.*

internal class PlayerStateStore(
    private val preferences: SharedPreferences,
    private val json: Json,
    val namespace: String,
    private val maxKeys: Int = 4096
) {
    private data class Revision(val epoch: Long, val sequence: Long) : Comparable<Revision> {
        override fun compareTo(other: Revision) = compareValuesBy(this, other, Revision::epoch, Revision::sequence)
        fun json() = buildJsonObject { put("epoch", epoch); put("sequence", sequence) }
    }
    private val states = linkedMapOf<String, String>()
    private val revisions = linkedMapOf<String, Revision>()
    private var epoch = preferences.getString("epoch", "0")?.toLongOrNull() ?: 0L
    private val keyPrefix = "ajiu_player_v3:$namespace:"
    init {
        require(namespace.matches(Regex("[a-f0-9]{64}")) && maxKeys in 1..4096)
        preferences.all.entries.filter { it.key.startsWith("record:") }.take(maxKeys).forEach { (raw, value) ->
            val key = raw.removePrefix("record:")
            if (validKey(key) && value is String && validValue(value)) states[key] = value
        }
        preferences.all.entries.filter { it.key.startsWith("revision:") }.take(maxKeys).forEach { (raw, value) ->
            val key = raw.removePrefix("revision:")
            if (validKey(key) && value is String) runCatching { revision(json.parseToJsonElement(value)) }.getOrNull()?.let { revisions[key] = it }
        }
        if (snapshot().toString().toByteArray().size > MAX_TOTAL) states.clear()
    }
    @Synchronized fun snapshot(): JsonObject = JsonObject(states.mapValues { JsonPrimitive(it.value) })
    @Synchronized fun bootstrapScript(): String {
        val next = maxOf(epoch + 1, System.currentTimeMillis())
        if (!preferences.edit().putString("epoch", next.toString()).commit()) throw PlayerHttpException(500, "State storage failed")
        epoch = next
        return "window.__AJIU_STATE_NAMESPACE__=${JsonPrimitive(namespace)};\n" +
            "window.__AJIU_STATE_EPOCH__=$next;\nwindow.__AJIU_SAVED_STATE__=${snapshot()};\n" +
            "window.__AJIU_STATE_REVISIONS__=${JsonObject(revisions.mapValues { it.value.json() })};\n"
    }
    @Synchronized fun update(bytes: ByteArray) {
        if (bytes.size > MAX_BODY) throw PlayerHttpException(413, "State request too large")
        val obj = runCatching { json.parseToJsonElement(bytes.toString(Charsets.UTF_8)) as? JsonObject }.getOrNull()
            ?: throw PlayerHttpException(400, "State JSON required")
        val key = (obj["key"] as? JsonPrimitive)?.takeIf { it.isString }?.content ?: throw PlayerHttpException(400, "State key required")
        if (!validKey(key)) throw PlayerHttpException(400, "Invalid state namespace or key")
        val value = obj["value"] ?: throw PlayerHttpException(400, "State value required")
        val text = when (value) {
            JsonNull -> null
            is JsonPrimitive -> value.takeIf { it.isString }?.content
                ?.also { if (!validValue(it)) throw PlayerHttpException(400, "Invalid state value") }
                ?: throw PlayerHttpException(400, "State string required")
            else -> throw PlayerHttpException(400, "State string or null required")
        }
        val incoming = revision(obj["revision"])?.takeIf { it.epoch <= epoch } ?: throw PlayerHttpException(400, "Invalid state revision")
        val previous = revisions[key]
        if (previous != null && (incoming < previous || incoming == previous && states[key] != text)) throw PlayerHttpException(409, "Stale state revision")
        if (states[key] == text && incoming == previous) return
        if ((states.keys + revisions.keys + key).size > maxKeys) throw PlayerHttpException(413, "State storage limit")
        val next = states.toMutableMap().apply { if (text == null) remove(key) else put(key, text) }
        if (JsonObject(next.mapValues { JsonPrimitive(it.value) }).toString().toByteArray().size > MAX_TOTAL) throw PlayerHttpException(413, "State storage limit")
        val editor = preferences.edit().putString("revision:$key", incoming.json().toString())
        if (text == null) editor.remove("record:$key") else editor.putString("record:$key", text)
        if (!editor.commit()) throw PlayerHttpException(500, "State storage failed")
        if (text == null) states.remove(key) else states[key] = text
        revisions[key] = incoming
    }
    private fun revision(value: JsonElement?): Revision? {
        val obj = value as? JsonObject ?: return null
        val e = (obj["epoch"] as? JsonPrimitive)?.longOrNull ?: return null
        val s = (obj["sequence"] as? JsonPrimitive)?.longOrNull ?: return null
        return if (e in 1..9007199254740991L && s in 1..9007199254740991L) Revision(e, s) else null
    }
    private fun validKey(key: String) = key.startsWith(keyPrefix) && key.length <= 2048 && key.none { it.code < 32 } && !temporaryUrl.containsMatchIn(key)
    private fun validValue(value: String) = value.toByteArray().size <= 512 * 1024 && !temporaryUrl.containsMatchIn(value.replace("\\/", "/"))
    companion object {
        const val MAX_BODY = 1024 * 1024 + 4096
        private const val MAX_TOTAL = 4 * 1024 * 1024
        private val temporaryUrl = Regex("(?:https?://(?:127\\.0\\.0\\.1|localhost):[0-9]+)?/[0-9a-f]{64}/", RegexOption.IGNORE_CASE)
        fun preferencesName(namespace: String) = "ajiu_player_state_$namespace"
    }
}
