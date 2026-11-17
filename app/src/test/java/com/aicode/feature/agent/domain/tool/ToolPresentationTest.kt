package com.aicode.feature.agent.domain.tool

import com.aicode.feature.agent.domain.mcp.McpClient
import com.aicode.feature.agent.domain.mcp.McpTool
import com.aicode.feature.agent.domain.mcp.McpToolDescriptor
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

class ToolPresentationTest {
    private class TestTool(
        override val name: String = "writeFile",
        override val capabilities: Set<ToolCapability> = emptySet(),
        override val parameters: Map<String, ToolParameter> = linkedMapOf(
            "path" to ToolParameter("path", ParameterType.STRING, "Path"),
            "content" to ToolParameter("content", ParameterType.STRING, "Content"),
            "overwrite" to ToolParameter("overwrite", ParameterType.BOOLEAN, "Overwrite", required = false)
        ),
        private val schema: Map<String, Any>? = null
    ) : AgentTool() {
        override val description = "Write file"
        var executed: Map<String, JsonElement>? = null

        override fun toJsonSchema(): Map<String, Any> = schema ?: super.toJsonSchema()

        override suspend fun execute(args: Map<String, JsonElement>): ToolResult {
            executed = args
            return ToolResult.Success(JsonNull)
        }
    }

    @Test
    fun modelSchemaAddsRequiredTitleBeforeCompleteBusinessParameters() {
        val tool = TestTool()
        val original = tool.toJsonSchema()
        val model = tool.toModelJsonSchema()
        val properties = model["properties"] as Map<*, *>

        assertEquals(listOf("tool_title", "path", "content", "overwrite"), properties.keys.toList())
        assertEquals(listOf("tool_title", "path", "content"), model["required"])
        assertEquals((original["properties"] as Map<*, *>), properties - "tool_title")
        assertEquals(mapOf("type" to "string", "description" to "Path"), properties["path"])
        assertFalse(tool.parameters.containsKey("tool_title"))
        assertFalse((original["properties"] as Map<*, *>).containsKey("tool_title"))
        assertFalse(model.containsKey("additionalProperties"))
        assertFalse(model.containsKey("strict"))
        assertEquals(model, tool.toModelJsonSchema())
    }

    @Test
    fun customSchemaKeepsNestedItemsEnumAndRootAttributes() {
        val schema = linkedMapOf<String, Any>(
            "type" to "object",
            "properties" to linkedMapOf(
                "edits" to mapOf(
                    "type" to "array",
                    "items" to mapOf(
                        "type" to "object",
                        "properties" to mapOf("mode" to mapOf("type" to "string", "enum" to listOf("a", "b"))),
                        "required" to listOf("mode")
                    )
                )
            ),
            "required" to listOf("edits"),
            "additionalProperties" to false
        )
        val model = TestTool(schema = schema).toModelJsonSchema()

        assertEquals(schema["additionalProperties"], model["additionalProperties"])
        assertSame((schema["properties"] as Map<*, *>)["edits"], (model["properties"] as Map<*, *>)["edits"])
        assertEquals(listOf("tool_title", "edits"), model["required"])
        assertEquals(listOf("edits"), schema["required"])
    }

    @Test
    fun mcpAndExternalSchemasAndArgumentsPassThroughUnchanged() {
        val schema = mapOf<String, Any>(
            "anyOf" to listOf(mapOf("type" to "object")),
            "properties" to mapOf("tool_title" to mapOf("type" to "integer"))
        )
        val args = mapOf("tool_title" to JsonPrimitive(7), "remote" to JsonPrimitive("value"))
        val tools = listOf(
            TestTool(name = "mcp__server__write", schema = schema),
            TestTool(name = "external", capabilities = setOf(ToolCapability.EXTERNAL_TOOL), schema = schema),
            TestTool(name = "mcp__server__empty", schema = emptyMap())
        )
        tools.forEach { tool ->
            assertSame(tool.toJsonSchema(), tool.toModelJsonSchema())
            assertSame(args, ToolPresentation.executionArguments(tool, args))
        }
    }

    @Test
    fun actualMcpToolKeepsRemoteSchemaAndSameNamedRemoteArgument() {
        val client = mockk<McpClient>()
        every { client.serverName } returns "remote"
        val schema = JsonObject(mapOf(
            "type" to JsonPrimitive("object"),
            "properties" to JsonObject(mapOf("tool_title" to JsonObject(mapOf("type" to JsonPrimitive("string")))))
        ))
        val tool = McpTool(client, McpToolDescriptor("run", inputSchema = schema))
        val args = mapOf("tool_title" to JsonPrimitive("Remote business value"))

        assertEquals(tool.toJsonSchema(), tool.toModelJsonSchema())
        assertFalse(tool.toModelJsonSchema().containsKey("required"))
        assertSame(args, ToolPresentation.executionArguments(tool, args))
    }

    @Test
    fun realTitleParameterIsNotOverwrittenOrRemoved() {
        val parameter = ToolParameter("tool_title", ParameterType.INTEGER, "Business title", required = false)
        val args = mapOf("tool_title" to JsonPrimitive(42))
        val tool = TestTool(parameters = mapOf("tool_title" to parameter))
        val schema = tool.toModelJsonSchema()
        assertEquals(emptyList<String>(), schema["required"])
        assertEquals("integer", ((schema["properties"] as Map<*, *>)["tool_title"] as Map<*, *>)["type"])
        assertSame(args, ToolPresentation.executionArguments(tool, args))

        val custom = TestTool(schema = schema)
        assertSame(schema, custom.toModelJsonSchema())
        assertSame(args, ToolPresentation.executionArguments(custom, args))
    }

    @Test
    fun executeReceivesOnlyBusinessArgumentsWithoutMutatingWireCall() = runTest {
        val tool = TestTool()
        val call = ToolCall("id", tool.name, linkedMapOf(
            "tool_title" to JsonPrimitive("Write configuration"),
            "path" to JsonPrimitive("config.json"),
            "content" to JsonPrimitive("full content"),
            "command" to JsonPrimitive("full command with all approval details")
        ))
        val original = JsonObject(call.arguments).toString()
        tool.execute(ToolPresentation.executionArguments(tool, call.arguments))

        assertFalse(tool.executed!!.containsKey("tool_title"))
        assertEquals(call.arguments - "tool_title", tool.executed)
        assertEquals(original, JsonObject(call.arguments).toString())
        assertEquals("Write configuration", ToolPresentation.titleOf(call))
    }

    @Test
    fun titlesAreStringOnlyControlFreeAndBoundedByUnicodeCodePoints() {
        fun title(value: JsonElement) = ToolPresentation.titleOf(ToolCall("id", "writeFile", mapOf("tool_title" to value)))

        assertNull(ToolPresentation.titleOf(ToolCall("id", "writeFile", emptyMap())))
        assertNull(title(JsonNull))
        assertNull(title(JsonPrimitive(42)))
        assertNull(title(JsonObject(emptyMap())))
        assertNull(title(JsonPrimitive(" \n\t\u200B\u202E ")))
        assertEquals("Readfile", title(JsonPrimitive("  Read\n\u0000\t\u202Efile\u2028  ")))
        val unicode = String(Character.toChars(0x1F4C4))
        val clipped = title(JsonPrimitive(unicode.repeat(60)))!!
        assertEquals(48, clipped.codePointCount(0, clipped.length))
        assertEquals(unicode.repeat(48), clipped)
        assertTrue(clipped.last().isLowSurrogate())
    }
}
