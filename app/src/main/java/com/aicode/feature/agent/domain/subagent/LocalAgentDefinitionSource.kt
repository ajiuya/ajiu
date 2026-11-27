package com.aicode.feature.agent.domain.subagent

import android.content.Context
import com.aicode.core.watch.ChangeRoot
import com.aicode.core.watch.FileChangeHub
import com.aicode.feature.agent.domain.container.RemoteSshConnection
import com.aicode.feature.agent.domain.plugin.PluginDefinitionReadOnlyException
import com.aicode.feature.settings.data.repository.ContainerProfileSnapshot
import com.aicode.feature.settings.data.repository.ContainerSettingsRepository
import com.aicode.feature.settings.data.repository.ExecutionMode
import com.aicode.feature.settings.data.repository.ExecutionModeHolder
import com.aicode.feature.workspace.data.repository.WorkspaceRepository
import com.aicode.feature.workspace.domain.RemoteAgentConfigCache
import com.aicode.feature.workspace.domain.WorkspaceExecutionContext
import dagger.hilt.android.qualifiers.ApplicationContext
import java.io.File
import java.io.IOException
import java.nio.ByteBuffer
import java.nio.channels.FileChannel
import java.nio.file.Files
import java.nio.file.LinkOption.NOFOLLOW_LINKS
import java.nio.file.NoSuchFileException
import java.nio.file.Path
import java.nio.file.StandardCopyOption.ATOMIC_MOVE
import java.nio.file.StandardCopyOption.REPLACE_EXISTING
import java.nio.file.StandardOpenOption.READ
import java.nio.file.StandardOpenOption.WRITE
import java.nio.file.attribute.BasicFileAttributes
import java.nio.file.attribute.PosixFilePermission
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.emitAll
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.merge
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive

internal class AgentDefinitionSourceChanged : CancellationException("Agent definition source changed")

/** App/project configuration only. Never opens a guest rootfs or starts a CHROOT execution. */
@Singleton
class LocalAgentDefinitionSource @Inject constructor(
    @param:ApplicationContext private val context: Context,
    private val workspace: WorkspaceRepository,
    private val modeHolder: ExecutionModeHolder,
    private val settings: ContainerSettingsRepository,
    private val remote: RemoteSshConnection,
    private val remoteCache: RemoteAgentConfigCache
) {
    internal data class Root(val original: File, val physical: Path, val key: Any?)
    internal data class Capture(val identity: String, val profile: ContainerProfileSnapshot,
        val workspacePath: String, val app: Root, val project: Root)
    private data class Stamp(val key: Any?, val size: Long, val modified: java.nio.file.attribute.FileTime)
    private val revision = MutableStateFlow(0L)
    private val mutationLock = Mutex()

    fun sourceIdentity(): String {
        val selection = workspace.selection.value
        val mode = modeHolder.currentMode()
        val peer = if (mode == ExecutionMode.REMOTE_SSH) remote.config?.let { "${it.host}:${it.port}:${it.remoteWorkspacePath}" } else null
        val channel = if (mode == ExecutionMode.REMOTE_SSH) remote.identityState.value?.generation else null
        val definitions = if (mode == ExecutionMode.REMOTE_SSH) remoteCache.definitionRevision else null
        return listOf(selection.id, selection.workspace?.path, mode.name, modeHolder.modeSelectionRevision,
            settings.activeProfileSelectionRevision, settings.profileConfigurationRevision, peer, channel, definitions, revision.value)
            .joinToString(":") { it?.toString()?.let { value -> "${value.length}:$value" } ?: "-" }
    }

    fun checkSourceIdentity(expected: String) {
        if (sourceIdentity() != expected) throw AgentDefinitionSourceChanged()
    }

    internal suspend fun capture(): Capture? = withContext(Dispatchers.IO) {
        currentCoroutineContext().ensureActive()
        WorkspaceExecutionContext.current()?.checkCurrent()
        val identity = sourceIdentity()
        val mode = modeHolder.currentMode()
        val profile = settings.captureProfileSnapshot()
        checkSourceIdentity(identity)
        if (mode != ExecutionMode.LOCAL_PROOT || !profile.activeProfile.isChroot) return@withContext null
        if (profile.selectedProfileId != profile.activeProfile.id) throw AgentDefinitionSourceChanged()
        val path = workspace.currentPath()
        Capture(identity, profile, path, root(context.filesDir), root(File(path))).also { check(it) }
    }

    private fun attributes(path: Path): BasicFileAttributes? = try {
        Files.readAttributes(path, BasicFileAttributes::class.java, NOFOLLOW_LINKS)
    } catch (_: NoSuchFileException) { null }

    private fun stamp(attributes: BasicFileAttributes) = Stamp(attributes.fileKey(), attributes.size(), attributes.lastModifiedTime())

    private fun root(file: File): Root {
        if (!file.isAbsolute) throw IOException("Invalid agent definition root")
        val physical = file.canonicalFile.toPath()
        val node = attributes(physical) ?: throw IOException("Missing agent definition root")
        if (!node.isDirectory || node.isSymbolicLink) throw IOException("Invalid agent definition root")
        return Root(file.absoluteFile, physical, node.fileKey())
    }

    internal suspend fun check(capture: Capture) {
        currentCoroutineContext().ensureActive()
        WorkspaceExecutionContext.current()?.checkCurrent()
        checkSourceIdentity(capture.identity)
        if (workspace.currentPath() != capture.workspacePath || context.filesDir.absoluteFile != capture.app.original) {
            throw AgentDefinitionSourceChanged()
        }
        try { settings.checkProfileSnapshot(capture.profile) } catch (_: CancellationException) {
            currentCoroutineContext().ensureActive()
            throw AgentDefinitionSourceChanged()
        }
        for (root in listOf(capture.app, capture.project)) {
            if (root.original.canonicalFile.toPath() != root.physical) throw AgentDefinitionSourceChanged()
            val node = attributes(root.physical) ?: throw AgentDefinitionSourceChanged()
            if (!node.isDirectory || node.isSymbolicLink || node.fileKey() != root.key) throw AgentDefinitionSourceChanged()
        }
        currentCoroutineContext().ensureActive()
    }

    private fun directory(capture: Capture, scope: AgentDefinitionScope): Path = when (scope) {
        AgentDefinitionScope.GLOBAL -> capture.app.physical.resolve("aicode")
        AgentDefinitionScope.PROJECT -> capture.project.physical.resolve(".aicode")
        AgentDefinitionScope.PLUGIN -> throw PluginDefinitionReadOnlyException()
    }

    private fun requireReadable(path: Path, directory: Boolean) {
        if (!Files.isReadable(path) || (directory && !Files.isExecutable(path))) throw IOException("Agent definition is unreadable")
        val permissions = try { Files.getPosixFilePermissions(path, NOFOLLOW_LINKS) }
        catch (_: UnsupportedOperationException) { return }
        if (permissions.none { it in setOf(PosixFilePermission.OWNER_READ, PosixFilePermission.GROUP_READ, PosixFilePermission.OTHERS_READ) } ||
            (directory && permissions.none { it in setOf(PosixFilePermission.OWNER_EXECUTE, PosixFilePermission.GROUP_EXECUTE, PosixFilePermission.OTHERS_EXECUTE) })) {
            throw IOException("Agent definition is unreadable")
        }
    }

    private suspend fun checkedNode(capture: Capture, path: Path): BasicFileAttributes? {
        check(capture)
        val root = if (path.startsWith(capture.project.physical)) capture.project.physical else capture.app.physical
        if (!path.startsWith(root) || path.normalize() != path) throw IOException("Invalid agent definition path")
        var current = root
        var node = attributes(current)
        for (part in root.relativize(path)) {
            if (node != null) {
                if (!node.isDirectory) throw IOException("Invalid agent definition parent")
                requireReadable(current, directory = true)
            }
            current = current.resolve(part)
            node = attributes(current)
            if (node?.isSymbolicLink == true) throw IOException("Unsupported agent definition link")
        }
        check(capture)
        return node
    }

    private suspend fun readText(capture: Capture, path: Path): String? {
        val before = checkedNode(capture, path) ?: return null
        if (!before.isRegularFile || before.size() > MAX_BYTES) throw IOException("Invalid agent definition file")
        requireReadable(path, directory = false)
        val bytes = FileChannel.open(path, READ, NOFOLLOW_LINKS).use { channel ->
            val buffer = ByteBuffer.allocate(before.size().toInt() + 1)
            while (buffer.hasRemaining() && channel.read(buffer) >= 0) check(capture)
            if (buffer.position() != before.size().toInt()) throw IOException("Agent definition changed while reading")
            buffer.array().copyOf(buffer.position())
        }
        val after = checkedNode(capture, path) ?: throw IOException("Agent definition disappeared")
        if (stamp(before) != stamp(after)) throw IOException("Agent definition changed while reading")
        return bytes.toString(Charsets.UTF_8)
    }

    internal suspend fun definitions(capture: Capture, scope: AgentDefinitionScope): List<AgentDefinition> {
        val root = directory(capture, scope).resolve("agents")
        val before = checkedNode(capture, root) ?: return emptyList()
        if (!before.isDirectory) throw IOException("Invalid agent definition directory")
        requireReadable(root, directory = true)
        val children = Files.newDirectoryStream(root).use { stream ->
            stream.asSequence().take(MAX_ENTRIES + 1).toList().also {
                if (it.size > MAX_ENTRIES) throw IOException("Agent definition scan limit exceeded")
            }
        }
        val logicalRoot = if (scope == AgentDefinitionScope.GLOBAL) "~/.aicode/agents" else "~/workspace/.aicode/agents"
        val result = children.filter { it.fileName.toString().endsWith(".md", ignoreCase = true) }.mapNotNull { path ->
            val node = checkedNode(capture, path) ?: throw IOException("Agent definition disappeared")
            if (node.isDirectory) null else readText(capture, path)?.let {
                AgentDefinitionParser.parseText(it, "$logicalRoot/${path.fileName}")
            }
        }
        val after = checkedNode(capture, root) ?: throw IOException("Agent definition directory disappeared")
        if (stamp(before) != stamp(after)) throw IOException("Agent definition directory changed")
        check(capture)
        return result.sortedBy { it.name.lowercase() }
    }

    private suspend fun disabled(capture: Capture, scope: AgentDefinitionScope): Set<String> {
        val text = readText(capture, directory(capture, scope).resolve("agents.json")) ?: return emptySet()
        val json = Json.parseToJsonElement(text) as? JsonObject ?: throw IOException("Invalid agent configuration")
        val values = json["disabled"] ?: return emptySet()
        return (values as? JsonArray ?: throw IOException("Invalid disabled agents")).map {
            val value = it as? JsonPrimitive ?: throw IOException("Invalid disabled agent name")
            if (!value.isString) throw IOException("Invalid disabled agent name")
            value.content.lowercase()
        }.toSet()
    }

    internal suspend fun disabledNames(capture: Capture): Set<String> =
        (disabled(capture, AgentDefinitionScope.GLOBAL) + disabled(capture, AgentDefinitionScope.PROJECT)).also { check(capture) }

    private suspend fun ensureDirectory(capture: Capture, path: Path) {
        val node = checkedNode(capture, path)
        if (node == null) {
            check(capture)
            Files.createDirectory(path)
        } else if (!node.isDirectory) throw IOException("Invalid agent configuration directory")
        checkedNode(capture, path)
    }

    private suspend fun write(capture: Capture, path: Path, text: String) {
        val bytes = text.toByteArray(Charsets.UTF_8)
        if (bytes.size > MAX_BYTES) throw IOException("Agent definition is too large")
        val before = checkedNode(capture, path)
        if (before != null && !before.isRegularFile) throw IOException("Invalid agent definition file")
        val parent = path.parent
        val parentKey = checkedNode(capture, parent)?.fileKey() ?: throw IOException("Missing agent definition directory")
        val staged = Files.createTempFile(parent, ".agent-", ".tmp")
        try {
            check(capture)
            FileChannel.open(staged, WRITE, NOFOLLOW_LINKS).use { channel ->
                val buffer = ByteBuffer.wrap(bytes)
                while (buffer.hasRemaining()) { check(capture); channel.write(buffer) }
                channel.force(true)
            }
            if (checkedNode(capture, parent)?.fileKey() != parentKey ||
                checkedNode(capture, path)?.let(::stamp) != before?.let(::stamp)) throw IOException("Agent definition changed while writing")
            check(capture)
            Files.move(staged, path, ATOMIC_MOVE, REPLACE_EXISTING)
            check(capture)
        } finally {
            try {
                check(capture)
                if (checkedNode(capture, parent)?.fileKey() == parentKey) Files.deleteIfExists(staged)
            } catch (_: Exception) { currentCoroutineContext().ensureActive() }
        }
    }

    internal suspend fun setDisabled(capture: Capture, name: String, value: Boolean, scope: AgentDefinitionScope) = mutationLock.withLock {
        val names = disabled(capture, scope).toMutableSet()
        if (value) names.add(name.lowercase()) else names.remove(name.lowercase())
        val parent = directory(capture, scope)
        ensureDirectory(capture, parent)
        write(capture, parent.resolve("agents.json"), AgentDefinitionConfigRepository.serializeDisabled(names))
        revision.update { it + 1 }
    }

    internal suspend fun save(capture: Capture, form: AgentDefinitionForm, scope: AgentDefinitionScope, originalName: String?): AgentSaveError? = mutationLock.withLock {
        val entries = definitions(capture, scope)
        val name = form.name.trim()
        val self = originalName?.equals(name, ignoreCase = true) == true
        if (!self && entries.any { it.name.equals(name, ignoreCase = true) }) return@withLock AgentSaveError.NAME_CONFLICT
        val old = entries.firstOrNull { it.name.equals(originalName, ignoreCase = true) }
        val parent = directory(capture, scope)
        ensureDirectory(capture, parent)
        val root = parent.resolve("agents")
        ensureDirectory(capture, root)
        val oldPath = old?.filePath?.substringAfterLast('/')?.let(root::resolve)
        val target = if (self && oldPath != null) oldPath else root.resolve("$name.md")
        write(capture, target, AgentDefinitionParser.serialize(name, form.description, form.providerId, form.model,
            form.reasoningEffort, form.allowedTools, form.disallowedTools, form.inject, form.prompt))
        if (!self && oldPath != null && oldPath != target) {
            checkedNode(capture, oldPath)
            check(capture)
            Files.delete(oldPath)
        }
        check(capture)
        revision.update { it + 1 }
        null
    }

    internal suspend fun delete(capture: Capture, name: String, scope: AgentDefinitionScope): Boolean = mutationLock.withLock {
        val entry = definitions(capture, scope).firstOrNull { it.name.equals(name, true) } ?: return@withLock false
        val path = directory(capture, scope).resolve("agents").resolve(requireNotNull(entry.filePath).substringAfterLast('/'))
        if (checkedNode(capture, path)?.isRegularFile != true) return@withLock false
        check(capture)
        Files.delete(path)
        check(capture)
        revision.update { it + 1 }
        true
    }

    internal fun sourceSignals(): Flow<Unit> = combine(combine(workspace.selection, modeHolder.mode,
        modeHolder.modeSelectionRevisions, settings.activeProfileSelectionRevisions, settings.profileConfigurationRevisions
    ) { _, _, _, _, _ -> Unit }, revision) { _, _ -> Unit }

    internal fun changes(capture: Capture, hub: FileChangeHub): Flow<Unit> = flow {
        val global = directory(capture, AgentDefinitionScope.GLOBAL)
        val project = directory(capture, AgentDefinitionScope.PROJECT)
        checkedNode(capture, global.resolve("agents"))
        checkedNode(capture, project.resolve("agents"))
        emitAll(merge(
            hub.watchHostDir(global.resolve("agents").toFile(), "/root/.aicode/agents", ChangeRoot.AICODE, recursive = true),
            hub.watchHostDir(project.resolve("agents").toFile(), "~/workspace/.aicode/agents", ChangeRoot.WORKSPACE, recursive = true),
            hub.watchHostDir(global.toFile(), "/root/.aicode", ChangeRoot.AICODE),
            hub.watchHostDir(project.toFile(), "~/workspace/.aicode", ChangeRoot.WORKSPACE)
        ).map { check(capture); Unit })
    }

    private companion object {
        const val MAX_ENTRIES = 4096
        const val MAX_BYTES = 1024 * 1024
    }
}
