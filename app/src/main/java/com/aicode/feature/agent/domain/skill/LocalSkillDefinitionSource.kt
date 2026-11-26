package com.aicode.feature.agent.domain.skill

import android.content.Context
import com.aicode.core.watch.ChangeRoot
import com.aicode.core.watch.FileChangeHub
import com.aicode.feature.agent.domain.container.RemoteSshConnection
import com.aicode.feature.settings.data.repository.ContainerProfileSnapshot
import com.aicode.feature.settings.data.repository.ContainerSettingsRepository
import com.aicode.feature.settings.data.repository.ExecutionMode
import com.aicode.feature.settings.data.repository.ExecutionModeHolder
import com.aicode.feature.workspace.data.repository.WorkspaceRepository
import com.aicode.feature.workspace.domain.RemoteAgentConfigCache
import com.aicode.feature.workspace.domain.WorkspaceExecutionContext
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.emitAll
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.merge
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import java.io.File
import java.io.IOException
import java.nio.channels.Channels
import java.nio.channels.FileChannel
import java.nio.charset.StandardCharsets
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

internal class SkillDefinitionSourceChanged : CancellationException("Skill definition source changed")

@OptIn(ExperimentalCoroutinesApi::class)
@Singleton
class LocalSkillDefinitionSource @Inject constructor(
    @param:ApplicationContext private val context: Context,
    private val workspace: WorkspaceRepository,
    private val modeHolder: ExecutionModeHolder,
    private val settings: ContainerSettingsRepository,
    private val remote: RemoteSshConnection,
    private val remoteCache: RemoteAgentConfigCache
) {
    internal data class Root(val original: File, val physical: File, val node: Any?)
    internal data class Capture(
        val identity: String,
        val profile: ContainerProfileSnapshot,
        val workspacePath: String,
        val app: Root,
        val project: Root
    )
    private data class Node(val path: Path, val key: Any?, val size: Long, val modified: java.nio.file.attribute.FileTime)
    private data class Instruction(val path: Path, val directory: String, val node: Node)

    private val revision = MutableStateFlow(0L)
    private val configLock = Mutex()

    fun sourceIdentity(): String {
        val selection = workspace.selection.value
        val mode = modeHolder.currentMode()
        val peer = if (mode == ExecutionMode.REMOTE_SSH) remote.config?.let { "${it.host}:${it.port}:${it.remoteWorkspacePath}" } else null
        val channel = if (mode == ExecutionMode.REMOTE_SSH) remote.identityState.value?.generation else null
        val definitions = if (mode == ExecutionMode.REMOTE_SSH) remoteCache.definitionRevision else null
        return listOf(selection.id, selection.workspace?.path, mode.name, modeHolder.modeSelectionRevision,
            settings.activeProfileSelectionRevision, settings.profileConfigurationRevision, peer, channel, definitions, revision.value)
            .joinToString(":") { value -> value?.toString()?.let { "${it.length}:$it" } ?: "-" }
    }

    fun checkSourceIdentity(expected: String) {
        if (sourceIdentity() != expected) throw SkillDefinitionSourceChanged()
    }

    internal suspend fun capture(): Capture? = withContext(Dispatchers.IO) {
        currentCoroutineContext().ensureActive()
        WorkspaceExecutionContext.current()?.checkCurrent()
        val identity = sourceIdentity()
        val mode = modeHolder.currentMode()
        val profile = settings.captureProfileSnapshot()
        checkSourceIdentity(identity)
        WorkspaceExecutionContext.current()?.checkCurrent()
        if (mode != ExecutionMode.LOCAL_PROOT || !profile.activeProfile.isChroot) return@withContext null
        if (profile.activeProfile.id != profile.selectedProfileId) throw SkillDefinitionSourceChanged()
        val path = workspace.currentPath()
        val captured = Capture(identity, profile, path, root(context.filesDir), root(File(path)))
        check(captured)
        captured
    }

    private fun attributes(path: Path): BasicFileAttributes? = try {
        Files.readAttributes(path, BasicFileAttributes::class.java, NOFOLLOW_LINKS)
    } catch (_: NoSuchFileException) {
        null
    }

    private fun root(file: File): Root {
        if (!file.isAbsolute) throw IOException("Invalid local skill definition root")
        val absolute = file.absoluteFile
        val physical = absolute.canonicalFile
        val node = attributes(physical.toPath()) ?: throw IOException("Local skill definition root is missing")
        if (!node.isDirectory || node.isSymbolicLink) throw IOException("Invalid local skill definition root")
        return Root(absolute, physical, node.fileKey())
    }

    internal suspend fun check(capture: Capture) {
        currentCoroutineContext().ensureActive()
        WorkspaceExecutionContext.current()?.checkCurrent()
        checkSourceIdentity(capture.identity)
        if (workspace.currentPath() != capture.workspacePath || context.filesDir.absoluteFile != capture.app.original) {
            throw SkillDefinitionSourceChanged()
        }
        try {
            settings.checkProfileSnapshot(capture.profile)
        } catch (failure: CancellationException) {
            currentCoroutineContext().ensureActive()
            throw SkillDefinitionSourceChanged()
        }
        for (root in listOf(capture.app, capture.project)) {
            if (root.original.canonicalFile != root.physical) throw SkillDefinitionSourceChanged()
            val node = attributes(root.physical.toPath()) ?: throw SkillDefinitionSourceChanged()
            if (!node.isDirectory || node.isSymbolicLink || node.fileKey() != root.node) throw SkillDefinitionSourceChanged()
        }
        currentCoroutineContext().ensureActive()
        WorkspaceExecutionContext.current()?.checkCurrent()
    }

    private fun configDirectory(capture: Capture, scope: SkillScope): Path = when (scope) {
        SkillScope.GLOBAL -> capture.app.physical.toPath().resolve("aicode")
        SkillScope.PROJECT -> capture.project.physical.toPath().resolve(".aicode")
        SkillScope.PLUGIN -> throw com.aicode.feature.agent.domain.plugin.PluginDefinitionReadOnlyException()
    }

    private suspend fun checkedNode(capture: Capture, path: Path): BasicFileAttributes? {
        check(capture)
        val root = if (path.startsWith(capture.project.physical.toPath())) capture.project.physical.toPath()
            else capture.app.physical.toPath()
        if (!path.startsWith(root)) throw IOException("Invalid local skill definition path")
        var current = root
        var result = attributes(current)
        root.relativize(path).forEach { part ->
            if (result != null && !result!!.isDirectory) throw IOException("Invalid local skill definition parent")
            if (result != null) requireReadable(current, directory = true)
            current = current.resolve(part)
            result = attributes(current)
            if (result?.isSymbolicLink == true) throw IOException("Unsupported local skill definition link")
        }
        check(capture)
        return result
    }

    private fun requireReadable(path: Path, directory: Boolean) {
        if (!Files.isReadable(path) || (directory && !Files.isExecutable(path))) throw IOException("Local skill definition is unreadable")
        val permissions = try { Files.getPosixFilePermissions(path, NOFOLLOW_LINKS) }
        catch (_: UnsupportedOperationException) { return }
        if (permissions.none { it in setOf(PosixFilePermission.OWNER_READ, PosixFilePermission.GROUP_READ, PosixFilePermission.OTHERS_READ) } ||
            (directory && permissions.none { it in setOf(PosixFilePermission.OWNER_EXECUTE, PosixFilePermission.GROUP_EXECUTE, PosixFilePermission.OTHERS_EXECUTE) })) {
            throw IOException("Local skill definition is unreadable")
        }
    }

    private fun node(path: Path, attributes: BasicFileAttributes) =
        Node(path, attributes.fileKey(), attributes.size(), attributes.lastModifiedTime())

    private suspend fun readText(capture: Capture, path: Path): String? {
        val before = checkedNode(capture, path) ?: return null
        if (!before.isRegularFile) throw IOException("Local skill definition is not a regular file")
        requireReadable(path, directory = false)
        val expected = node(path, before)
        val text = FileChannel.open(path, READ, NOFOLLOW_LINKS).use { channel ->
            Channels.newReader(channel, StandardCharsets.UTF_8.newDecoder(), -1).readText()
        }
        check(capture)
        val after = checkedNode(capture, path) ?: throw IOException("Local skill definition changed while reading")
        if (node(path, after) != expected) throw IOException("Local skill definition changed while reading")
        return text
    }

    internal suspend fun skills(capture: Capture, scope: SkillScope): List<Skill> {
        val root = configDirectory(capture, scope).resolve("skills")
        val instructions = mutableListOf<Instruction>()
        val directories = mutableListOf<Node>()
        var visited = 0
        suspend fun walk(directory: Path, depth: Int) {
            val before = checkedNode(capture, directory) ?: return
            if (!before.isDirectory) throw IOException("Local skill definition directory is invalid")
            val stamp = node(directory, before)
            directories.add(stamp)
            requireReadable(directory, directory = true)
            val children = Files.newDirectoryStream(directory).use { stream ->
                val entries = mutableListOf<Path>()
                for (child in stream) {
                    if (++visited > MAX_ENTRIES) throw IOException("Local skill definition scan limit exceeded")
                    entries.add(child)
                }
                entries.sortedBy { it.fileName.toString() }
            }
            val candidates = mutableListOf<Path>()
            for (child in children) {
                val attributes = checkedNode(capture, child) ?: throw IOException("Local skill definition changed while scanning")
                if (attributes.isRegularFile && (child.fileName.toString().equals("SKILL.md", true) ||
                        child.fileName.toString().equals("CLAUDE.md", true))) candidates.add(child)
                if (attributes.isDirectory && depth < MAX_DEPTH - 1) walk(child, depth + 1)
            }
            val selected = candidates.firstOrNull { it.fileName.toString().equals("SKILL.md", true) } ?: candidates.firstOrNull()
            if (selected != null) {
                val attributes = checkedNode(capture, selected) ?: throw IOException("Local skill definition changed while scanning")
                val relative = root.relativize(directory).toString().replace(File.separatorChar, '/')
                val logicalRoot = if (scope == SkillScope.GLOBAL) "~/.aicode/skills" else "~/workspace/.aicode/skills"
                val logical = if (relative.isEmpty()) logicalRoot else "$logicalRoot/$relative"
                instructions.add(Instruction(selected, logical, node(selected, attributes)))
            }
            val after = checkedNode(capture, directory) ?: throw IOException("Local skill definition changed while scanning")
            if (node(directory, after) != stamp) throw IOException("Local skill definition changed while scanning")
        }
        walk(root, 0)
        val skills = instructions.map { instruction ->
            val text = readText(capture, instruction.path) ?: throw IOException("Local skill definition disappeared")
            val after = checkedNode(capture, instruction.path) ?: throw IOException("Local skill definition disappeared")
            if (node(instruction.path, after) != instruction.node) throw IOException("Local skill definition changed while reading")
            SkillParser.parseText(text, instruction.directory.substringAfterLast('/')).copy(dirPath = instruction.directory)
        }
        for (directory in directories) {
            val after = checkedNode(capture, directory.path) ?: throw IOException("Local skill definition directory disappeared")
            if (node(directory.path, after) != directory) throw IOException("Local skill definition directory changed")
        }
        check(capture)
        return skills.sortedBy { it.name.lowercase() }
    }

    private fun parseDisabled(text: String): Set<String> {
        val objectValue = JSON.parseToJsonElement(text) as? JsonObject ?: throw IOException("Invalid local skill configuration")
        val disabled = objectValue["disabled"] ?: return emptySet()
        val values = disabled as? JsonArray ?: throw IOException("Invalid local skill disabled list")
        return values.map { value ->
            val name = value as? JsonPrimitive ?: throw IOException("Invalid local skill disabled name")
            if (!name.isString) throw IOException("Invalid local skill disabled name")
            name.content.lowercase()
        }.toSet()
    }

    internal suspend fun disabledNames(capture: Capture): Set<String> {
        val global = readDisabled(capture, SkillScope.GLOBAL)
        val project = readDisabled(capture, SkillScope.PROJECT)
        check(capture)
        return global + project
    }

    private suspend fun readDisabled(capture: Capture, scope: SkillScope): Set<String> =
        readText(capture, configDirectory(capture, scope).resolve("skills.json"))?.let(::parseDisabled) ?: emptySet()

    internal suspend fun setDisabled(capture: Capture, name: String, disabled: Boolean, scope: SkillScope) {
        configLock.withLock {
                check(capture)
                val names = readDisabled(capture, scope).toMutableSet()
                if (disabled) names.add(name.lowercase()) else names.remove(name.lowercase())
                val directory = configDirectory(capture, scope)
                val file = directory.resolve("skills.json")
                val existing = checkedNode(capture, file)?.let { node(file, it) }
                if (checkedNode(capture, directory) == null) {
                    check(capture)
                    Files.createDirectory(directory)
                }
                val directoryNode = checkedNode(capture, directory)
                if (directoryNode?.isDirectory != true) throw IOException("Invalid local skill configuration directory")
                val directoryKey = directoryNode.fileKey()
                val staged = Files.createTempFile(directory, ".skills-", ".tmp")
                try {
                    check(capture)
                    FileChannel.open(staged, WRITE, NOFOLLOW_LINKS).use { channel ->
                        val bytes = StandardCharsets.UTF_8.encode(SkillConfigRepository.serializeDisabled(names))
                        while (bytes.hasRemaining()) {
                            check(capture)
                            channel.write(bytes)
                        }
                        channel.force(true)
                    }
                    check(capture)
                    if (checkedNode(capture, directory)?.fileKey() != directoryKey ||
                        checkedNode(capture, file)?.let { node(file, it) } != existing) throw IOException("Local skill configuration changed while writing")
                    Files.move(staged, file, ATOMIC_MOVE, REPLACE_EXISTING)
                    check(capture)
                    if (checkedNode(capture, directory)?.fileKey() != directoryKey ||
                        checkedNode(capture, file)?.isRegularFile != true) throw IOException("Local skill configuration write unavailable")
                } finally {
                    try {
                        check(capture)
                        if (checkedNode(capture, directory)?.fileKey() == directoryKey) Files.deleteIfExists(staged)
                    } catch (_: Exception) {
                        currentCoroutineContext().ensureActive()
                    }
                }
                revision.update { it + 1 }
        }
    }

    internal fun changes(capture: Capture, hub: FileChangeHub): Flow<Unit> = flow {
            val global = configDirectory(capture, SkillScope.GLOBAL)
            val project = configDirectory(capture, SkillScope.PROJECT)
            checkedNode(capture, global.resolve("skills"))
            checkedNode(capture, project.resolve("skills"))
            emitAll(merge(
                hub.watchHostDir(global.resolve("skills").toFile(), "/root/.aicode/skills", ChangeRoot.AICODE, recursive = true),
                hub.watchHostDir(project.resolve("skills").toFile(), "~/workspace/.aicode/skills", ChangeRoot.WORKSPACE, recursive = true),
                hub.watchHostDir(global.toFile(), "/root/.aicode", ChangeRoot.AICODE),
                hub.watchHostDir(project.toFile(), "~/workspace/.aicode", ChangeRoot.WORKSPACE)
            ).map {
                check(capture)
                Unit
            })
    }

    internal fun sourceSignals(): Flow<Unit> = combine(combine(
        workspace.selection, modeHolder.mode, modeHolder.modeSelectionRevisions,
        settings.activeProfileSelectionRevisions, settings.profileConfigurationRevisions
    ) { _, _, _, _, _ -> Unit }, revision) { _, _ -> Unit }

    private companion object {
        const val MAX_DEPTH = 4
        const val MAX_ENTRIES = 4096
        val JSON = Json { ignoreUnknownKeys = true }
    }
}
