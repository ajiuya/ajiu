package com.aicode.feature.plugin.runtime

import com.aicode.feature.agent.domain.container.SshPortMappingManager
import com.aicode.feature.plugin.data.PluginRecord
import com.aicode.feature.plugin.domain.PluginCapture
import com.aicode.feature.plugin.domain.PluginContent
import com.aicode.feature.settings.data.repository.ExecutionMode
import com.aicode.feature.workspace.domain.WorkspaceExecutionContext
import io.mockk.Called
import io.mockk.mockk
import io.mockk.verify
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class PluginPortServiceTest {
    @get:Rule val temporary = TemporaryFolder()
    private val data by lazy { temporary.newFolder("data") }
    private val capture by lazy { PluginCapture(PluginRecord("fixture.ports", "1.0.0", true, 1,
        archiveSha256 = "archive", manifestSha256 = "manifest", fileHashes = emptyMap(), installRevision = "revision", contentHash = "hash"),
        mockk(), PluginContent(), temporary.newFolder("install"), {}, {}) }
    private val execution = WorkspaceExecutionContext(ExecutionMode.LOCAL_PROOT, "fixture", mockk(), null, null) {}
    private fun context(source: Any = "source") = PluginInvocationContext(capture, execution, "/workspace", null, data,
        MutableSharedFlow(), sourceIdentity = source) {}

    @Test fun localDirectPortDoesNotOpenOrCloseSharedSshTransports() = runBlocking<Unit> {
        val mappings = mockk<SshPortMappingManager>()
        val service = PluginPortService(mappings)
        val owner = context()
        val result = service.forward(owner, 3000, null)
        assertEquals("true", result.getValue("direct").jsonPrimitive.content)
        assertEquals("http://127.0.0.1:3000", result.getValue("url").jsonPrimitive.content)
        assertEquals(1, service.list(context()).size)
        service.close(owner, result.getValue("id").jsonPrimitive.content)
        assertEquals(0, service.list(owner).size)
        verify { mappings wasNot Called }
    }

    @Test fun arbitraryAndForeignSourceHandlesCannotCloseOrDiscoverPorts() = runBlocking<Unit> {
        val service = PluginPortService(mockk())
        val owner = context()
        val id = service.forward(owner, 3000, null).getValue("id").jsonPrimitive.content
        assertEquals(0, service.list(context("other")).size)
        for ((caller, handle) in listOf(context("other") to id, owner to "someone-elses-id")) {
            try { service.close(caller, handle); fail("Cross-owner close must fail") }
            catch (failure: PluginHostException) { assertEquals("PORT_NOT_FOUND", failure.code) }
        }
        assertEquals(1, service.list(owner).size)
        service.close(owner, id)
    }

    @Test fun directPortLimitAndLocalRebindingAreNotSilentlyEmulated() = runBlocking<Unit> {
        val service = PluginPortService(mockk())
        val owner = context()
        try { service.forward(owner, 3000, 4000); fail("Different local binding must not be faked") }
        catch (failure: PluginHostException) { assertEquals("LOCAL_PORT_UNSUPPORTED", failure.code) }
        val ids = (3000..3003).map { service.forward(owner, it, null).getValue("id").jsonPrimitive.content }
        try { service.forward(owner, 3004, null); fail("Port resource cap must apply") }
        catch (failure: PluginHostException) { assertEquals("LIMIT_REACHED", failure.code) }
        ids.forEach { service.close(owner, it) }
        assertEquals(0, service.list(owner).size)
    }
}
