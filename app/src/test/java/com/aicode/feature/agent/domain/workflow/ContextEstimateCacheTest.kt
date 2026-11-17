package com.aicode.feature.agent.domain.workflow

import com.aicode.feature.agent.domain.model.AgentImage
import com.aicode.feature.agent.domain.model.AgentMessage
import com.aicode.feature.agent.domain.model.ContextUsageBreakdown
import com.aicode.feature.agent.domain.tool.AgentTool
import com.aicode.feature.agent.domain.tool.ParameterType
import com.aicode.feature.agent.domain.tool.ToolCall
import com.aicode.feature.agent.domain.tool.ToolCapability
import com.aicode.feature.agent.domain.tool.ToolParameter
import com.aicode.feature.agent.domain.tool.ToolResult
import com.aicode.feature.agent.domain.tool.modelToolResultText
import com.aicode.feature.settings.domain.model.ModelContextPolicy
import com.google.gson.Gson
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.lang.reflect.Modifier
import java.util.Collections
import java.util.IdentityHashMap
import java.util.concurrent.Callable
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

class ContextEstimateCacheTest {
    private val gson = Gson()
    private val image = AgentImage("image/png", "base64")

    @Test
    fun cachedBreakdownsMatchUncachedAlgorithmForUnicodeEscapingImagesAndModelResults() {
        val writeResult = """{"status":"success","data":{"path":"中文.kt","created":true,"lines_written":3},"notifications":[{"summary":"完成"}]}"""
        val messages = listOf(
            AgentMessage.UserMessage(content = "ASCII <>&='\"\n\t 中文\uD83D\uDE00\u2028\u2029", images = listOf(image)),
            AgentMessage.UserMessage(content = ""),
            AgentMessage.AssistantMessage(content = "a", reasoning = "b"),
            AgentMessage.AssistantMessage(
                content = "回答\uD83E\uDDEA",
                reasoning = "思考 <&>",
                toolCalls = listOf(
                    ToolCall("call-1", "readFile", linkedMapOf("path" to JsonPrimitive("<&中文.kt"))),
                    ToolCall("call-2", "writeFile", linkedMapOf("content" to JsonPrimitive("\uD83D\uDE00\n"))),
                    ToolCall("call-3", "empty", emptyMap())
                ),
                images = listOf(image, image)
            ),
            AgentMessage.ToolResultMessage(toolName = "writeFile", result = writeResult),
            AgentMessage.ToolResultMessage(toolName = "readFile", result = "raw".repeat(1_000), modelResult = "精简\uD83D\uDE00", images = listOf(image)),
            AgentMessage.ToolResultMessage(toolName = "unknown", result = "原样回退 <>&\uD83D\uDE00"),
            AgentMessage.ToolResultMessage(toolName = "writeFile", result = writeResult, modelResult = "")
        )
        val tools = listOf(MutableBaseTool(), MutableSchemaTool())
        for (density in listOf(1, 2, 3, 4, 5, 8, Int.MAX_VALUE)) {
            val expected = uncachedBreakdown("System 系统\uD83D\uDE00 <&>", messages, tools, density)
            repeat(3) {
                assertEquals(expected, ContextTokenEstimator.breakdown("System 系统\uD83D\uDE00 <&>", messages, tools, density))
                assertEquals(expected.estimatedTotal, ContextTokenEstimator.estimate("System 系统\uD83D\uDE00 <&>", messages, tools, density))
                messages.forEach { message ->
                    assertEquals(uncachedMessage(message, density), ContextTokenEstimator.estimate(message, density))
                }
            }
        }
        assertEquals(2, ModelContextPolicy.estimateTextTokens("\uD83D\uDE00"))
        assertEquals(3, ModelContextPolicy.COMPACTION_CHARS_PER_TOKEN)
        assertEquals(0.8, ModelContextPolicy.COMPACTION_BUDGET_RATIO, 0.0)
    }

    @Test
    fun assistantRoundingStillGroupsContentReasoningAndDefaultCallSeparators() {
        val cache = ContextEstimateCache()
        val message = AgentMessage.AssistantMessage(
            content = "a", reasoning = "b",
            toolCalls = listOf(ToolCall("1", "c", emptyMap()), ToolCall("2", "d", emptyMap()))
        )
        assertEquals("abc{}, d{}", uncachedText(message))
        assertEquals(3, cache.messageTextTokens(message, 4))
        assertEquals(2, cache.messageTextTokens(message.copy(toolCalls = message.toolCalls.take(1)), 4))
        assertEquals(uncachedMessage(message, 4), ContextTokenEstimator.estimate(message, 4))
    }

    @Test
    fun repeatedPublicEstimatesReuseMessageAndSchemaMaterializations() {
        val message = AgentMessage.AssistantMessage(
            content = "答案", reasoning = "理由",
            toolCalls = listOf(ToolCall("call", "readFile", mapOf("path" to JsonPrimitive("a.kt"))))
        )
        val tool = MutableBaseTool(name = "cacheStats_${message.id}")
        val expected = uncachedBreakdown("system", listOf(message), listOf(tool), 4)
        assertEquals(expected, ContextTokenEstimator.breakdown("system", listOf(message), listOf(tool)))
        val warm = ContextTokenEstimator.cacheStats
        repeat(20) {
            assertEquals(expected.estimatedTotal, ContextTokenEstimator.estimate("system", listOf(message), listOf(tool)))
        }
        val repeated = ContextTokenEstimator.cacheStats
        assertEquals(warm.messageMaterializations, repeated.messageMaterializations)
        assertEquals(warm.schemaMaterializations, repeated.schemaMaterializations)
        assertEquals(warm.messageHits + 20, repeated.messageHits)
        assertEquals(warm.schemaHits + 20, repeated.schemaHits)
        assertTrue(repeated.messageEntries <= 8_192)
        assertTrue(repeated.messageBytes <= 24L * 1024 * 1024)
        assertTrue(repeated.schemaEntries <= 512)
        assertTrue(repeated.schemaBytes <= 4L * 1024 * 1024)
    }

    @Test
    fun sameIdTextReasoningNamesAndArgumentsNeverReturnAnOldCount() {
        val cache = ContextEstimateCache()
        val call = ToolCall("call", "readFile", mapOf("path" to JsonPrimitive("a.kt")))
        val original = AgentMessage.AssistantMessage(id = "same", content = "a", reasoning = "b", toolCalls = listOf(call))
        val versions = listOf(
            original,
            original.copy(content = "文本".repeat(30)),
            original.copy(reasoning = "reasoning".repeat(30)),
            original.copy(toolCalls = listOf(call.copy(name = "name".repeat(30)))),
            original.copy(toolCalls = listOf(call.copy(arguments = mapOf("path" to JsonPrimitive("新路径".repeat(30)))))),
            original
        )
        versions.forEach { assertMessageText(cache, it) }
        assertEquals(versions.size.toLong(), cache.stats.messageMaterializations)
        assertEquals(1, cache.stats.messageEntries)
    }

    @Test
    fun mutableArgumentsNestedJsonMapsListsAndCallListsAreDeeplySnapshotted() {
        val cache = ContextEstimateCache()
        val items = mutableListOf<JsonElement>(JsonPrimitive("one"))
        val fields = linkedMapOf<String, JsonElement>("items" to JsonArray(items))
        val arguments = linkedMapOf<String, JsonElement>("object" to JsonObject(fields), "text" to JsonPrimitive("short"))
        val calls = mutableListOf(ToolCall("call", "readFile", arguments))
        val message = AgentMessage.AssistantMessage(content = "answer", toolCalls = calls)
        assertMessageText(cache, message)
        arguments["text"] = JsonPrimitive("文字".repeat(30))
        assertMessageText(cache, message)
        fields["extra"] = JsonPrimitive("nested".repeat(30))
        assertMessageText(cache, message)
        items[0] = JsonPrimitive("列表".repeat(30))
        assertMessageText(cache, message)
        items.add(JsonObject(linkedMapOf("extra" to JsonPrimitive("添加"))))
        assertMessageText(cache, message)
        calls.add(ToolCall("second", "writeFile", mapOf("content" to JsonPrimitive("file"))))
        assertMessageText(cache, message)
        assertEquals(6L, cache.stats.messageMaterializations)
        repeat(5) { assertMessageText(cache, message) }
        assertEquals(6L, cache.stats.messageMaterializations)
        assertEquals(5L, cache.stats.messageHits)
    }

    @Test
    fun reorderedArgumentMapsAndNestedObjectsAreComparedStructurallyInOrder() {
        val cache = ContextEstimateCache()
        val fields = linkedMapOf<String, JsonElement>("a" to JsonPrimitive("first"), "b" to JsonPrimitive("second"))
        val arguments = linkedMapOf<String, JsonElement>("object" to JsonObject(fields), "text" to JsonPrimitive("third"))
        val message = AgentMessage.AssistantMessage(content = "", toolCalls = listOf(ToolCall("call", "readFile", arguments)))
        assertMessageText(cache, message)
        fields["a"] = fields.remove("a")!!
        assertMessageText(cache, message)
        arguments["object"] = arguments.remove("object")!!
        assertMessageText(cache, message)
        assertEquals(3L, cache.stats.messageMaterializations)
    }

    @Test
    fun irrelevantMetadataAndImagePayloadsDoNotInvalidateTextSnapshots() {
        val cache = ContextEstimateCache()
        val call = ToolCall("call", "readFile", mapOf("path" to JsonPrimitive("a.kt")))
        val assistant = AgentMessage.AssistantMessage(content = "answer", reasoning = "reason", toolCalls = listOf(call))
        assertMessageText(cache, assistant)
        assertMessageText(cache, assistant.copy(
            signature = "signature".repeat(1_000), thinkingBlocksJson = "thinking".repeat(1_000), inputTokens = 32_000,
            isContextSummary = true, images = listOf(image), toolCalls = listOf(call.copy(id = "different-call"))
        ))
        val user = AgentMessage.UserMessage(content = "user")
        assertMessageText(cache, user)
        assertMessageText(cache, user.copy(
            images = listOf(image, image), isCompactionMarker = true, isTransient = true,
            modelInputOwnerId = "owner", projectMemoryKey = "memory"
        ))
        val result = AgentMessage.ToolResultMessage(toolName = "readFile", result = "raw", modelResult = "short")
        assertMessageText(cache, result)
        assertMessageText(cache, result.copy(id = "different-call", result = "raw".repeat(10_000), images = listOf(image)))
        assertEquals(3L, cache.stats.messageMaterializations)
        assertEquals(3L, cache.stats.messageHits)
        assertEquals(uncachedMessage(user.copy(images = listOf(image, image)), 4),
            ContextTokenEstimator.estimate(user.copy(images = listOf(image, image))))
    }

    @Test
    fun userAndToolResultChangesWithReusedIdsAreRecomputed() {
        val cache = ContextEstimateCache()
        val user = AgentMessage.UserMessage(id = "same", content = "small")
        assertMessageText(cache, user)
        assertMessageText(cache, user.copy(content = "中文".repeat(50)))
        val result = AgentMessage.ToolResultMessage(id = "same", messageId = "same", toolName = "unknown", result = "small")
        assertMessageText(cache, result)
        assertMessageText(cache, result.copy(result = "中文".repeat(50)))
        assertMessageText(cache, result.copy(toolName = "longToolName".repeat(30)))
        assertMessageText(cache, result.copy(modelResult = "model".repeat(30)))
        assertMessageText(cache, result.copy(modelResult = ""))
        assertEquals(7L, cache.stats.messageMaterializations)
        assertEquals(2, cache.stats.messageEntries)
    }

    @Test
    fun projectionUsesModelResultThenProjectedTransportThenRawFallback() {
        val cache = ContextEstimateCache()
        val result = AgentMessage.ToolResultMessage(
            toolName = "writeFile", result = """{"status":"success","data":{"path":"a.kt","lines_written":3}}"""
        )
        assertTrue(modelToolResultText(result.toolName, result.result) != null)
        assertMessageText(cache, result)
        assertMessageText(cache, result.copy(modelResult = "explicit"))
        assertMessageText(cache, result.copy(modelResult = ""))
        assertMessageText(cache, result.copy(result = "invalid JSON"))
        assertMessageText(cache, result.copy(toolName = "unknown"))
        assertEquals(5L, cache.stats.messageMaterializations)
    }

    @Test
    fun baseSchemasReuseEquivalentToolsWithoutSerializingAgain() {
        val cache = ContextEstimateCache()
        val tool = MutableBaseTool()
        val expected = uncachedSchemaText(tool, 4)
        repeat(10) {
            assertEquals(expected, cache.schemaTextTokens(MutableBaseTool(), 4))
        }
        assertEquals(1L, cache.stats.schemaMaterializations)
        assertEquals(9L, cache.stats.schemaHits)
        assertEquals(1, cache.stats.schemaEntries)
    }

    @Test
    fun baseSchemaMetadataParametersCapabilitiesAndNestedMutableValuesInvalidate() {
        val cache = ContextEstimateCache()
        val choices = mutableListOf("one")
        val required = mutableListOf("value")
        val field = linkedMapOf<String, Any>("type" to "string", "description" to "short")
        val items = linkedMapOf<String, Any>(
            "type" to "object", "properties" to linkedMapOf("value" to field), "required" to required
        )
        val parameters = linkedMapOf(
            "items" to ToolParameter("items", ParameterType.ARRAY, "items", enum = choices, itemsSchema = items)
        )
        val capabilities = mutableSetOf(ToolCapability.READ_WORKSPACE)
        val tool = MutableBaseTool().apply {
            this.parameters = parameters
            this.capabilities = capabilities
        }
        assertSchemaText(cache, tool)
        tool.description = "说明".repeat(50)
        assertSchemaText(cache, tool)
        choices.add("选择".repeat(20))
        assertSchemaText(cache, tool)
        required.add("extra")
        assertSchemaText(cache, tool)
        field["description"] = "嵌套".repeat(40)
        assertSchemaText(cache, tool)
        items["additionalProperties"] = false
        assertSchemaText(cache, tool)
        parameters["optional"] = ToolParameter("optional", ParameterType.STRING, "optional", required = false)
        assertSchemaText(cache, tool)
        capabilities.add(ToolCapability.EXTERNAL_TOOL)
        assertSchemaText(cache, tool)
        parameters["items"] = parameters.getValue("items").copy(required = false)
        assertSchemaText(cache, tool)
        tool.name = "renamed"
        assertSchemaText(cache, tool)
        assertEquals(10L, cache.stats.schemaMaterializations)
        assertEquals(2, cache.stats.schemaEntries)
        assertSchemaText(cache, tool)
        assertEquals(10L, cache.stats.schemaMaterializations)
    }

    @Test
    fun modelTitleInjectionRemainsSensitiveToCapabilitiesNameAndParameters() {
        val cache = ContextEstimateCache()
        val tool = MutableBaseTool()
        val titled = cache.schemaTextTokens(tool, 4)
        tool.capabilities = setOf(ToolCapability.EXTERNAL_TOOL)
        val external = cache.schemaTextTokens(tool, 4)
        assertEquals(uncachedSchemaText(tool, 4), external)
        assertTrue(titled > external)
        tool.capabilities = emptySet()
        tool.name = "mcp__server__base"
        assertSchemaText(cache, tool)
        tool.name = "base"
        tool.parameters = mapOf(AgentTool.TOOL_TITLE_PARAM to ToolParameter(AgentTool.TOOL_TITLE_PARAM, ParameterType.STRING, "own title"))
        assertSchemaText(cache, tool)
        assertEquals(4L, cache.stats.schemaMaterializations)
    }

    @Test
    fun sameOverridingToolIsReadEveryTimeAndMutableSchemaIsDeeplySnapshotted() {
        val cache = ContextEstimateCache()
        val values = mutableListOf("short")
        val property = linkedMapOf<String, Any>("type" to "string", "enum" to values)
        val properties = linkedMapOf<String, Any>("value" to property)
        val required = mutableListOf("value")
        val schema = linkedMapOf<String, Any>("type" to "object", "properties" to properties, "required" to required)
        val tool = MutableSchemaTool().apply { this.schema = schema }
        assertEquals(uncachedSchemaText(tool.name, tool.description, schema, 4), cache.schemaTextTokens(tool, 4))
        repeat(5) { cache.schemaTextTokens(tool, 4) }
        assertEquals(6, tool.schemaReads)
        assertEquals(1L, cache.stats.schemaMaterializations)
        values.add("更长".repeat(50))
        assertEquals(uncachedSchemaText(tool.name, tool.description, schema, 4), cache.schemaTextTokens(tool, 4))
        required.add("new")
        assertEquals(uncachedSchemaText(tool.name, tool.description, schema, 4), cache.schemaTextTokens(tool, 4))
        property["description"] = "变化".repeat(50)
        assertEquals(uncachedSchemaText(tool.name, tool.description, schema, 4), cache.schemaTextTokens(tool, 4))
        schema["additionalProperties"] = false
        assertEquals(uncachedSchemaText(tool.name, tool.description, schema, 4), cache.schemaTextTokens(tool, 4))
        assertEquals(10, tool.schemaReads)
        assertEquals(5L, cache.stats.schemaMaterializations)
        assertEquals(5L, cache.stats.schemaHits)
    }

    @Test
    fun localOverridingSchemasCompareTheActualModelTitleBoundary() {
        val cache = ContextEstimateCache()
        val properties = linkedMapOf<String, Any>("value" to mapOf("type" to "string"))
        val schema = linkedMapOf<String, Any>("type" to "object", "properties" to properties)
        val tool = MutableSchemaTool().apply { name = "customLocal"; this.schema = schema }
        assertSchemaText(cache, tool)
        val titled = cache.schemaTextTokens(tool, 4)
        tool.capabilities = setOf(ToolCapability.EXTERNAL_TOOL)
        val external = cache.schemaTextTokens(tool, 4)
        assertEquals(uncachedSchemaText(tool, 4), external)
        assertTrue(titled > external)
        tool.capabilities = emptySet()
        properties[AgentTool.TOOL_TITLE_PARAM] = mapOf("type" to "string", "description" to "own title")
        assertSchemaText(cache, tool)
        properties[AgentTool.TOOL_TITLE_PARAM] = mapOf("type" to "string", "description" to "变更".repeat(100))
        assertSchemaText(cache, tool)
        assertEquals(4L, cache.stats.schemaMaterializations)
        assertEquals(1L, cache.stats.schemaHits)
    }

    @Test
    fun schemaObjectsRecreatedOnEveryReadCanReuseStructuralCounts() {
        val cache = ContextEstimateCache()
        val tool = object : MutableBaseTool(name = "mcp__server__fresh") {
            var reads = 0
            override fun toJsonSchema(): Map<String, Any> {
                reads++
                return linkedMapOf("type" to "object", "properties" to linkedMapOf<String, Any>(), "required" to listOf("value"))
            }
        }
        repeat(5) { cache.schemaTextTokens(tool, 4) }
        assertEquals(5, tool.reads)
        assertEquals(1L, cache.stats.schemaMaterializations)
        assertEquals(4L, cache.stats.schemaHits)
    }

    @Test
    fun dynamicSchemaChangesOnSameToolInstanceAreNeverHiddenByACacheHit() {
        val cache = ContextEstimateCache()
        val tool = object : MutableBaseTool(name = "mcp__server__changing") {
            var reads = 0
            var lastSchema: Map<String, Any> = emptyMap()
            override fun toJsonSchema(): Map<String, Any> {
                reads++
                return mapOf<String, Any>("description" to "汉".repeat(reads * 10)).also { lastSchema = it }
            }
        }
        var previous = -1
        repeat(10) {
            val actual = cache.schemaTextTokens(tool, 4)
            assertEquals(uncachedSchemaText(tool.name, tool.description, tool.lastSchema, 4), actual)
            assertTrue(actual > previous)
            previous = actual
        }
        assertEquals(10, tool.reads)
        assertEquals(10L, cache.stats.schemaMaterializations)
        assertEquals(0L, cache.stats.schemaHits)
        assertEquals(1, cache.stats.schemaEntries)
    }

    @Test
    fun inheritedSchemaOverridesAreAlsoReadForEveryEstimate() {
        val cache = ContextEstimateCache()
        val tool = object : MutableSchemaTool() {}
        repeat(5) { cache.schemaTextTokens(tool, 4) }
        assertEquals(5, tool.schemaReads)
        assertEquals(1L, cache.stats.schemaMaterializations)
    }

    @Test
    fun schemaMapReorderingDoesNotUseReferenceOrUnorderedEquality() {
        val cache = ContextEstimateCache()
        val properties = linkedMapOf<String, Any>("a" to mapOf("type" to "string"), "b" to mapOf("type" to "number"))
        val schema = linkedMapOf<String, Any>("type" to "object", "properties" to properties)
        val tool = MutableSchemaTool().apply { this.schema = schema }
        cache.schemaTextTokens(tool, 4)
        properties["a"] = properties.remove("a")!!
        assertEquals(uncachedSchemaText(tool.name, tool.description, schema, 4), cache.schemaTextTokens(tool, 4))
        schema["type"] = schema.remove("type")!!
        assertEquals(uncachedSchemaText(tool.name, tool.description, schema, 4), cache.schemaTextTokens(tool, 4))
        assertEquals(3L, cache.stats.schemaMaterializations)
    }

    @Test
    fun unknownMutableSchemaValuesAreFullyEstimatedWithoutRetention() {
        val cache = ContextEstimateCache()
        val value = MutableSchemaValue("short")
        val tool = MutableSchemaTool().apply { schema = mapOf("extension" to value) }
        val first = cache.schemaTextTokens(tool, 4)
        assertEquals(uncachedSchemaText(tool.name, tool.description, tool.schema, 4), first)
        value.value = "变更".repeat(100)
        val second = cache.schemaTextTokens(tool, 4)
        assertEquals(uncachedSchemaText(tool.name, tool.description, tool.schema, 4), second)
        assertTrue(second > first)
        assertEquals(0, cache.stats.schemaEntries)
        assertEquals(0L, cache.stats.schemaBytes)
        assertEquals(2L, cache.stats.uncachedSchemas)
    }

    @Test
    fun cacheKeysSeparateMessageKindsAndTokenDensities() {
        val cache = ContextEstimateCache()
        val messages = listOf(
            AgentMessage.UserMessage(id = "shared", content = "a".repeat(17)),
            AgentMessage.AssistantMessage(id = "shared", content = "b".repeat(33)),
            AgentMessage.ToolResultMessage(id = "shared", messageId = "shared", toolName = "unknown", result = "c".repeat(65))
        )
        val tool = MutableBaseTool()
        repeat(2) {
            for (density in listOf(1, 3, 4, 8)) {
                messages.forEach { assertMessageText(cache, it, density) }
                assertSchemaText(cache, tool, density)
            }
        }
        assertEquals(12L, cache.stats.messageMaterializations)
        assertEquals(12L, cache.stats.messageHits)
        assertEquals(4L, cache.stats.schemaMaterializations)
        assertEquals(4L, cache.stats.schemaHits)
    }

    @Test
    fun messageAndSchemaEntryLimitsEvictLeastRecentlyUsedEntries() {
        val cache = ContextEstimateCache(maxMessageEntries = 2, maxSchemaEntries = 2)
        val messages = listOf("a", "b", "c").map { AgentMessage.UserMessage(id = it, content = it) }
        val tools = listOf("a", "b", "c").map { MutableBaseTool(name = it) }
        for (index in listOf(0, 1, 0, 2, 0, 1)) {
            assertMessageText(cache, messages[index])
            assertSchemaText(cache, tools[index])
            assertTrue(cache.stats.messageEntries <= 2)
            assertTrue(cache.stats.schemaEntries <= 2)
        }
        assertEquals(4L, cache.stats.messageMaterializations)
        assertEquals(2L, cache.stats.messageHits)
        assertEquals(4L, cache.stats.schemaMaterializations)
        assertEquals(2L, cache.stats.schemaHits)
        assertEquals(2, cache.stats.messageEntries)
        assertEquals(2, cache.stats.schemaEntries)
    }

    @Test
    fun heapBudgetsEvictBeforeEntryLimitsAndIncludeSnapshotTextAndKeys() {
        val probe = ContextEstimateCache()
        val a = AgentMessage.UserMessage(id = "a", content = "text")
        val b = a.copy(id = "b")
        val toolA = MutableBaseTool(name = "a")
        val toolB = MutableBaseTool(name = "b")
        assertMessageText(probe, a)
        assertSchemaText(probe, toolA)
        val messageWeight = probe.stats.messageBytes
        val schemaWeight = probe.stats.schemaBytes
        assertTrue(messageWeight >= 2L * (a.id.length + a.content.length))
        assertTrue(schemaWeight >= 2L * (toolA.name.length + toolA.description.length))
        val cache = ContextEstimateCache(maxMessageEntries = 20, maxMessageBytes = messageWeight * 2 - 1,
            maxSchemaEntries = 20, maxSchemaBytes = schemaWeight * 2 - 1)
        for ((message, tool) in listOf(a to toolA, b to toolB, a to toolA)) {
            assertMessageText(cache, message)
            assertSchemaText(cache, tool)
            assertEquals(1, cache.stats.messageEntries)
            assertEquals(messageWeight, cache.stats.messageBytes)
            assertEquals(1, cache.stats.schemaEntries)
            assertEquals(schemaWeight, cache.stats.schemaBytes)
        }
        assertEquals(3L, cache.stats.messageMaterializations)
        assertEquals(3L, cache.stats.schemaMaterializations)
    }

    @Test
    fun oversizedChangesAreFullyCountedAndRemoveOlderSnapshotsInsteadOfBeingCached() {
        val cache = ContextEstimateCache(maxMessageBytes = 8_192, maxSchemaBytes = 8_192)
        val small = AgentMessage.AssistantMessage(id = "same", content = "small",
            toolCalls = listOf(ToolCall("call", "readFile", mapOf("path" to JsonPrimitive("a.kt")))))
        val tool = MutableBaseTool()
        assertMessageText(cache, small)
        assertSchemaText(cache, tool)
        assertEquals(1, cache.stats.messageEntries)
        assertEquals(1, cache.stats.schemaEntries)
        val large = small.copy(toolCalls = listOf(ToolCall("call", "readFile", mapOf("path" to JsonPrimitive("大文本<&>".repeat(10_000))))))
        tool.description = "大定义<&>".repeat(10_000)
        repeat(2) {
            assertMessageText(cache, large)
            assertSchemaText(cache, tool)
            assertEquals(0, cache.stats.messageEntries)
            assertEquals(0L, cache.stats.messageBytes)
            assertEquals(0, cache.stats.schemaEntries)
            assertEquals(0L, cache.stats.schemaBytes)
        }
        assertEquals(3L, cache.stats.messageMaterializations)
        assertEquals(3L, cache.stats.schemaMaterializations)
        assertEquals(2L, cache.stats.uncachedMessages)
        assertEquals(2L, cache.stats.uncachedSchemas)
    }

    @Test
    fun manySmallNestedNodesCountAgainstHeapBudgetWithoutTruncatingTheEstimate() {
        val cache = ContextEstimateCache(maxMessageBytes = 8_192, maxSchemaBytes = 8_192)
        val message = AgentMessage.AssistantMessage(content = "",
            toolCalls = listOf(ToolCall("call", "tool", mapOf("items" to JsonArray(List(2_000) { JsonPrimitive("x") })))))
        val tool = MutableSchemaTool().apply {
            schema = mapOf("type" to "object", "enum" to List(2_000) { "x" })
        }
        repeat(2) {
            assertMessageText(cache, message)
            assertSchemaText(cache, tool)
        }
        assertEquals(0, cache.stats.messageEntries)
        assertEquals(0L, cache.stats.messageBytes)
        assertEquals(0, cache.stats.schemaEntries)
        assertEquals(0L, cache.stats.schemaBytes)
        assertEquals(2L, cache.stats.uncachedMessages)
        assertEquals(2L, cache.stats.uncachedSchemas)
    }

    @Test
    fun oversizedIdsAndToolNamesCountAgainstHeapBudget() {
        val cache = ContextEstimateCache(maxMessageBytes = 1_024, maxSchemaBytes = 8_192)
        val message = AgentMessage.UserMessage(id = "id".repeat(1_000), content = "small")
        val tool = MutableBaseTool(name = "name".repeat(1_000))
        repeat(2) {
            assertMessageText(cache, message)
            assertSchemaText(cache, tool)
        }
        assertEquals(0, cache.stats.messageEntries)
        assertEquals(0, cache.stats.schemaEntries)
        assertEquals(2L, cache.stats.uncachedMessages)
        assertEquals(2L, cache.stats.uncachedSchemas)
    }

    @Test
    fun zeroCapacityOrZeroBudgetNeverSkipsCompleteEstimation() {
        for (cache in listOf(
            ContextEstimateCache(maxMessageEntries = 0, maxSchemaEntries = 0),
            ContextEstimateCache(maxMessageBytes = 0, maxSchemaBytes = 0)
        )) {
            repeat(2) {
                assertMessageText(cache, AgentMessage.UserMessage(id = "id", content = "中文 ASCII\uD83D\uDE00"))
                assertSchemaText(cache, MutableBaseTool())
            }
            assertEquals(0, cache.stats.messageEntries)
            assertEquals(0L, cache.stats.messageBytes)
            assertEquals(0, cache.stats.schemaEntries)
            assertEquals(0L, cache.stats.schemaBytes)
            assertEquals(2L, cache.stats.messageMaterializations)
            assertEquals(2L, cache.stats.schemaMaterializations)
        }
    }

    @Test
    fun snapshotsDoNotRetainMessagesImagesBase64OrIgnoredMetadata() {
        val cache = ContextEstimateCache(maxMessageBytes = 16_384)
        val base64 = "PRIVATE_IMAGE_PAYLOAD".repeat(50_000)
        val signature = "PRIVATE_SIGNATURE".repeat(1_000)
        val thinking = "PRIVATE_THINKING".repeat(1_000)
        val rawResult = "PRIVATE_RAW_RESULT".repeat(1_000)
        val attachment = AgentImage("image/png", base64, "/private/image.png")
        val messages = listOf(
            AgentMessage.UserMessage(content = "user", images = listOf(attachment)),
            AgentMessage.AssistantMessage(content = "assistant", images = listOf(attachment), signature = signature, thinkingBlocksJson = thinking,
                toolCalls = listOf(ToolCall("call", "readFile", mapOf("path" to JsonPrimitive("a.kt"))))),
            AgentMessage.ToolResultMessage(toolName = "readFile", result = rawResult, modelResult = "short", images = listOf(attachment))
        )
        messages.forEach { assertMessageText(cache, it) }
        assertEquals(3, cache.stats.messageEntries)
        assertTrue(cache.stats.messageBytes < 16_384)
        val retained = messageCacheGraph(cache)
        assertFalse(retained.any { it is AgentMessage || it is AgentImage })
        assertFalse(retained.any { it is String && it in listOf(base64, signature, thinking, rawResult, attachment.path) })
        val bytes = cache.stats.messageBytes
        messages.forEach { assertMessageText(cache, it) }
        assertEquals(bytes, cache.stats.messageBytes)
        assertEquals(3L, cache.stats.messageMaterializations)
        assertEquals(3L, cache.stats.messageHits)
    }

    @Test
    fun imageCountsAreReadWithoutEnumeratingOrRetainingAttachmentsAndStillSaturate() {
        val images = object : AbstractList<AgentImage>() {
            override val size = 600_000
            override fun get(index: Int): AgentImage = throw AssertionError("Images must not be enumerated")
        }
        val message = AgentMessage.UserMessage(content = "request", images = images)
        val cache = ContextEstimateCache(maxMessageBytes = 2_048)
        assertMessageText(cache, message)
        assertEquals(1, cache.stats.messageEntries)
        repeat(2) {
            assertEquals(Int.MAX_VALUE, ContextTokenEstimator.estimate(message))
            assertEquals(uncachedBreakdown("system", listOf(message), emptyList(), 4),
                ContextTokenEstimator.breakdown("system", listOf(message), emptyList()))
        }
        val noImages = message.copy(images = emptyList())
        val oneImage = message.copy(images = listOf(image))
        assertEquals(4_096, ContextTokenEstimator.estimate(oneImage) - ContextTokenEstimator.estimate(noImages))
    }

    @Test
    fun concurrentIdenticalCallsMaterializeEachSnapshotOnce() {
        val cache = ContextEstimateCache()
        val message = AgentMessage.AssistantMessage(content = "文本", reasoning = "reasoning",
            toolCalls = listOf(ToolCall("call", "readFile", mapOf("path" to JsonPrimitive("a.kt")))))
        val tool = MutableBaseTool()
        val expectedMessage = ModelContextPolicy.estimateTextTokens(uncachedText(message), 4)
        val expectedSchema = uncachedSchemaText(tool, 4)
        val workers = Executors.newFixedThreadPool(8)
        val start = CountDownLatch(1)
        try {
            val futures = (0 until 8).map {
                workers.submit(Callable {
                    assertTrue(start.await(10, TimeUnit.SECONDS))
                    repeat(100) {
                        assertEquals(expectedMessage, cache.messageTextTokens(message, 4))
                        assertEquals(expectedSchema, cache.schemaTextTokens(tool, 4))
                    }
                })
            }
            start.countDown()
            futures.forEach { it.get(30, TimeUnit.SECONDS) }
        } finally {
            workers.shutdownNow()
            assertTrue(workers.awaitTermination(10, TimeUnit.SECONDS))
        }
        assertEquals(1L, cache.stats.messageMaterializations)
        assertEquals(1L, cache.stats.schemaMaterializations)
        assertEquals(799L, cache.stats.messageHits)
        assertEquals(799L, cache.stats.schemaHits)
    }

    @Test
    fun concurrentSameIdDifferentContentsSchemasAndDensitiesStayCorrectAndBounded() {
        val cache = ContextEstimateCache(maxMessageEntries = 4, maxMessageBytes = 65_536, maxSchemaEntries = 4, maxSchemaBytes = 65_536)
        val workers = Executors.newFixedThreadPool(6)
        val start = CountDownLatch(1)
        try {
            val futures = listOf(1, 3, 4, 8, 3, 4).mapIndexed { index, density ->
                val message = AgentMessage.AssistantMessage(id = "same", content = "ascii".repeat(index + 1), reasoning = "汉".repeat(index),
                    toolCalls = listOf(ToolCall("call", "tool", mapOf("value" to JsonPrimitive("value".repeat(index + 1))))))
                val tool = MutableBaseTool(name = "same", description = "定义".repeat(index + 1))
                val expectedMessage = ModelContextPolicy.estimateTextTokens(uncachedText(message), density)
                val expectedSchema = uncachedSchemaText(tool, density)
                workers.submit(Callable {
                    assertTrue(start.await(10, TimeUnit.SECONDS))
                    repeat(100) {
                        assertEquals(expectedMessage, cache.messageTextTokens(message, density))
                        assertEquals(expectedSchema, cache.schemaTextTokens(tool, density))
                        val stats = cache.stats
                        assertTrue(stats.messageEntries <= 4 && stats.messageBytes <= 65_536)
                        assertTrue(stats.schemaEntries <= 4 && stats.schemaBytes <= 65_536)
                    }
                })
            }
            start.countDown()
            futures.forEach { it.get(30, TimeUnit.SECONDS) }
        } finally {
            workers.shutdownNow()
            assertTrue(workers.awaitTermination(10, TimeUnit.SECONDS))
        }
    }

    @Test
    fun publicCalibrationAndEmptyBreakdownKeepExistingSemanticsAfterCacheWarmup() {
        val user = AgentMessage.UserMessage(content = "request")
        val result = AgentMessage.ToolResultMessage(toolName = "readFile", result = "汉".repeat(25_000))
        repeat(2) {
            val before = ContextTokenEstimator.estimate("system", listOf(user), emptyList())
            val after = ContextTokenEstimator.estimate("system", listOf(user, result), emptyList())
            assertEquals(135_014, ContextTokenEstimator.calibrated(after, before, 110_000))
            assertEquals(12_000, ContextTokenEstimator.calibrated(22_000, 20_000, 10_000))
            assertEquals(10_000, ContextTokenEstimator.calibrated(18_000, 20_000, 10_000))
            assertEquals(22_000, ContextTokenEstimator.calibrated(22_000, 20_000, 0))
            assertEquals(Int.MAX_VALUE, ContextTokenEstimator.calibrated(Int.MAX_VALUE, 0, Int.MAX_VALUE))
            assertEquals(ContextUsageBreakdown(), ContextTokenEstimator.breakdown("", emptyList(), emptyList()))
        }
    }

    private fun assertMessageText(cache: ContextEstimateCache, message: AgentMessage, density: Int = 4) {
        assertEquals(ModelContextPolicy.estimateTextTokens(uncachedText(message), density), cache.messageTextTokens(message, density))
    }

    private fun assertSchemaText(cache: ContextEstimateCache, tool: AgentTool, density: Int = 4) {
        assertEquals(uncachedSchemaText(tool, density), cache.schemaTextTokens(tool, density))
    }

    private fun uncachedSchemaText(tool: AgentTool, density: Int): Int =
        uncachedSchemaText(tool.name, tool.description, tool.toModelJsonSchema(), density)

    private fun uncachedSchemaText(name: String, description: String, schema: Map<String, Any>, density: Int): Int =
        ModelContextPolicy.estimateTextTokens(name + description + gson.toJson(schema), density)

    private fun uncachedText(message: AgentMessage): String = when (message) {
        is AgentMessage.UserMessage -> message.content
        is AgentMessage.AssistantMessage -> message.content + message.reasoning +
            message.toolCalls.joinToString { it.name + gson.toJson(it.arguments) }
        is AgentMessage.ToolResultMessage -> message.toolName +
            (message.modelResult ?: modelToolResultText(message.toolName, message.result) ?: message.result)
    }

    private fun imageCount(message: AgentMessage): Int = when (message) {
        is AgentMessage.UserMessage -> message.images.size
        is AgentMessage.AssistantMessage -> message.images.size
        is AgentMessage.ToolResultMessage -> message.images.size
    }

    private fun uncachedMessage(message: AgentMessage, density: Int): Int =
        (ModelContextPolicy.estimateTextTokens(uncachedText(message), density).toLong() + imageCount(message) * 4_096L + 12)
            .coerceAtMost(Int.MAX_VALUE.toLong()).toInt()

    private fun uncachedBreakdown(system: String, messages: List<AgentMessage>, tools: List<AgentTool>, density: Int): ContextUsageBreakdown {
        val definitions = tools.sumOf { uncachedSchemaText(it, density).toLong() + 16 }
        var chat = 0L
        var results = 0L
        var images = 0L
        messages.forEach { message ->
            val imageTokens = imageCount(message) * 4_096L
            val text = (uncachedMessage(message, density).toLong() - imageTokens).coerceAtLeast(0)
            if (message is AgentMessage.ToolResultMessage) results += text else chat += text
            images += imageTokens
        }
        return ContextUsageBreakdown(
            systemTokens = ModelContextPolicy.estimateTextTokens(system, density),
            toolDefinitionTokens = definitions.coerceAtMost(Int.MAX_VALUE.toLong()).toInt(),
            chatTokens = chat.coerceAtMost(Int.MAX_VALUE.toLong()).toInt(),
            toolResultTokens = results.coerceAtMost(Int.MAX_VALUE.toLong()).toInt(),
            imageTokens = images.coerceAtMost(Int.MAX_VALUE.toLong()).toInt()
        )
    }

    private fun messageCacheGraph(cache: ContextEstimateCache): Set<Any> {
        val lru = cache.javaClass.getDeclaredField("messages").apply { isAccessible = true }.get(cache)
        val entries = lru.javaClass.getDeclaredField("entries").apply { isAccessible = true }.get(lru)
        val visited = Collections.newSetFromMap(IdentityHashMap<Any, Boolean>())
        fun visit(value: Any?) {
            if (value == null || !visited.add(value)) return
            when (value) {
                is Map<*, *> -> value.forEach { (key, item) -> visit(key); visit(item) }
                is Iterable<*> -> value.forEach(::visit)
                is JsonPrimitive -> visit(value.content)
                is AgentMessage, is AgentImage, is String, is Number, is Boolean, is Enum<*> -> Unit
                else -> {
                    assertTrue(value.javaClass.name.startsWith(ContextEstimateCache::class.java.name + "\$") ||
                        value.javaClass.name.contains("WeightedLruCache\$"))
                    value.javaClass.declaredFields.filterNot { Modifier.isStatic(it.modifiers) }.forEach {
                        visit(it.apply { isAccessible = true }.get(value))
                    }
                }
            }
        }
        visit(entries)
        return visited
    }

    private class MutableSchemaValue(var value: String)

    private open class MutableBaseTool(
        override var name: String = "readFile",
        override var description: String = "Read file 内容"
    ) : AgentTool() {
        override var parameters: Map<String, ToolParameter> = linkedMapOf(
            "path" to ToolParameter("path", ParameterType.STRING, "File path <&>")
        )
        override var capabilities: Set<ToolCapability> = emptySet()
        override suspend fun execute(args: Map<String, JsonElement>): ToolResult = ToolResult.Success(JsonPrimitive("ok"))
    }

    private open class MutableSchemaTool : MutableBaseTool(name = "mcp__server__dynamic") {
        var schema: Map<String, Any> = linkedMapOf("type" to "object", "properties" to linkedMapOf<String, Any>())
        var schemaReads = 0
        override fun toJsonSchema(): Map<String, Any> {
            schemaReads++
            return schema
        }
    }
}
