package com.aicode.feature.plugin.runtime

import com.aicode.feature.agent.domain.model.AgentImage
import java.util.Base64
import java.util.Locale
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

class PluginModelEncodedImages(val array: JsonArray, val truncated: Boolean)

class PluginModelImageCodec(private val readPath: suspend (String) -> ByteArray) {
    private var count = 0
    private var bytes = 0L

    suspend fun parse(raw: JsonElement?, messageIndex: Int): List<AgentImage> {
        if (raw == null || raw is JsonNull) return emptyList()
        val array = raw as? JsonArray ?: PluginConversationJson.invalid("messages[$messageIndex].images must be an array")
        if (array.size > MAX_IMAGES_PER_MESSAGE || count + array.size > MAX_IMAGES_PER_REQUEST) tooLarge()
        return array.map { element ->
            val item = PluginConversationJson.objectValue(element, "image")
            PluginConversationJson.keys(item, setOf("mimeType", "path", "base64"))
            val mime = PluginConversationJson.string(item, "mimeType", 32).lowercase(Locale.ROOT)
            if (mime !in SUPPORTED_MIME_TYPES) throw PluginHostException("IMAGE_TYPE_UNSUPPORTED", "Image MIME type is not supported")
            val path = PluginConversationJson.optionalString(item, "path", 4096)
            val inline = PluginConversationJson.optionalString(item, "base64", MAX_INLINE_IMAGE_BYTES * 4 / 3 + 4)
            if ((path == null) == (inline == null)) PluginConversationJson.invalid("Image requires exactly one of path or base64")
            val data = if (path != null) readPath(path) else try {
                Base64.getDecoder().decode(inline)
            } catch (_: IllegalArgumentException) { PluginConversationJson.invalid("Image base64 is invalid") }
            if (data.isEmpty() || data.size > if (path != null) MAX_PATH_IMAGE_BYTES else MAX_INLINE_IMAGE_BYTES) tooLarge()
            bytes += data.size
            count++
            if (bytes > MAX_IMAGES_TOTAL_BYTES) tooLarge()
            AgentImage(mime, Base64.getEncoder().encodeToString(data), path.orEmpty())
        }
    }

    private fun tooLarge(): Nothing = throw PluginHostException("IMAGE_TOO_LARGE", "Image request exceeds the image budget")

    companion object {
        val SUPPORTED_MIME_TYPES = setOf("image/png", "image/jpeg", "image/webp", "image/gif")
        const val MAX_IMAGES_PER_MESSAGE = 4
        const val MAX_IMAGES_PER_REQUEST = 8
        const val MAX_PATH_IMAGE_BYTES = 1024 * 1024
        const val MAX_INLINE_IMAGE_BYTES = 1024 * 1024
        const val MAX_IMAGES_TOTAL_BYTES = 2L * 1024 * 1024
        const val MAX_OUTPUT_IMAGE_CHARS = 512 * 1024
        const val MAX_OUTPUT_IMAGES_CHARS = 768 * 1024

        fun encodeOutput(images: List<AgentImage>): PluginModelEncodedImages {
            var size = 0
            var truncated = false
            val array = images.mapNotNull { image ->
                if (image.base64Data.isEmpty()) return@mapNotNull null
                if (image.mimeType !in SUPPORTED_MIME_TYPES || image.base64Data.length > MAX_OUTPUT_IMAGE_CHARS ||
                    size + image.base64Data.length > MAX_OUTPUT_IMAGES_CHARS) {
                    truncated = true
                    return@mapNotNull null
                }
                size += image.base64Data.length
                buildJsonObject { put("mimeType", image.mimeType); put("base64", image.base64Data) }
            }
            return PluginModelEncodedImages(JsonArray(array), truncated)
        }
    }
}
