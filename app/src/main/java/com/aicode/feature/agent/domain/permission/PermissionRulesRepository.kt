package com.aicode.feature.agent.domain.permission

import android.content.Context
import com.aicode.core.util.FileLogger
import com.aicode.core.watch.ChangeRoot
import com.aicode.core.watch.FileChangeBatch
import com.aicode.core.watch.FileChangeHub
import com.aicode.feature.agent.domain.container.ContainerProfile
import com.aicode.feature.agent.domain.container.RemoteSshConnection
import com.aicode.feature.settings.data.repository.ContainerProfileSnapshot
import com.aicode.feature.settings.data.repository.ContainerSettingsRepository
import com.aicode.feature.settings.data.repository.ExecutionMode
import com.aicode.feature.settings.data.repository.ExecutionModeHolder
import com.aicode.feature.workspace.data.repository.WorkspaceRepository
import com.aicode.feature.workspace.domain.ProjectAicodeRoot
import com.aicode.feature.workspace.domain.WorkspacePathMapper
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.buffer
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.emitAll
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.merge
import kotlinx.coroutines.flow.transform
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import java.io.File
import java.io.IOException
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
import java.util.ArrayDeque
import javax.inject.Inject
import javax.inject.Singleton

@OptIn(ExperimentalCoroutinesApi::class)
@Singleton
class PermissionRulesRepository @Inject constructor(
    @param:ApplicationContext private val context: Context,
    private val workspaceRepository: WorkspaceRepository,
    private val projectAicodeRoot: ProjectAicodeRoot,
    private val fileChangeHub: FileChangeHub,
    private val pathMapper: WorkspacePathMapper,
    private val modeHolder: ExecutionModeHolder,
    private val settings: ContainerSettingsRepository,
    private val remoteConnection: RemoteSshConnection
) {
    private companion object {
        const val TAG = "PermissionRules"
        const val PERMISSIONS_FILE = "permissions.json"
        const val MAX_LINKS = 40
        val JSON = Json { ignoreUnknownKeys = true; encodeDefaults = true; prettyPrint = true }
    }

    private class SourceChanged : CancellationException("Permission policy source changed")

    private data class Anchor(val source: File, val root: File, val node: Any?)

    private data class ProjectStamp(
        val selection: WorkspaceRepository.Selection,
        val workspacePath: String,
        val mode: ExecutionMode,
        val modeRevision: Long,
        val profile: ContainerProfileSnapshot,
        val remotePeer: Pair<String, Int>?,
        val remoteIdentity: Any?
    )

    private sealed interface SourceKey {
        data class Global(val anchor: Anchor) : SourceKey
        data class Project(
            val anchor: Anchor,
            val selection: WorkspaceRepository.Selection,
            val workspacePath: String,
            val mode: ExecutionMode,
            val modeRevision: Long,
            val profile: ContainerProfile,
            val profileSelectionRevision: Long,
            val profileConfigurationRevision: Long,
            val rootPath: String,
            val remotePeer: Pair<String, Int>?,
            val remoteIdentity: Any?
        ) : SourceKey
    }

    private sealed class PolicySource(
        val anchor: Anchor,
        val directory: File,
        val key: SourceKey
    ) {
        class Global(anchor: Anchor) : PolicySource(anchor, File(anchor.root, "aicode"), SourceKey.Global(anchor))
        class LocalProject(anchor: Anchor, directory: File, val stamp: ProjectStamp, key: SourceKey.Project) :
            PolicySource(anchor, directory, key)
        class RemoteProject(anchor: Anchor, directory: File, val stamp: ProjectStamp, key: SourceKey.Project) :
            PolicySource(anchor, directory, key)
    }

    private data class Node(val path: Path, val key: Any?)
    private data class Resolved(
        val file: File,
        val parents: List<Node>,
        val links: List<Pair<Path, String>>,
        val exists: Boolean,
        val fileKey: Any?,
        val size: Long?,
        val modified: java.nio.file.attribute.FileTime?
    )
    private data class Loaded(val key: SourceKey, val resolved: Resolved, val rules: List<PermissionRule>)
    private data class Failure(val key: SourceKey, val error: Exception)
    private data class Observed(val source: PolicySource?, val selection: WorkspaceRepository.Selection,
        val rules: List<PermissionRule>)
    private data class Part(val name: String, val required: Boolean = false)

    private val mutex = Mutex()
    private var globalCache: Loaded? = null
    private var projectCache: Loaded? = null
    private var globalFailure: Failure? = null
    private var projectFailure: Failure? = null
    private val invalidations = MutableStateFlow(0L)
    private val watchScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private var watchJob: Job? = null

    private fun attributes(path: Path): BasicFileAttributes? = try {
        Files.readAttributes(path, BasicFileAttributes::class.java, NOFOLLOW_LINKS)
    } catch (_: NoSuchFileException) {
        null
    }

    private fun captureAnchor(file: File): Anchor {
        if (!file.isAbsolute) throw IOException("Invalid permission policy root")
        val root = file.canonicalFile
        val node = attributes(root.toPath()) ?: throw IOException("Permission policy root is missing")
        if (!node.isDirectory || node.isSymbolicLink) throw IOException("Invalid permission policy root")
        return Anchor(file.absoluteFile, root, node.fileKey())
    }

    private fun checkAnchor(anchor: Anchor) {
        if (anchor.source.canonicalFile != anchor.root) throw SourceChanged()
        val node = attributes(anchor.root.toPath()) ?: throw SourceChanged()
        if (!node.isDirectory || node.isSymbolicLink || node.fileKey() != anchor.node) throw SourceChanged()
    }

    private fun remotePeer(): Pair<String, Int>? = remoteConnection.config?.let { it.host to it.port }

    private suspend fun checkStamp(stamp: ProjectStamp) {
        currentCoroutineContext().ensureActive()
        if (workspaceRepository.selection.value !== stamp.selection ||
            workspaceRepository.currentPath() != stamp.workspacePath ||
            modeHolder.currentMode() != stamp.mode || modeHolder.modeSelectionRevision != stamp.modeRevision ||
            (stamp.mode == ExecutionMode.REMOTE_SSH &&
                (remotePeer() != stamp.remotePeer || remoteConnection.identityState.value !== stamp.remoteIdentity))) {
            throw SourceChanged()
        }
        try {
            settings.checkProfileSnapshot(stamp.profile)
        } catch (failure: CancellationException) {
            currentCoroutineContext().ensureActive()
            throw SourceChanged()
        }
    }

    private suspend fun captureProjectSource(): PolicySource {
        val selection = workspaceRepository.selection.value
        val path = workspaceRepository.currentPath()
        val mode = modeHolder.currentMode()
        val modeRevision = modeHolder.modeSelectionRevision
        val snapshot = settings.captureProfileSnapshot()
        val stamp = ProjectStamp(selection, path, mode, modeRevision, snapshot,
            if (mode == ExecutionMode.REMOTE_SSH) remotePeer() else null,
            if (mode == ExecutionMode.REMOTE_SSH) remoteConnection.identityState.value else null)
        checkStamp(stamp)
        val configured = projectAicodeRoot.forPath(path).absoluteFile
        val anchor: Anchor
        val directory: File
        if (mode == ExecutionMode.REMOTE_SSH) {
            val appRoot = context.filesDir.absoluteFile.toPath()
            val relative = configured.toPath().let {
                if (!it.startsWith(appRoot)) throw IOException("Invalid remote permission policy root")
                appRoot.relativize(it)
            }
            if (relative.nameCount != 3 || relative.getName(0).toString() != "aicode" ||
                relative.getName(1).toString() != "projects" || relative.any { it.toString() in setOf(".", "..") }) {
                throw IOException("Invalid remote permission policy root")
            }
            anchor = captureAnchor(context.filesDir)
            directory = anchor.root.toPath().resolve(relative).toFile()
        } else {
            if (configured != File(path, ".aicode").absoluteFile) throw IOException("Invalid local permission policy root")
            anchor = captureAnchor(File(path))
            directory = File(anchor.root, ".aicode")
        }
        val key = SourceKey.Project(anchor, selection, path, mode, modeRevision, snapshot.activeProfile,
            snapshot.selectionRevision, snapshot.configurationRevision, configured.path, stamp.remotePeer, stamp.remoteIdentity)
        val source = if (mode == ExecutionMode.REMOTE_SSH) PolicySource.RemoteProject(anchor, directory, stamp, key)
            else PolicySource.LocalProject(anchor, directory, stamp, key)
        checkSource(source)
        return source
    }

    private suspend fun captureGlobalSource(): PolicySource.Global =
        PolicySource.Global(captureAnchor(context.filesDir)).also { checkSource(it) }

    private suspend fun checkSource(source: PolicySource) {
        currentCoroutineContext().ensureActive()
        when (source) {
            is PolicySource.Global -> if (context.filesDir.absoluteFile != source.anchor.source) throw SourceChanged()
            is PolicySource.LocalProject -> {
                checkStamp(source.stamp)
                if (projectAicodeRoot.forPath(source.stamp.workspacePath).absolutePath !=
                    (source.key as SourceKey.Project).rootPath) throw SourceChanged()
            }
            is PolicySource.RemoteProject -> {
                checkStamp(source.stamp)
                if (projectAicodeRoot.forPath(source.stamp.workspacePath).absolutePath !=
                    (source.key as SourceKey.Project).rootPath) throw SourceChanged()
            }
        }
        checkAnchor(source.anchor)
        currentCoroutineContext().ensureActive()
    }

    private fun requireReadable(path: Path, directory: Boolean) {
        if (!Files.isReadable(path) || (directory && !Files.isExecutable(path))) {
            throw IOException("Permission policy node is not readable")
        }
        val permissions = try { Files.getPosixFilePermissions(path, NOFOLLOW_LINKS) }
        catch (_: UnsupportedOperationException) { return }
        if (permissions.none { it in setOf(PosixFilePermission.OWNER_READ, PosixFilePermission.GROUP_READ, PosixFilePermission.OTHERS_READ) } ||
            (directory && permissions.none { it in setOf(PosixFilePermission.OWNER_EXECUTE, PosixFilePermission.GROUP_EXECUTE, PosixFilePermission.OTHERS_EXECUTE) })) {
            throw IOException("Permission policy node is not readable")
        }
    }

    private fun mappedWithinWorkspace(file: File, source: PolicySource.LocalProject): Path {
        val candidate = file.absoluteFile.toPath()
        val original = File(source.stamp.workspacePath).absoluteFile.toPath()
        val boundary = source.anchor.root.toPath()
        return when {
            candidate.startsWith(original) -> boundary.resolve(original.relativize(candidate))
            candidate.startsWith(boundary) -> candidate
            else -> throw IOException("Permission policy link escapes its workspace")
        }
    }

    private suspend fun resolve(source: PolicySource, validateFile: Boolean = true): Resolved {
        checkSource(source)
        val boundary = source.anchor.root.toPath()
        val requested = File(source.directory, PERMISSIONS_FILE).toPath()
        if (!requested.startsWith(boundary)) throw IOException("Invalid permission policy path")
        val pending = ArrayDeque<Part>()
        boundary.relativize(requested).forEach { pending.addLast(Part(it.toString())) }
        var current = boundary
        val parents = mutableListOf<Node>()
        val links = mutableListOf<Pair<Path, String>>()
        var mapping: WorkspacePathMapper.ExecutionMapping? = null
        var finalAttributes: BasicFileAttributes? = null
        while (pending.isNotEmpty()) {
            checkSource(source)
            val part = pending.removeFirst()
            when (part.name) {
                "." -> continue
                ".." -> {
                    if (current == boundary) throw IOException("Permission policy link escapes its workspace")
                    current = current.parent
                    continue
                }
            }
            val parentAttributes = attributes(current)
            if (parentAttributes != null) {
                if (!parentAttributes.isDirectory || parentAttributes.isSymbolicLink) throw IOException("Invalid permission policy parent")
                requireReadable(current, true)
                parents.add(Node(current, parentAttributes.fileKey()))
            }
            val candidate = current.resolve(part.name)
            val node = attributes(candidate)
            if (node?.isSymbolicLink == true) {
                if (source !is PolicySource.LocalProject || source.stamp.profile.activeProfile.isChroot) {
                    throw IOException("Unsupported permission policy link")
                }
                if (links.size >= MAX_LINKS) throw IOException("Permission policy link limit exceeded")
                if (mapping == null) mapping = pathMapper.captureExecutionMapping(source.stamp.profile)
                checkLegacyMapping(source, mapping)
                val target = Files.readSymbolicLink(candidate).toString()
                links.add(candidate to target)
                if (target.startsWith("/")) {
                    val mapped = mappedWithinWorkspace(pathMapper.toHostFile(target), source)
                    checkLegacyMapping(source, mapping)
                    current = boundary
                    boundary.relativize(mapped).map { Part(it.toString(), true) }.asReversed().forEach(pending::addFirst)
                } else {
                    target.split('/').filter { it.isNotEmpty() }.map { Part(it, true) }.asReversed().forEach(pending::addFirst)
                }
            } else {
                if (node == null && (part.required || pending.any { it.required })) throw IOException("Dangling permission policy link")
                if (node != null && pending.isNotEmpty() && !node.isDirectory) throw IOException("Invalid permission policy parent")
                current = candidate
                finalAttributes = node
            }
        }
        if (validateFile && finalAttributes != null) {
            if (!finalAttributes.isRegularFile) throw IOException("Permission policy is not a regular file")
            requireReadable(current, false)
        }
        if (mapping != null && source is PolicySource.LocalProject) {
            checkLegacyMapping(source, mapping)
            val mapped = pathMapper.resolveHostFile(File(File(source.stamp.workspacePath, ".aicode"), PERMISSIONS_FILE))
            checkLegacyMapping(source, mapping)
            if (mappedWithinWorkspace(mapped, source) != current) throw IOException("Permission policy link mapping changed")
        }
        checkSource(source)
        return Resolved(current.toFile(), parents, links, finalAttributes != null, finalAttributes?.fileKey(),
            finalAttributes?.size(), finalAttributes?.lastModifiedTime())
    }

    private suspend fun checkLegacyMapping(source: PolicySource.LocalProject, mapping: WorkspacePathMapper.ExecutionMapping) {
        checkSource(source)
        try {
            pathMapper.checkExecutionMapping(mapping)
        } catch (failure: CancellationException) {
            currentCoroutineContext().ensureActive()
            throw SourceChanged()
        }
    }

    private fun cached(source: PolicySource): Loaded? = if (source is PolicySource.Global) globalCache else projectCache
    private fun failure(source: PolicySource): Failure? = if (source is PolicySource.Global) globalFailure else projectFailure

    private fun recordFailure(source: PolicySource, error: Exception) {
        val failed = Failure(source.key, error)
        if (source is PolicySource.Global) globalFailure = failed else projectFailure = failed
    }

    private suspend fun publish(source: PolicySource, resolved: Resolved, rules: List<PermissionRule>) {
        checkSource(source)
        val loaded = Loaded(source.key, resolved, rules.toList())
        if (source is PolicySource.Global) {
            globalCache = loaded
            globalFailure = null
        } else {
            projectCache = loaded
            projectFailure = null
        }
    }

    private suspend fun loadLocked(source: PolicySource, force: Boolean = false): List<PermissionRule> = withContext(Dispatchers.IO) {
        try {
            val resolved = resolve(source)
            val cached = cached(source)
            if (!force && failure(source)?.key != source.key && cached?.key == source.key && cached.resolved == resolved) {
                checkSource(source)
                return@withContext cached.rules
            }
            val rules = if (!resolved.exists) emptyList() else {
                val text = FileChannel.open(resolved.file.toPath(), READ, NOFOLLOW_LINKS).use { channel ->
                    java.nio.channels.Channels.newReader(channel, StandardCharsets.UTF_8.newDecoder(), -1).readText()
                }
                JSON.decodeFromString<PermissionFile>(text).toRuleList()
            }
            checkSource(source)
            if (resolve(source) != resolved) throw IOException("Permission policy changed while reading")
            publish(source, resolved, rules)
            rules
        } catch (failure: CancellationException) {
            throw failure
        } catch (failure: Exception) {
            recordFailure(source, failure)
            throw failure
        }
    }

    private suspend fun writeLocked(source: PolicySource, rules: List<PermissionRule>) = withContext(Dispatchers.IO) {
        var temporary: Path? = null
        var stable: Resolved? = null
        try {
            var resolved = resolve(source)
            val parent = resolved.file.parentFile.toPath()
            var current = source.anchor.root.toPath()
            current.relativize(parent).forEach { part ->
                checkSource(source)
                if (resolve(source).file != resolved.file) throw SourceChanged()
                current = current.resolve(part)
                if (attributes(current) == null) Files.createDirectory(current)
                val node = attributes(current) ?: throw IOException("Permission policy directory is missing")
                if (!node.isDirectory || node.isSymbolicLink) throw IOException("Invalid permission policy parent")
            }
            resolved = resolve(source)
            stable = resolved
            temporary = Files.createTempFile(parent, ".permissions-", ".tmp")
            checkSource(source)
            if (resolve(source) != resolved) throw SourceChanged()
            FileChannel.open(temporary, WRITE, NOFOLLOW_LINKS).use { channel ->
                val bytes = StandardCharsets.UTF_8.encode(JSON.encodeToString(PermissionFile.serializer(), rules.toPermissionFile()))
                while (bytes.hasRemaining()) {
                    currentCoroutineContext().ensureActive()
                    channel.write(bytes)
                }
                channel.force(true)
            }
            checkSource(source)
            if (resolve(source) != resolved) throw SourceChanged()
            Files.move(temporary, resolved.file.toPath(), ATOMIC_MOVE, REPLACE_EXISTING)
            temporary = null
            checkSource(source)
            val written = resolve(source)
            if (written.file != resolved.file || written.parents != resolved.parents || written.links != resolved.links) throw SourceChanged()
            publish(source, written, rules)
        } catch (failure: CancellationException) {
            throw failure
        } catch (failure: Exception) {
            recordFailure(source, failure)
            throw failure
        } finally {
            val pending = temporary
            if (pending != null) {
                try {
                    checkSource(source)
                    val resolved = resolve(source)
                    val captured = stable
                    if (captured != null && resolved.file == captured.file && resolved.parents == captured.parents &&
                        resolved.links == captured.links) Files.deleteIfExists(pending)
                } catch (_: Exception) {
                    currentCoroutineContext().ensureActive()
                }
            }
        }
    }

    private fun sourceSignals(includeInvalidations: Boolean = true): Flow<Unit> {
        val signals = mutableListOf(
            workspaceRepository.selection.map { },
            workspaceRepository.current.map { },
            modeHolder.mode.map { },
            modeHolder.modeSelectionRevisions.map { },
            settings.activeProfileSelectionRevisions.map { },
            settings.profileConfigurationRevisions.map { },
            remoteConnection.connectionState.map { },
            remoteConnection.identityState.map { },
            pathMapper.runtimeProfileState.map { }
        )
        if (includeInvalidations) signals.add(invalidations.map { })
        return combine(signals) { Unit }
    }

    private fun observedRules(project: Boolean, requireSelection: Boolean): Flow<List<PermissionRule>> =
        sourceSignals().flatMapLatest {
            flow {
                try {
                    if (project && requireSelection && workspaceRepository.current.value == null) {
                        val selection = workspaceRepository.selection.value
                        if (workspaceRepository.current.value == null && workspaceRepository.selection.value === selection) {
                            emit(Observed(null, selection, emptyList()))
                        }
                    } else {
                        val source = if (project) captureProjectSource() else captureGlobalSource()
                        val rules = mutex.withLock { loadLocked(source) }
                        checkSource(source)
                        emit(Observed(source, workspaceRepository.selection.value, rules))
                    }
                } catch (_: SourceChanged) {
                    currentCoroutineContext().ensureActive()
                } catch (failure: CancellationException) {
                    throw failure
                } catch (_: Exception) {
                    FileLogger.w(TAG, "Permission policy unavailable; waiting for a source or file change")
                }
            }
        }.buffer(0).transform { observed ->
            try {
                val source = observed.source
                if (source == null) {
                    if (workspaceRepository.current.value != null || workspaceRepository.selection.value !== observed.selection) throw SourceChanged()
                } else checkSource(source)
            } catch (_: SourceChanged) {
                currentCoroutineContext().ensureActive()
                return@transform
            } catch (failure: CancellationException) {
                throw failure
            } catch (_: Exception) {
                currentCoroutineContext().ensureActive()
                FileLogger.w(TAG, "Permission policy unavailable at delivery; waiting for a source or file change")
                return@transform
            }
            emit(observed.rules)
        }

    fun currentProjectName(): String? = workspaceRepository.current.value?.name
    val currentProjectNameFlow: Flow<String?> = workspaceRepository.current.map { it?.name }
    val currentProjectRulesFlow: Flow<List<PermissionRule>> = observedRules(project = true, requireSelection = true)
    val globalRulesFlow: Flow<List<PermissionRule>> = observedRules(project = false, requireSelection = false)

    fun projectRulesFlow(projectName: String): Flow<List<PermissionRule>> = observedRules(project = true, requireSelection = false)

    suspend fun getGlobalRulesOnce(): List<PermissionRule> {
        val source = captureGlobalSource()
        return mutex.withLock { loadLocked(source).also { checkSource(source) } }
    }

    suspend fun setGlobalRules(rules: List<PermissionRule>) {
        val source = captureGlobalSource()
        mutex.withLock { writeLocked(source, rules) }
        invalidations.update { it + 1 }
    }

    suspend fun loadEffectiveForCurrentProject(): List<PermissionRule> {
        val project = captureProjectSource()
        val global = captureGlobalSource()
        return mutex.withLock {
            val globalRules = loadLocked(global)
            val projectRules = loadLocked(project)
            checkSource(global)
            checkSource(project)
            projectRules + globalRules
        }
    }

    suspend fun removeGlobalRule(rule: PermissionRule) {
        val source = captureGlobalSource()
        mutex.withLock {
            val rules = loadLocked(source).toMutableList().apply { remove(rule) }
            writeLocked(source, rules)
        }
        invalidations.update { it + 1 }
    }

    suspend fun removeProjectRule(projectName: String, rule: PermissionRule) {
        val source = captureProjectSource()
        mutex.withLock {
            val rules = loadLocked(source).toMutableList().apply { remove(rule) }
            writeLocked(source, rules)
        }
        invalidations.update { it + 1 }
    }

    suspend fun promoteToGlobal(projectName: String, rule: PermissionRule) {
        val project = captureProjectSource()
        val global = captureGlobalSource()
        try {
            mutex.withLock {
                val projectRules = loadLocked(project).toMutableList().apply { remove(rule) }
                val globalRules = loadLocked(global).toMutableList().apply { if (rule !in this) add(rule) }
                checkSource(project)
                writeLocked(global, globalRules)
                checkSource(project)
                writeLocked(project, projectRules)
            }
        } finally {
            invalidations.update { it + 1 }
        }
    }

    @Synchronized
    fun startWatching() {
        if (watchJob?.isActive == true) return
        watchJob = watchScope.launch {
            merge(policyWatch(project = false), policyWatch(project = true)).collect { (source, batch) ->
                refreshFromDisk(source, batch)
            }
        }
    }

    private fun policyWatch(project: Boolean): Flow<Pair<PolicySource, FileChangeBatch>> =
        sourceSignals(includeInvalidations = false).flatMapLatest {
            flow {
                try {
                    val source = if (project) captureProjectSource() else captureGlobalSource()
                    val parent = resolve(source, validateFile = false).file.parentFile
                    checkSource(source)
                    emitAll(fileChangeHub.watchHostDir(
                        parent,
                        if (project) "~/workspace/.aicode" else "/root/.aicode",
                        if (project) ChangeRoot.WORKSPACE else ChangeRoot.AICODE
                    ).map { source to it })
                } catch (_: SourceChanged) {
                    currentCoroutineContext().ensureActive()
                } catch (failure: CancellationException) {
                    throw failure
                } catch (_: Exception) {
                    FileLogger.w(TAG, "Permission policy watch unavailable; waiting for a source change")
                }
            }
        }

    private suspend fun refreshFromDisk(source: PolicySource, batch: FileChangeBatch) {
        mutex.withLock {
            try {
                checkSource(source)
                val resolved = resolve(source, validateFile = false)
                val logical = File(source.directory, PERMISSIONS_FILE).absolutePath
                if (batch.truncated || batch.changes.any { change ->
                        change.hostPath == resolved.file.absolutePath || change.hostPath == logical ||
                            resolved.links.any { it.first.toString() == change.hostPath }
                    }) loadLocked(source, force = true)
            } catch (_: SourceChanged) {
                currentCoroutineContext().ensureActive()
            } catch (failure: CancellationException) {
                throw failure
            } catch (failure: Exception) {
                recordFailure(source, failure)
                FileLogger.w(TAG, "Permission policy refresh unavailable; previous cache retained")
            }
        }
        invalidations.update { it + 1 }
    }
}
