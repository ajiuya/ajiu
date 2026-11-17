package com.aicode.feature.player.data

import android.content.Context
import com.aicode.feature.agent.domain.memory.ProjectMemoryCapture
import com.aicode.feature.agent.domain.memory.ProjectMemoryIdentity
import com.aicode.feature.agent.domain.memory.ProjectMemoryIdentityResolver
import com.aicode.feature.settings.data.repository.ExecutionMode
import com.aicode.feature.workspace.data.repository.WorkspaceRepository
import com.aicode.feature.workspace.domain.FileAccessProvider
import com.aicode.feature.workspace.domain.WorkspaceExecutionContext
import com.aicode.feature.workspace.domain.WorkspaceExecutionContextFactory
import com.aicode.feature.workspace.domain.model.Workspace
import com.aicode.feature.workspace.domain.model.WorkspaceType
import io.mockk.coEvery
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import java.io.File
import java.net.Socket
import java.net.URI
import java.util.concurrent.CancellationException
import java.util.concurrent.atomic.AtomicBoolean

internal class PlayerServerFixture(root: File, files: FileAccessProvider? = null, workspaceRoot: String = "/work/project") {
    val context = mockk<Context>()
    val workspaceRepository = mockk<WorkspaceRepository>()
    val identityResolver = mockk<ProjectMemoryIdentityResolver>()
    val executionFactory = mockk<WorkspaceExecutionContextFactory>()
    val backend = mockk<VideoCatalogRepository>()
    val current = MutableStateFlow<Workspace?>(files?.let { Workspace("fixture", workspaceRoot, WorkspaceType.REMOTE) })
    val environmentChanges = MutableSharedFlow<Unit>(extraBufferCapacity = 1)
    val valid = AtomicBoolean(true)
    val identity = ProjectMemoryIdentity("remote_ssh", "host.example", 22, "fixture", workspaceRoot)
    val namespace = if (files == null) stateNamespaceHash("network") else playerStateNamespace(identity)
    private val preferences = mutableMapOf<String, android.content.SharedPreferences>()

    init {
        every { context.cacheDir } returns root
        every { context.getSharedPreferences(any(), any()) } answers {
            synchronized(preferences) { preferences.getOrPut(firstArg()) { memoryPreferences() } }
        }
        every { context.getString(any()) } returns "Fixture"
        every { workspaceRepository.current } returns current
        every { identityResolver.environmentChanges } returns environmentChanges
        if (files != null) {
            val execution = WorkspaceExecutionContext(ExecutionMode.REMOTE_SSH, files.cacheIdentity(), files, null, null) {
                if (!valid.get()) throw CancellationException("Fixture environment changed")
            }
            coEvery { identityResolver.capture(workspaceRoot, any(), any()) } answers {
                ProjectMemoryCapture(identity, "1") { if (!valid.get()) throw CancellationException("Fixture environment changed") }
            }
            coEvery { executionFactory.freeze(any()) } returns execution
        }
    }

    fun server() = PlayerServer(context, workspaceRepository, identityResolver, executionFactory, backend, PlayerCopyBudget())
}

internal fun playerSessionPrefix(session: PlayerSession) = URI(session.url).path.substringBeforeLast('/') + "/"

internal fun playerHttp(session: PlayerSession, route: String, method: String = "GET", headers: Map<String, String> = emptyMap(),
    body: ByteArray = byteArrayOf()): String {
    val uri = URI(session.url)
    return Socket(uri.host, uri.port).use { socket ->
        socket.soTimeout = 5000
        val fields = mapOf("Host" to "${uri.host}:${uri.port}") + headers
        val request = buildString {
            append("$method $route HTTP/1.1\r\n")
            fields.forEach { (key, value) -> append("$key: $value\r\n") }
            append("\r\n")
        }
        socket.getOutputStream().apply { write(request.toByteArray()); write(body); flush() }
        socket.getInputStream().readBytes().toString(Charsets.UTF_8)
    }
}
