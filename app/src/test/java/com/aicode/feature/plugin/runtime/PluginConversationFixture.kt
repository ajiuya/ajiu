package com.aicode.feature.plugin.runtime

import com.aicode.feature.agent.domain.provider.ProviderInstanceFactory
import com.aicode.feature.plugin.data.PluginCapabilityRepository
import com.aicode.feature.plugin.domain.PluginCapability
import com.aicode.feature.plugin.domain.PluginCapture
import com.aicode.feature.plugin.domain.PluginCoreFixtures
import com.aicode.feature.plugin.domain.PluginManager
import com.aicode.feature.plugin.domain.PluginOperationResult
import com.aicode.feature.settings.data.remote.ModelMetadataService
import com.aicode.feature.settings.data.repository.GeneralSettingsRepository
import com.aicode.feature.settings.data.repository.ProviderKeyRotator
import com.aicode.feature.settings.domain.model.ModelMetadata
import io.mockk.coEvery
import io.mockk.mockk
import java.io.File
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.merge
import kotlinx.coroutines.flow.map

internal class PluginConversationFixture(root: File) {
    val manager = PluginManager(File(root, "plugins"), File(root, "registry/state.json"))
    val capabilities = PluginCapabilityRepository(File(root, "capabilities/state.enc"), manager, { it }, { it })
    val source = Any()
    val changes = MutableSharedFlow<Unit>(extraBufferCapacity = 16)
    var current = true
    lateinit var capture: PluginCapture
    val metadata = mockk<ModelMetadataService>()
    val general = mockk<GeneralSettingsRepository>()
    val rotator = ProviderKeyRotator()
    val factory = ProviderInstanceFactory(mockk(), mockk(), mockk(), metadata, general, rotator)

    suspend fun install(): PluginCapture {
        val manifest = PluginCoreFixtures.manifest(contributions = """
            "modelAccess":{"purpose":"Run model completions"},
            "providers":[{"id":"local","name":"Local","format":"openai","baseUrl":"http://127.0.0.1:8787/v1"}],
            "sessionSources":[{"id":"history","title":"History","handlers":{"listSessions":"sessions.list","readSession":"sessions.read","sendMessage":"sessions.send"}}]
        """.trimIndent(), runtime = """{"type":"python","entry":"module.py"}""")
        PluginCoreFixtures.install(manager, PluginCoreFixtures.archive(manifest, listOf("module.py" to ByteArray(0))))
        capture = PluginCoreFixtures.activate(manager)
        coEvery { metadata.resolve(any(), any(), any()) } answers {
            ModelMetadata(thirdArg(), contextTokens = 64_000, outputTokens = 4096, supportsTools = true, supportsVision = true)
        }
        coEvery { general.firstByteTimeoutMs() } returns 20_000
        coEvery { general.streamIdleTimeoutMs() } returns 15_000
        coEvery { general.maxNetworkRetries() } returns 2
        return capture
    }

    suspend fun grantModel() {
        val challenge = requireNotNull(capabilities.prepareGrant(capture, PluginCapability.MODEL_ACCESS))
        check(capabilities.confirmGrant(challenge.nativeToken) == PluginOperationResult.Success)
    }

    fun context(workspace: String = "/work", session: String? = "chat-A", identity: Any = source, background: Boolean = false,
        execution: com.aicode.feature.workspace.domain.WorkspaceExecutionContext? = null): PluginInvocationContext {
        val data = manager.dataDirectory(capture.id)
        data.mkdirs()
        return PluginInvocationContext(capture, execution, workspace, session, data,
            merge(changes, manager.snapshot.map { Unit }),
            sourceIdentity = identity, background = background) {
            if (!current) throw kotlinx.coroutines.CancellationException("Fixture source changed")
        }
    }
}
