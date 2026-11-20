package com.aicode.feature.backup.data

import android.content.Context
import com.aicode.feature.agent.domain.container.ContainerInstaller
import com.aicode.feature.agent.domain.container.RemoteSshConnection
import com.aicode.feature.agent.domain.memory.MemorySource
import com.aicode.feature.agent.domain.prompt.SystemPromptProvider
import com.aicode.feature.agent.domain.skill.SkillRepository
import com.aicode.feature.backup.domain.BackupContentKind
import com.aicode.feature.backup.domain.BackupContentLocation
import com.aicode.feature.backup.domain.BackupContentRestoreResult
import com.aicode.feature.backup.domain.BackupContentRestoreSelection
import com.aicode.feature.backup.domain.BackupContentRestoreStatus
import com.aicode.feature.backup.domain.BackupContentRestoreTarget
import com.aicode.feature.backup.domain.BackupContentSectionMeta
import com.aicode.feature.backup.domain.BackupOptions
import com.aicode.feature.settings.data.repository.ExecutionMode
import com.aicode.feature.settings.data.repository.ExecutionModeHolder
import com.aicode.feature.workspace.data.repository.WorkspaceRepository
import com.aicode.feature.workspace.domain.HostTransferBackend
import com.aicode.feature.workspace.domain.RemoteAgentConfigCache
import com.aicode.feature.workspace.domain.RemoteSftpFileAccess
import com.aicode.feature.workspace.domain.TransferBackend
import com.aicode.feature.workspace.domain.TransferKind
import com.aicode.feature.workspace.domain.TransferReader
import com.aicode.feature.workspace.domain.WorkspaceExecutionContext
import com.aicode.feature.workspace.domain.WorkspaceExecutionContextFactory
import com.aicode.feature.workspace.domain.model.WorkspaceType
import com.aicode.feature.workspace.domain.transferStream
import dagger.hilt.android.qualifiers.ApplicationContext
import java.io.File
import java.io.IOException
import java.nio.file.FileVisitResult
import java.nio.file.Files
import java.nio.file.LinkOption.NOFOLLOW_LINKS
import java.nio.file.NoSuchFileException
import java.nio.file.Path
import java.nio.file.SimpleFileVisitor
import java.nio.file.StandardOpenOption.CREATE_NEW
import java.nio.file.StandardOpenOption.WRITE
import java.nio.file.attribute.BasicFileAttributes
import java.security.MessageDigest
import java.util.IdentityHashMap
import java.util.UUID
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull

@Singleton
class BackupFileContentService @Inject constructor(
    @param:ApplicationContext private val context: Context,
    private val containerInstaller: ContainerInstaller,
    private val workspaces: WorkspaceRepository,
    private val modeHolder: ExecutionModeHolder,
    private val connection: RemoteSshConnection,
    private val remoteFiles: RemoteSftpFileAccess,
    private val executions: WorkspaceExecutionContextFactory,
    private val remoteCache: RemoteAgentConfigCache,
    private val skillRepository: SkillRepository,
    private val systemPrompts: SystemPromptProvider
) {
    private data class OwnedDirectory(val directory: File, val identity: Any)
    private data class Part(
        val path: String,
        val reader: TransferReader,
        val include: (String, TransferKind) -> Boolean = { _, _ -> true }
    )
    private data class RemoteTarget(
        val execution: WorkspaceExecutionContext,
        val identity: String,
        val label: String,
        val home: String,
        val project: String,
        val checkCurrent: () -> Unit
    )
    private data class Destination(
        val backend: (String) -> TransferBackend,
        val ensureParent: () -> Unit,
        val checkCurrent: () -> Unit,
        val invalidate: () -> Unit = {}
    )

    private val ownedStages = IdentityHashMap<PreparedBackupContent, OwnedDirectory>()

    suspend fun prepareExport(options: BackupOptions): List<PreparedBackupContent> {
        val owner = currentCoroutineContext()
        val pending = arrayListOf<OwnedDirectory>()
        try {
            return withContext(Dispatchers.IO) {
                if (!options.memories && !options.skills && !options.prompts) return@withContext emptyList()
                val job = owner
                val result = arrayListOf<PreparedBackupContent>()
                val inventories = arrayListOf<Pair<File, List<String>>>()
                val verifications = arrayListOf<() -> Unit>()
                val aicode = appFile(containerInstaller.aicodeDir)
                val phoneCheck = hostGuard(aicode) { job.ensureActive() }
                fun addPhoneSection(
                    kind: BackupContentKind,
                    location: BackupContentLocation,
                    root: File,
                    name: String? = null,
                    key: String? = null,
                    check: () -> Unit = phoneCheck,
                    definitions: Boolean = true
                ) {
                    check()
                    val parts = when (kind) {
                        BackupContentKind.MEMORY -> listOf(Part("", hostBackend(root, check), ::includeMemory))
                        BackupContentKind.PROMPTS -> listOf(Part("", hostBackend(root, check)))
                        BackupContentKind.SKILLS -> buildList {
                            if (definitions) add(Part("definitions", hostBackend(File(root, "skills"), check), BackupFileContentFiles::includeSkill))
                            add(Part("skills.json", hostBackend(File(root, "skills.json"), check)))
                        }
                        BackupContentKind.PLUGIN -> throw IOException("Plugin content has a separate backup service")
                    }
                    if (result.size >= 512) throw IOException("Too many backup content sections")
                    val section = prepare(kind, location, parts, name, key, root.path, check, pending, verifications,
                        BackupContentArchive.MAX_TOTAL_BYTES - result.sumOf { it.metadata.bytes })
                    result += section
                }
                val locals = workspaces.captureBackupLocalWorkspaces()
                if (options.memories) addPhoneSection(BackupContentKind.MEMORY, BackupContentLocation.PHONE_GLOBAL, File(aicode, "memory"))
                if (options.prompts) addPhoneSection(BackupContentKind.PROMPTS, BackupContentLocation.PHONE_GLOBAL, File(aicode, "prompts.custom"))
                if (options.skills) addPhoneSection(BackupContentKind.SKILLS, BackupContentLocation.PHONE_GLOBAL, aicode)
                for (local in locals) {
                    val workspace = local.workspace
                    require(BackupContentArchive.validPart(workspace.name))
                    val root = appFile(File(workspace.path, ".aicode"))
                    val check = hostGuard(root) { job.ensureActive(); local.checkCurrent() }
                    if (options.memories) addPhoneSection(BackupContentKind.MEMORY, BackupContentLocation.PHONE_PROJECT, File(root, "memory"), workspace.name, check = check)
                    if (options.prompts) addPhoneSection(BackupContentKind.PROMPTS, BackupContentLocation.PHONE_PROJECT, File(root, "prompts.custom"), workspace.name, check = check)
                    if (options.skills) addPhoneSection(BackupContentKind.SKILLS, BackupContentLocation.PHONE_PROJECT, root, workspace.name, check = check)
                }
                if (options.memories) {
                    val cacheRoot = File(aicode, "memory/projects")
                    val cachedProjects = childDirectories(cacheRoot, phoneCheck)
                    inventories += cacheRoot to cachedProjects.map { it.name }
                    for (directory in cachedProjects) {
                        addPhoneSection(BackupContentKind.MEMORY, BackupContentLocation.PHONE_REMOTE_PROJECT, directory,
                            directory.name, directory.name)
                    }
                }
                if (options.prompts || options.skills) {
                    val cacheRoot = File(aicode, "projects")
                    val cachedProjects = childDirectories(cacheRoot, phoneCheck)
                    inventories += cacheRoot to cachedProjects.map { it.name }
                    for (directory in cachedProjects) {
                        if (options.prompts) addPhoneSection(BackupContentKind.PROMPTS, BackupContentLocation.PHONE_REMOTE_PROJECT,
                            File(directory, "prompts.custom"), directory.name, directory.name)
                        if (options.skills) addPhoneSection(BackupContentKind.SKILLS, BackupContentLocation.PHONE_REMOTE_PROJECT,
                            directory, directory.name, directory.name, definitions = false)
                    }
                }
                if (options.skills) {
                    captureRemote()?.let { remote ->
                        withContext(Dispatchers.IO + remote.execution) {
                            val check = { job.ensureActive(); remote.checkCurrent() }
                            val home = remote.home.trimEnd('/') + "/.aicode"
                            val project = remote.project.trimEnd('/') + "/.aicode"
                            for ((location, root) in listOf(BackupContentLocation.SSH_GLOBAL to home, BackupContentLocation.SSH_PROJECT to project)) {
                                val parts = buildList {
                                    add(Part("definitions", remoteBackend("$root/skills", check), BackupFileContentFiles::includeSkill))
                                    if (location == BackupContentLocation.SSH_GLOBAL) add(Part("skills.json", remoteBackend("$root/skills.json", check)))
                                }
                                if (result.size >= 512) throw IOException("Too many backup content sections")
                                result += prepare(BackupContentKind.SKILLS, location, parts,
                                    remote.project.substringAfterLast('/').takeIf(BackupContentArchive::validPart), null,
                                    remote.label, check, pending, verifications,
                                    BackupContentArchive.MAX_TOTAL_BYTES - result.sumOf { it.metadata.bytes })
                            }
                        }
                    }
                }
                BackupContentArchive.validateMetadata(result.map { it.metadata })
                if (workspaces.captureBackupLocalWorkspaces().map { it.workspace } != locals.map { it.workspace }) {
                    throw IOException("Backup workspace registry changed during export")
                }
                inventories.forEach { (root, names) ->
                    if (childDirectories(root, phoneCheck).map { it.name } != names) throw IOException("Backup project cache registry changed")
                }
                verifications.forEach { it() }
                result.forEach { it.checkCurrent() }
                synchronized(ownedStages) {
                    result.forEach { section -> ownedStages[section] = pending.first { it.directory == section.directory } }
                }
                result
            }
        } catch (failure: Throwable) {
            withContext(NonCancellable + Dispatchers.IO) {
                pending.forEach { owned -> runCatching { deleteOwned(owned) }.exceptionOrNull()?.let(failure::addSuppressed) }
                synchronized(ownedStages) { ownedStages.entries.removeAll { (_, owned) -> owned in pending } }
            }
            throw failure
        }
    }

    fun cleanup(sections: Collection<PreparedBackupContent>) {
        for (section in sections) {
            val owned = synchronized(ownedStages) { ownedStages[section] } ?: continue
            deleteOwned(owned)
            synchronized(ownedStages) { if (ownedStages[section] === owned) ownedStages.remove(section) }
        }
    }

    suspend fun currentRemoteTarget(): BackupContentRestoreTarget? = withContext(Dispatchers.IO) {
        try {
            val remote = captureRemote() ?: return@withContext null
            withContext(Dispatchers.IO + remote.execution) {
                remoteBackend(remote.home, remote.checkCurrent).stat("")?.let {
                    if (it.kind != TransferKind.DIRECTORY) throw IOException("Remote home is unavailable")
                } ?: throw IOException("Remote home is unavailable")
                if (remoteBackend(remote.project, remote.checkCurrent).stat("")?.kind != TransferKind.DIRECTORY) {
                    throw IOException("Remote project is unavailable")
                }
                remote.checkCurrent()
                BackupContentRestoreTarget(remote.identity, remote.label)
            }
        } catch (failure: CancellationException) {
            currentCoroutineContext().ensureActive()
            null
        } catch (_: IOException) {
            null
        } catch (_: SecurityException) {
            null
        } catch (_: IllegalStateException) {
            null
        } catch (_: IllegalArgumentException) {
            null
        }
    }

    suspend fun restore(
        meta: BackupContentSectionMeta,
        directory: File,
        selection: BackupContentRestoreSelection
    ): BackupContentRestoreResult = withContext(Dispatchers.IO) {
        val progress = BackupFileRestoreProgress()
        var validated = false
        val job = currentCoroutineContext()
        fun result(status: BackupContentRestoreStatus, code: String? = null) = BackupContentRestoreResult(
            meta.id, meta.kind, meta.label, status, progress.restored, progress.skipped, progress.conflicts, code
        )
        try {
            BackupContentArchive.validateMetadata(listOf(meta))
            if (meta.kind == BackupContentKind.PLUGIN) throw IOException("Plugin content has a separate restore service")
            BackupContentArchive.checkDirectory(directory)
            val inputCheck = hostGuard(directory) { job.ensureActive() }
            val reader = hostBackend(directory, inputCheck)
            val tree = BackupFileContentFiles.snapshot(reader, inputCheck, definitionsLayout = meta.kind == BackupContentKind.SKILLS)
                ?: throw IOException("Backup content is missing")
            validateInput(meta, reader, tree)
            validated = true
            val remoteLocation = meta.location == BackupContentLocation.SSH_GLOBAL || meta.location == BackupContentLocation.SSH_PROJECT
            if (remoteLocation) {
                val remote = captureRemote() ?: return@withContext result(BackupContentRestoreStatus.UNAVAILABLE, "REMOTE_UNAVAILABLE")
                if (selection.remoteTargetIdentity == null || selection.remoteTargetIdentity != remote.identity) {
                    return@withContext result(BackupContentRestoreStatus.UNAVAILABLE, "REMOTE_TARGET_CHANGED")
                }
                withContext(Dispatchers.IO + remote.execution) {
                    val check = { inputCheck(); remote.checkCurrent() }
                    val root = (if (meta.location == BackupContentLocation.SSH_GLOBAL) remote.home else remote.project).trimEnd('/') + "/.aicode"
                    restoreParts(meta, directory, selection, progress, check, { relative ->
                        val path = if (relative.isEmpty()) root else "$root/$relative"
                        Destination({ child -> remoteBackend(join(path, child), check) },
                            { ensureRemoteDirectory(path.substringBeforeLast('/'), check) }, check,
                            remoteCache.invalidation(path))
                    })
                }
            } else {
                val (base, targetCheck) = phoneDestination(meta) { job.ensureActive() }
                val check = { inputCheck(); targetCheck() }
                restoreParts(meta, directory, selection, progress, check, { relative ->
                    val root = if (relative.isEmpty() || meta.location == BackupContentLocation.PHONE_REMOTE_PROJECT &&
                        meta.kind == BackupContentKind.MEMORY) base else File(base, relative)
                    Destination({ child -> hostBackend(if (child.isEmpty()) root else File(root, child), check) },
                        { ensureHostDirectory(root.parentFile ?: throw IOException("Backup target requires a parent"), check) }, check)
                })
            }
            result(when {
                progress.conflicts > 0 && progress.restored > 0 -> BackupContentRestoreStatus.PARTIAL
                progress.conflicts > 0 -> BackupContentRestoreStatus.CONFLICT
                else -> BackupContentRestoreStatus.RESTORED
            })
        } catch (failure: CancellationException) {
            job.ensureActive()
            result(if (progress.changed) BackupContentRestoreStatus.PARTIAL else BackupContentRestoreStatus.UNAVAILABLE, "TARGET_CHANGED")
        } catch (_: TargetUnavailableException) {
            result(if (progress.changed) BackupContentRestoreStatus.PARTIAL else BackupContentRestoreStatus.UNAVAILABLE, "TARGET_UNAVAILABLE")
        } catch (_: Exception) {
            result(when {
                progress.changed -> BackupContentRestoreStatus.PARTIAL
                validated -> BackupContentRestoreStatus.UNAVAILABLE
                else -> BackupContentRestoreStatus.FAILED
            }, "FILE_CONTENT_FAILED")
        } finally {
            if (progress.changed) {
                if (meta.kind == BackupContentKind.SKILLS) skillRepository.invalidateAfterBackupRestore()
                systemPrompts.invalidateAfterBackupRestore()
            }
        }
    }

    private fun prepare(
        kind: BackupContentKind,
        location: BackupContentLocation,
        parts: List<Part>,
        workspaceName: String?,
        sourceKey: String?,
        sourceLabel: String,
        check: () -> Unit,
        pending: MutableList<OwnedDirectory>,
        verifications: MutableList<() -> Unit>,
        remainingBytes: Long
    ): PreparedBackupContent = memoryLocked(kind) {
        check()
        val owned = newStage()
        pending += owned
        val snapshots = parts.map { part -> part to BackupFileContentFiles.snapshot(part.reader, check, part.include) }
        val bytes = snapshots.sumOf { (_, tree) -> tree?.files?.sumOf { it.bytes } ?: 0L }
        if (bytes > BackupContentArchive.MAX_SECTION_BYTES || bytes > remainingBytes) throw IOException("Backup content size limit exceeded")
        for ((part, tree) in snapshots) {
            if (tree == null) continue
            if (part.path == "skills.json") {
                if (tree.nodes.singleOrNull()?.stat?.kind != TransferKind.FILE) throw IOException("Invalid skills configuration")
                validateSkillsConfig(part.reader, "", check)
                copyFile(part.reader, tree.nodes.single(), File(owned.directory, part.path), check)
            } else {
                if (tree.nodes.firstOrNull()?.stat?.kind != TransferKind.DIRECTORY) throw IOException("Backup content root must be a directory")
                val destination = if (part.path.isEmpty()) owned.directory else File(owned.directory, part.path).also { Files.createDirectory(it.toPath()) }
                BackupFileContentFiles.copyToDirectory(part.reader, tree, destination, check)
            }
        }
        for ((part, tree) in snapshots) {
            if (BackupFileContentFiles.snapshot(part.reader, check, part.include) != tree) throw IOException("Backup content changed during export")
        }
        val label = buildString {
            workspaceName?.let { append(it).append(" · ") }
            append(when (kind) {
                BackupContentKind.MEMORY -> ".aicode/memory"
                BackupContentKind.PROMPTS -> ".aicode/prompts.custom"
                BackupContentKind.SKILLS -> ".aicode/skills"
                BackupContentKind.PLUGIN -> throw IOException("Unsupported file content")
            })
        }
        val meta = BackupContentArchive.metadata(UUID.randomUUID().toString(), kind, location, label, owned.directory)
            .copy(workspaceName = workspaceName, sourceKey = sourceKey, sourceLabel = sourceLabel)
        val stagedReader = hostBackend(owned.directory, check)
        val stagedTree = BackupFileContentFiles.snapshot(stagedReader, check, definitionsLayout = kind == BackupContentKind.SKILLS)
            ?: throw IOException("Backup staging disappeared")
        validateInput(meta, stagedReader, stagedTree)
        val execution = WorkspaceExecutionContext.current()
        verifications += {
            val verify = {
                memoryLocked(kind) {
                    for ((part, original) in snapshots) {
                        if (BackupFileContentFiles.snapshot(part.reader, check, part.include) != original) {
                            throw IOException("Backup content changed before export completed")
                        }
                    }
                }
            }
            if (execution == null) verify() else execution.useCaptured(verify)
        }
        PreparedBackupContent(meta, owned.directory) {
            check()
            if (attributes(owned.directory)?.let { it.isDirectory && it.fileKey() == owned.identity } != true) {
                throw IOException("Backup staging ownership changed")
            }
        }
    }

    private fun restoreParts(
        meta: BackupContentSectionMeta,
        directory: File,
        selection: BackupContentRestoreSelection,
        progress: BackupFileRestoreProgress,
        check: () -> Unit,
        destination: (String) -> Destination
    ) = memoryLocked(meta.kind) {
        val reader = hostBackend(directory, check)
        val tree = BackupFileContentFiles.snapshot(reader, check, definitionsLayout = meta.kind == BackupContentKind.SKILLS)
            ?: throw IOException("Backup content disappeared")
        validateInput(meta, reader, tree)
        val parts = when (meta.kind) {
            BackupContentKind.MEMORY -> listOf("" to "memory")
            BackupContentKind.PROMPTS -> listOf("" to "prompts.custom")
            BackupContentKind.SKILLS -> listOf("definitions" to "skills", "skills.json" to "skills.json")
            BackupContentKind.PLUGIN -> throw IOException("Unsupported file content")
        }
        for ((archivePath, targetPath) in parts) {
            val sourceFile = if (archivePath.isEmpty()) directory else File(directory, archivePath)
            val source = hostBackend(sourceFile, check)
            val snapshot = BackupFileContentFiles.snapshot(source, check) ?: continue
            val target = destination(targetPath)
            target.checkCurrent()
            target.ensureParent()
            BackupFileContentFiles.restore(source, snapshot, target.backend, selection.overwrite, progress, check, target.invalidate)
        }
    }

    private suspend fun phoneDestination(meta: BackupContentSectionMeta, check: () -> Unit): Pair<File, () -> Unit> {
        val aicode = appFile(containerInstaller.aicodeDir)
        return when (meta.location) {
            BackupContentLocation.PHONE_GLOBAL -> aicode to hostGuard(aicode, check)
            BackupContentLocation.PHONE_REMOTE_PROJECT -> {
                val key = meta.sourceKey?.takeIf(BackupContentArchive::validPart) ?: throw TargetUnavailableException()
                val parent = File(aicode, if (meta.kind == BackupContentKind.MEMORY) "memory/projects" else "projects")
                val root = File(parent, key)
                root to hostGuard(root, check)
            }
            BackupContentLocation.PHONE_PROJECT -> {
                val name = meta.workspaceName?.takeIf(BackupContentArchive::validPart) ?: throw TargetUnavailableException()
                val locals = workspaces.captureBackupLocalWorkspaces()
                val existing = locals.singleOrNull { it.workspace.name == name }
                if (existing != null) {
                    if (!existing.workspace.available || existing.workspace.type == WorkspaceType.REMOTE) throw TargetUnavailableException()
                    val root = File(appFile(File(existing.workspace.path)), ".aicode")
                    root to hostGuard(root) { check(); existing.checkCurrent() }
                } else {
                    val projectRoot = appFile(File(context.filesDir, "projects"))
                    val root = File(projectRoot, name)
                    val guard = hostGuard(root, check)
                    if (attributes(root) != null) throw TargetUnavailableException()
                    ensureHostDirectory(projectRoot, guard)
                    Files.createDirectory(root.toPath())
                    guard()
                    val destination = File(root, ".aicode")
                    destination to hostGuard(destination, guard)
                }
            }
            else -> throw TargetUnavailableException()
        }
    }

    private suspend fun captureRemote(): RemoteTarget? {
        if (modeHolder.currentMode() != ExecutionMode.REMOTE_SSH || !connection.isConnected()) return null
        val selection = workspaces.selection.value
        val workspace = selection.workspace?.takeIf { it.type == WorkspaceType.REMOTE && it.available } ?: return null
        val execution = executions.freeze()
        if (execution.mode != ExecutionMode.REMOTE_SSH) return null
        val source = execution.remoteSource ?: throw IOException("Remote execution is unavailable")
        val config = connection.config ?: throw IOException("Remote configuration is unavailable")
        val home = absoluteRemote(source.identity.home)
        val project = absoluteRemote(source.workspaceRoot)
        if (workspace.path != source.workspaceRoot) throw CancellationException("Remote project changed")
        val checkIdentity = {
            execution.checkCurrent()
            connection.checkIdentity(source.identity)
            if (workspaces.selection.value !== selection || connection.config !== config) {
                throw CancellationException("Remote restore target changed")
            }
        }
        val check = withContext(Dispatchers.IO + execution) {
            checkIdentity()
            val root = remoteFiles.transferBackend("/", checkIdentity)
            val verify = {
                checkIdentity()
                for (path in listOf(home, project)) {
                    if (root.stat(path.removePrefix("/"))?.kind != TransferKind.DIRECTORY) {
                        throw TargetUnavailableException()
                    }
                }
                checkIdentity()
            }
            verify()
            verify
        }
        val rawIdentity = listOf(config.host, config.port.toString(), config.username, home, project,
            source.identity.generation.toString(), execution.fileCacheIdentity, System.identityHashCode(selection).toString()).joinToString("\u0000")
        val identity = MessageDigest.getInstance("SHA-256").digest(rawIdentity.toByteArray(Charsets.UTF_8))
            .joinToString("") { "%02x".format(it.toInt() and 0xff) }
        val label = "${config.username}@${config.host}:${config.port} · $project"
        return RemoteTarget(execution, identity, label, home, project, check)
    }

    private fun remoteBackend(path: String, check: () -> Unit): TransferBackend {
        val absolute = absoluteRemote(path)
        checkRemoteParents(absolute, check)
        val backend = remoteFiles.transferBackend(absolute) { check(); checkRemoteParents(absolute, check) }
        return backend
    }

    private fun checkRemoteParents(path: String, check: () -> Unit) {
        check()
        val root = remoteFiles.transferBackend("/", check)
        var relative = ""
        for (part in path.removePrefix("/").split('/').filter { it.isNotEmpty() }.dropLast(1)) {
            relative = join(relative, part)
            val stat = root.stat(relative) ?: return
            if (stat.kind != TransferKind.DIRECTORY) throw IOException("Unsafe remote backup parent")
        }
        check()
    }

    private fun ensureRemoteDirectory(path: String, check: () -> Unit) {
        check()
        val root = remoteFiles.transferBackend("/", check)
        var relative = ""
        for (part in absoluteRemote(path).removePrefix("/").split('/').filter { it.isNotEmpty() }) {
            relative = join(relative, part)
            val stat = root.stat(relative)
            if (stat == null) {
                val target = remoteBackend("/$relative", check)
                target.createStage(TransferKind.DIRECTORY).use { stage ->
                    stage.commit(false) {
                        check()
                        if (root.stat(relative) != null) throw IOException("Remote backup parent appeared")
                    }
                }
            } else if (stat.kind != TransferKind.DIRECTORY) throw IOException("Unsafe remote backup target parent")
            check()
        }
    }

    private fun validateInput(meta: BackupContentSectionMeta, reader: TransferReader, tree: BackupFileTree) {
        if (tree.nodes.firstOrNull()?.stat?.kind != TransferKind.DIRECTORY || tree.files.size != meta.fileCount ||
            tree.files.sumOf { it.bytes } != meta.bytes
        ) throw IOException("Backup content metadata mismatch")
        when (meta.kind) {
            BackupContentKind.MEMORY -> if (tree.nodes.drop(1).any { !includeMemory(it.path, it.stat.kind) || it.stat.kind != TransferKind.FILE }) {
                throw IOException("Invalid memory backup path")
            }
            BackupContentKind.PROMPTS -> {
                if (tree.nodes.any { it.path == "prompts.json" && it.stat.kind != TransferKind.FILE }) throw IOException("Invalid prompt configuration")
                if (tree.files.any { it.path == "prompts.json" }) validatePromptConfig(reader, "prompts.json")
            }
            BackupContentKind.SKILLS -> {
                if (tree.nodes.drop(1).any { node ->
                        when {
                            node.path == "skills.json" -> node.stat.kind != TransferKind.FILE || meta.location == BackupContentLocation.SSH_PROJECT
                            node.path == "definitions" -> node.stat.kind != TransferKind.DIRECTORY || meta.location == BackupContentLocation.PHONE_REMOTE_PROJECT
                            node.path.startsWith("definitions/") -> meta.location == BackupContentLocation.PHONE_REMOTE_PROJECT ||
                                !BackupFileContentFiles.validRelative(node.path.removePrefix("definitions/")) ||
                                !BackupFileContentFiles.includeSkill(node.path.removePrefix("definitions/"), node.stat.kind)
                            else -> true
                        }
                    }) throw IOException("Invalid skills backup path")
                if (tree.files.any { it.path == "skills.json" }) validateSkillsConfig(reader, "skills.json")
            }
            BackupContentKind.PLUGIN -> throw IOException("Unsupported file backup kind")
        }
    }

    private fun validateSkillsConfig(reader: TransferReader, path: String, check: () -> Unit = {}) {
        val root = readConfiguration(reader, path, check)
        if (root.keys != setOf("disabled")) throw IOException("Invalid skills configuration fields")
        val disabled = root["disabled"] as? JsonArray ?: throw IOException("Invalid disabled skills configuration")
        if (disabled.size > BackupContentArchive.MAX_ENTRIES || disabled.any {
                val value = it as? JsonPrimitive
                value == null || !value.isString || !BackupContentArchive.validPart(value.content)
            }) throw IOException("Invalid disabled skill name")
    }

    private fun validatePromptConfig(reader: TransferReader, path: String) {
        val root = readConfiguration(reader, path) {}
        if (root.keys != setOf("enabled")) throw IOException("Invalid prompt configuration fields")
        val enabled = root["enabled"] as? JsonObject ?: throw IOException("Invalid prompt configuration")
        val numbers = hashSetOf<Int>()
        enabled.forEach { (key, value) ->
            val number = key.toIntOrNull()
            val flag = value as? JsonPrimitive
            if (number == null || number !in 0..99 || !numbers.add(number) || flag == null || flag.isString || flag.booleanOrNull == null) {
                throw IOException("Invalid prompt configuration entry")
            }
        }
    }

    private fun readConfiguration(reader: TransferReader, path: String, check: () -> Unit): JsonObject {
        val stat = reader.stat(path) ?: throw IOException("Backup configuration is missing")
        if (stat.kind != TransferKind.FILE || stat.size == null || stat.size !in 0..1024L * 1024) throw IOException("Invalid backup configuration size")
        val output = java.io.ByteArrayOutputStream(stat.size.toInt())
        reader.openRead(path).use { transferStream(it, output, stat.size, check) }
        if (reader.stat(path) != stat) throw IOException("Backup configuration changed")
        return Json.parseToJsonElement(output.toString(Charsets.UTF_8.name())) as? JsonObject
            ?: throw IOException("Invalid backup configuration")
    }

    private fun copyFile(reader: TransferReader, node: BackupFileNode, destination: File, check: () -> Unit) {
        Files.newOutputStream(destination.toPath(), CREATE_NEW, WRITE, NOFOLLOW_LINKS).use { output ->
            val copied = reader.openRead("").use { transferStream(it, output, node.bytes, check) }
            if (copied.digest != node.digest || reader.stat("") != node.stat || reader.isExecutable("") != node.executable) {
                throw IOException("Backup configuration changed during staging")
            }
        }
        if (destination.canExecute() != node.executable && !destination.setExecutable(node.executable, true)) {
            throw IOException("Cannot preserve backup permissions")
        }
    }

    private fun appFile(file: File): File {
        val app = context.filesDir.absoluteFile
        val canonical = app.canonicalFile
        val absolute = file.absoluteFile
        val prefix = app.path.trimEnd('/') + "/"
        return if (absolute == app) canonical else if (absolute.path.startsWith(prefix)) {
            File(canonical, absolute.path.removePrefix(prefix))
        } else absolute
    }

    private fun hostBackend(root: File, check: () -> Unit): TransferBackend = HostTransferBackend({ relative, _ ->
        check()
        val file = if (relative.isEmpty()) root else File(root, relative)
        checkHostPath(file)
        val stat = attributes(file)
        if (stat?.isRegularFile == true) {
            val links = try { Files.getAttribute(file.toPath(), "unix:nlink", NOFOLLOW_LINKS) as? Number }
                catch (_: UnsupportedOperationException) { null } catch (_: IllegalArgumentException) { null }
            if (links != null && links.toLong() > 1) throw IOException("Hard-linked backup content is not supported")
        }
        file
    }, check)

    private fun hostGuard(root: File, check: () -> Unit): () -> Unit {
        val identities = linkedMapOf<File, Any>()
        val directories = generateSequence(root.absoluteFile) { it.parentFile }.toList().asReversed()
        fun verify() {
            check()
            checkHostPath(root)
            directories.forEach { directory ->
                val attrs = attributes(directory)
                if (attrs != null && attrs.isDirectory) {
                    val identity = attrs.fileKey() ?: throw TargetUnavailableException()
                    val previous = identities.putIfAbsent(directory, identity)
                    if (previous != null && previous != identity) throw TargetUnavailableException()
                } else if (directory in identities) throw TargetUnavailableException()
            }
            check()
        }
        verify()
        return ::verify
    }

    private fun checkHostPath(file: File) {
        if (file.path.any(Char::isISOControl) || file.toPath().normalize() != file.toPath()) throw IOException("Invalid backup host path")
        var path: File? = file.absoluteFile
        var final = true
        while (path != null) {
            val attrs = attributes(path)
            if (attrs != null && (attrs.isSymbolicLink || !final && !attrs.isDirectory)) throw IOException("Unsafe backup host parent")
            final = false
            path = path.parentFile
        }
    }

    private fun ensureHostDirectory(directory: File, check: () -> Unit) {
        check()
        checkHostPath(directory)
        val parts = generateSequence(directory.absoluteFile) { it.parentFile }.toList().asReversed()
        for (part in parts) {
            check()
            checkHostPath(part)
            val attrs = attributes(part)
            if (attrs == null) Files.createDirectory(part.toPath())
            else if (!attrs.isDirectory) throw IOException("Unsafe backup target directory")
        }
        check()
    }

    private fun childDirectories(root: File, check: () -> Unit): List<File> {
        check()
        checkHostPath(root)
        val attrs = attributes(root) ?: return emptyList()
        if (!attrs.isDirectory) throw IOException("Backup project cache root must be a directory")
        val identity = attrs.fileKey() ?: throw IOException("Backup project cache identity is unavailable")
        val result = arrayListOf<File>()
        Files.newDirectoryStream(root.toPath()).use { entries ->
            var count = 0
            for (path in entries) {
                check()
                if (++count > BackupContentArchive.MAX_ENTRIES) throw IOException("Too many backup project caches")
                val file = path.toFile()
                if (!BackupContentArchive.validPart(file.name)) throw IOException("Invalid backup project cache key")
                val child = attributes(file) ?: throw IOException("Backup project cache disappeared")
                if (child.isSymbolicLink) throw IOException("Linked backup project caches are not supported")
                if (child.isDirectory) result += file
            }
        }
        val after = attributes(root) ?: throw IOException("Backup project caches disappeared")
        if (!after.isDirectory || after.fileKey() != identity) throw IOException("Backup project cache root changed")
        check()
        return result.sortedBy { it.name }
    }

    private fun newStage(): OwnedDirectory {
        val root = context.cacheDir.canonicalFile
        BackupContentArchive.checkDirectory(root)
        val directory = Files.createTempDirectory(root.toPath(), "backup-file-content-").toFile()
        val identity = attributes(directory)?.fileKey() ?: throw IOException("Backup staging identity is unavailable")
        return OwnedDirectory(directory, identity)
    }

    private fun deleteOwned(owned: OwnedDirectory) {
        val root = owned.directory.toPath()
        fun owns(): Boolean = attributes(owned.directory)?.let { it.isDirectory && it.fileKey() == owned.identity } == true
        if (!owns()) return
        BackupContentArchive.checkDirectory(owned.directory)
        Files.walkFileTree(root, object : SimpleFileVisitor<Path>() {
            override fun preVisitDirectory(dir: Path, attrs: BasicFileAttributes): FileVisitResult {
                if (!owns()) throw IOException("Backup staging ownership changed")
                return FileVisitResult.CONTINUE
            }
            override fun visitFile(file: Path, attrs: BasicFileAttributes): FileVisitResult {
                if (!owns()) throw IOException("Backup staging ownership changed")
                Files.delete(file)
                return FileVisitResult.CONTINUE
            }
            override fun postVisitDirectory(dir: Path, exc: IOException?): FileVisitResult {
                if (exc != null) throw exc
                if (!owns()) throw IOException("Backup staging ownership changed")
                Files.delete(dir)
                return FileVisitResult.CONTINUE
            }
        })
    }

    private fun attributes(file: File): BasicFileAttributes? = try {
        Files.readAttributes(file.toPath(), BasicFileAttributes::class.java, NOFOLLOW_LINKS)
    } catch (_: NoSuchFileException) { null }

    private fun includeMemory(path: String, kind: TransferKind): Boolean =
        '/' !in path && path.endsWith(".md") && kind != TransferKind.DIRECTORY

    private fun absoluteRemote(path: String): String {
        if (!path.startsWith('/')) throw IOException("Remote backup path must be absolute")
        val result = path.trimEnd('/').ifEmpty { "/" }
        if (result != "/" && !BackupFileContentFiles.validRelative(result.removePrefix("/"))) throw IOException("Invalid remote backup path")
        return result
    }

    private fun join(parent: String, child: String): String = when {
        child.isEmpty() -> parent
        parent.isEmpty() -> child
        else -> parent.trimEnd('/') + "/" + child
    }

    private inline fun <T> memoryLocked(kind: BackupContentKind, block: () -> T): T =
        if (kind == BackupContentKind.MEMORY) synchronized(MemorySource.storageLock, block) else block()

    private class TargetUnavailableException : IOException()
}
