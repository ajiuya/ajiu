package com.aicode.feature.plugin.runtime

import com.aicode.feature.agent.domain.model.AgentImage
import java.util.Base64
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import org.junit.Assert.*
import org.junit.Test

class PluginModelCodecTest {
    @Test fun inlineAndPathImagesAreLoadedThroughExplicitBoundary() = runBlocking<Unit> {
        val paths = mutableListOf<String>()
        val codec = PluginModelImageCodec { path -> paths += path; byteArrayOf(1, 2, 3) }
        val images = codec.parse(JsonArray(listOf(
            buildJsonObject { put("mimeType", "image/png"); put("base64", Base64.getEncoder().encodeToString(byteArrayOf(4, 5))) },
            buildJsonObject { put("mimeType", "image/jpeg"); put("path", "data://picture.jpg") }
        )), 0)
        assertEquals(2, images.size)
        assertEquals(listOf("data://picture.jpg"), paths)
        assertEquals("data://picture.jpg", images[1].path)
    }

    @Test fun ambiguousOrInvalidBase64ImagesAreRejected() = runBlocking<Unit> {
        val codec = PluginModelImageCodec { fail("No image read should occur"); ByteArray(0) }
        for (image in listOf(
            buildJsonObject { put("mimeType", "image/png"); put("base64", "not base64") },
            buildJsonObject { put("mimeType", "image/png"); put("base64", "AA=="); put("path", "data://file") }
        )) {
            try { codec.parse(JsonArray(listOf(image)), 0); fail("Expected invalid image") }
            catch (failure: PluginHostException) { assertEquals("PARAM_INVALID", failure.code) }
        }
    }

    @Test fun imageCountBudgetSpansTheEntireRequest() = runBlocking<Unit> {
        val codec = PluginModelImageCodec { byteArrayOf(1) }
        val images = JsonArray(List(4) { buildJsonObject { put("mimeType", "image/png"); put("base64", "AA==") } })
        codec.parse(images, 0)
        codec.parse(images, 1)
        try { codec.parse(images, 2); fail("Expected image budget failure") }
        catch (failure: PluginHostException) { assertEquals("IMAGE_TOO_LARGE", failure.code) }
    }

    @Test fun outputImagesRespectTransportBudgetAndSignalTruncation() {
        val result = PluginModelImageCodec.encodeOutput(listOf(
            AgentImage("image/png", "A".repeat(500 * 1024)),
            AgentImage("image/png", "B".repeat(500 * 1024))
        ))
        assertEquals(1, result.array.size)
        assertTrue(result.truncated)
    }

    @Test fun schemaDefinitionsHaveNoHostExecutionAuthority() = runBlocking<Unit> {
        val definition = PluginModelToolDefinition("custom", "Custom schema", buildJsonObject {
            put("type", "object"); put("required", JsonArray(listOf(JsonPrimitive("path"))))
        })
        assertEquals(listOf("path"), definition.toJsonSchema()["required"])
        try { definition.execute(emptyMap()); fail("No execution authority is attached to a schema") }
        catch (failure: PluginHostException) { assertEquals("SYSTEM_PROTECTED", failure.code) }
    }
}
