package com.aicode.feature.agent.domain.tool

import android.content.Context
import com.aicode.feature.agent.domain.container.ContainerInstaller
import com.aicode.feature.workspace.domain.FileAccessProvider
import com.aicode.feature.workspace.domain.PathHomeResolver
import io.mockk.confirmVerified
import io.mockk.mockk
import io.mockk.verify
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import org.junit.Assert.assertSame
import org.junit.Test

class HostToolOutputStoreTest {
    private val installer = mockk<ContainerInstaller>()
    private val files = mockk<FileAccessProvider>()
    private val home = mockk<PathHomeResolver>()
    private val context = mockk<Context>()
    private val store = ToolOutputStore(installer, files, home, context)

    @Test
    fun hostRootAndShizukuLargeTextNeverUsesTheWorkspaceStorageBackend() {
        for (name in listOf("Root", "Shizuku")) {
            val original = ToolResult.Success(JsonPrimitive("private-host-output".repeat(4_000)))
            assertSame(original, store.process(name, "call", original))
        }
        verify(exactly = 0) { installer.aicodeDir }
        confirmVerified(installer, files, home, context)
    }

    @Test
    fun hostStructuredPartialAndErrorResultsNeverArchiveToAnSshWorkspace() {
        for (name in listOf("Root", "Shizuku")) {
            val payload = buildJsonObject { put("output", "private-host-output".repeat(4_000)) }
            val results = listOf(
                ToolResult.Success(payload),
                ToolResult.Partial(payload, "partial"),
                ToolResult.Error("private-host-output".repeat(4_000), "HOST_FAILED")
            )
            for (original in results) assertSame(original, store.process(name, "call", original))
        }
        confirmVerified(installer, files, home, context)
    }

    @Test
    fun ordinarySmallWorkspaceToolResultsRemainInline() {
        val text = JsonPrimitive("workspace output")
        val result = store.process("Bash", "call", ToolResult.Success(text)) as ToolResult.Success
        assertSame(text, result.data)
        confirmVerified(installer, files, home, context)
    }
}
