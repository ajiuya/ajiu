package com.aicode.core.watch

import android.os.FileObserver
import com.aicode.core.util.FileLogger
import com.aicode.feature.agent.domain.container.ContainerInstaller
import com.aicode.feature.agent.domain.container.runtime.LocalRuntimeAuthorizationRequired
import com.aicode.feature.settings.data.repository.ContainerSettingsRepository
import com.aicode.feature.settings.data.repository.ExecutionMode
import com.aicode.feature.settings.data.repository.ExecutionModeHolder
import com.aicode.feature.workspace.data.repository.WorkspaceRepository
import com.aicode.feature.workspace.domain.WorkspacePathMapper
import com.aicode.feature.workspace.domain.WorkspaceExecutionContext
import com.aicode.feature.workspace.domain.WorkspaceExecutionContextFactory
import com.aicode.feature.workspace.domain.model.Workspace
import java.io.Closeable
import java.io.File
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.buffer
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.emitAll
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.merge
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.flow.transform
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * App 级文件变更监听中心：监听当前活动工作区与 `~/.aicode` 两个根（也可订阅任意宿主目录），
 * 变更合并成批后广播；订阅方按需声明范围与过滤规则，没人订阅的目录不持有 inotify 句柄、不轮询。
 *
 * 事件来源两层：
 * - 每目录单层 inotify（订阅声明递归时随新目录自动补挂）；
 * - 订阅级快照轮询兜底（按订阅范围递归比对 mtime/size），覆盖 PRoot 绑定目录与外部 FUSE 目录上
 *   inotify 失效的机型，也能发现「整棵目录被移动/替换」这类 inotify 事件不完整的变更。
 *
 * 过滤按订阅方生效：递归剪枝与事件投递都用订阅自己的 [WatchFilter]，同一目录可以对一个订阅可见、
 * 对另一个订阅被忽略。剪枝只看父路径段，故被剪枝目录自身的增删仍会上报（父目录列表才看得到它）。
 */
@OptIn(ExperimentalCoroutinesApi::class)
@Singleton
class FileChangeHub internal constructor(
    private val workspaceRepository: WorkspaceRepository,
    private val containerInstaller: ContainerInstaller,
    private val pathMapper: WorkspacePathMapper,
    private val executionModeHolder: ExecutionModeHolder,
    private val ioDispatcher: CoroutineDispatcher,
    mainDispatcher: CoroutineDispatcher,
    private val observerFactory: (String, Int, (FileObserver, Int, String?) -> Unit) -> FileObserver = { path, mask, accept ->
        @Suppress("DEPRECATION")
        object : FileObserver(path, mask) {
            override fun onEvent(event: Int, path: String?) = accept(this, event, path)
        }
    },
    snapshot: (SnapshotScope, Int) -> ScopeSnapshot = ::snapshotOfScope,
    private val remoteFileWatchHub: RemoteFileWatchHub = RemoteFileWatchHub(),
    private val executionFactory: WorkspaceExecutionContextFactory? = null,
    private val profileChanges: Flow<Long> = flowOf(0L)
) {
    @Inject
    constructor(
        workspaceRepository: WorkspaceRepository,
        containerInstaller: ContainerInstaller,
        pathMapper: WorkspacePathMapper,
        executionModeHolder: ExecutionModeHolder,
        remoteFileWatchHub: RemoteFileWatchHub,
        executionFactory: WorkspaceExecutionContextFactory,
        settings: ContainerSettingsRepository
    ) : this(workspaceRepository, containerInstaller, pathMapper, executionModeHolder, Dispatchers.IO, Dispatchers.Main.immediate,
        remoteFileWatchHub = remoteFileWatchHub, executionFactory = executionFactory,
        profileChanges = merge(settings.activeProfileSelectionRevisions, settings.profileConfigurationRevisions))

    companion object {
        private const val TAG = "FileChangeHub"

        /** 批量窗口默认长度：窗口内同路径变更合并为一条，窗口到期必发。 */
        const val DEFAULT_BATCH_WINDOW_MS = 300

        /** 单批明细上限，超出即截断（只保留「有变更」信号）。 */
        private const val MAX_BATCH_SIZE = 512

        /** 快照轮询兜底间隔。 */
        private const val POLL_INTERVAL_MS = 2000L

        /** 订阅根尚不存在时的探测间隔。 */
        private const val ROOT_WAIT_INTERVAL_MS = 2000L

        /** 单次快照最多收集的条目数，防止超大订阅每轮遍历开销失控。 */
        private const val MAX_SNAPSHOT_ENTRIES = 4096

        /** 单订阅可持有 inotify 句柄的目录数上限，防止大仓库把 inotify watch 用尽。 */
        private const val MAX_WATCHED_DIRS = 512

        /** 待处理路径上限；超出时显式发布 truncated，由消费方重新对账。 */
        private const val MAX_PENDING_EVENTS = 4096

        /** 待广播批次队列上限；慢消费者施加背压，不静默丢批次。 */
        private const val MAX_PENDING_BATCHES = 256

        /** AI 看到的工作区根路径。 */
        const val CONTAINER_ROOT = WorkspacePathMapper.CONTAINER_ROOT

        /** AI 配置目录在容器内的根路径。 */
        const val AICODE_ROOT = WorkspacePathMapper.AICODE_ROOT

        private const val IN_IGNORED = 0x00008000

        /**
         * 目录监听链路的逐目录 / 逐事件诊断打点。默认关闭：一次启动就会为每个被监听的目录
         * 打若干条，排查挂载竞态或 inotify 失效时再打开。
         */
        private const val DIAG = false

        private fun diag(message: String) {
            if (DIAG) FileLogger.d(TAG, "[diag] $message")
        }

        private val MASK = FileObserver.CREATE or FileObserver.DELETE or FileObserver.MOVED_TO or
            FileObserver.MOVED_FROM or FileObserver.CLOSE_WRITE
    }

    private val ioScope = CoroutineScope(SupervisorJob() + ioDispatcher)

    /** FileObserver 必须绑定有 Looper 的线程创建与 startWatching，故观察器起停统一走主线程。 */
    private val mainScope = CoroutineScope(SupervisorJob() + mainDispatcher)

    private val dirWatches = HashMap<String, DirWatch>()
    private val pendingStops = HashMap<String, Job>()

    // 嵌套取锁只允许 lifecycleLock → dirLock；观察器起停与向订阅回调都必须释放 dirLock。
    private val dirLock = Any()

    private val scopeSnapshots = SharedSnapshotPoller<SnapshotScope, ScopeSnapshot>(
        scope = ioScope,
        intervalMs = POLL_INTERVAL_MS,
        snapshot = { snapshot(it, MAX_SNAPSHOT_ENTRIES) },
        onError = { FileLogger.w(TAG, "目录快照失败，下轮继续", it) }
    )

    /** 两个根都订阅，递归。 */
    fun watchAll(
        filter: WatchFilter = WatchFilter.DEFAULT,
        fallbackPoll: Boolean = true,
        batchWindowMs: Int = DEFAULT_BATCH_WINDOW_MS
    ): Flow<FileChangeBatch> = merge(
        watchWorkspace(
            recursive = true,
            filter = filter,
            fallbackPoll = fallbackPoll,
            batchWindowMs = batchWindowMs
        ),
        watchAicode(
            recursive = true,
            filter = filter,
            fallbackPoll = fallbackPoll,
            batchWindowMs = batchWindowMs
        )
    )

    private data class WorkspaceWatchSource(
        val workspace: Workspace?,
        val workspacePath: String,
        val modeSelectionRevision: Long,
        val profile: WorkspacePathMapper.RuntimeProfileState
    )

    private data class WorkspaceWatchBatch(val source: WorkspaceWatchSource?, val batch: FileChangeBatch)

    private fun isCurrentWorkspaceSource(source: WorkspaceWatchSource): Boolean =
        source.profile.ready && !source.profile.profile.isChroot &&
            pathMapper.runtimeProfileState.value == source.profile &&
            executionModeHolder.currentMode() == ExecutionMode.LOCAL_PROOT &&
            executionModeHolder.modeSelectionRevision == source.modeSelectionRevision &&
            workspaceRepository.current.value == source.workspace && workspaceRepository.currentPath() == source.workspacePath

    /**
     * 订阅当前工作区（跟随工作区切换自动重建）。[containerSubPath] 是容器路径，默认整个工作区根。
     * 远程模式按冻结 SSH 来源共享快照轮询，不创建宿主观察器。
     */
    fun watchWorkspace(
        containerSubPath: String = CONTAINER_ROOT,
        recursive: Boolean = false,
        filter: WatchFilter = WatchFilter.DEFAULT,
        fallbackPoll: Boolean = true,
        batchWindowMs: Int = DEFAULT_BATCH_WINDOW_MS
    ): Flow<FileChangeBatch> = combine(workspaceRepository.current, executionModeHolder.mode,
        executionModeHolder.modeSelectionRevisions, profileChanges, pathMapper.runtimeProfileState) { current, _, revision, _, profile ->
        WorkspaceWatchSource(current, workspaceRepository.currentPath(), revision, profile)
    }.flatMapLatest { source ->
        if (executionModeHolder.currentMode() == ExecutionMode.REMOTE_SSH) {
            flow {
                val execution = executionFactory?.freeze() ?: throw IllegalStateException("Captured remote watch execution required")
                val path = withContext(ioDispatcher + execution) { execution.fileAccess.resolvePath(containerSubPath, true) }
                emitAll(watchRemoteDir(execution, path, recursive, filter).map { WorkspaceWatchBatch(null, it) })
            }
        } else if (!isCurrentWorkspaceSource(source)) {
            emptyFlow()
        } else {
            flow {
                if (!isCurrentWorkspaceSource(source)) return@flow
                val hostDir = try {
                    pathMapper.toHostFile(containerSubPath)
                } catch (_: LocalRuntimeAuthorizationRequired) {
                    return@flow
                }
                if (!isCurrentWorkspaceSource(source)) return@flow
                emitAll(watchHostDir(hostDir, containerSubPath.trimEnd('/'), ChangeRoot.WORKSPACE,
                    recursive, filter, fallbackPoll, batchWindowMs) { isCurrentWorkspaceSource(source) }
                    .map { WorkspaceWatchBatch(source, it) })
            }
        }
    }.buffer(0).transform { (source, batch) ->
        if (source == null || isCurrentWorkspaceSource(source)) emit(batch)
    }

    fun watchRemoteDir(execution: WorkspaceExecutionContext, rootPath: String, recursive: Boolean = false,
        filter: WatchFilter = WatchFilter.DEFAULT): Flow<FileChangeBatch> = remoteFileWatchHub.watch(execution, rootPath, recursive, filter)

    /** 订阅 AI 配置目录（`~/.aicode`）下的子路径，[containerSubPath] 为空表示该目录本身。 */
    fun watchAicode(
        containerSubPath: String = "",
        recursive: Boolean = false,
        filter: WatchFilter = WatchFilter.DEFAULT,
        fallbackPoll: Boolean = true,
        batchWindowMs: Int = DEFAULT_BATCH_WINDOW_MS
    ): Flow<FileChangeBatch> {
        val sub = containerSubPath.trim('/')
        return watchHostDir(
            hostDir = if (sub.isEmpty()) containerInstaller.aicodeDir else File(containerInstaller.aicodeDir, sub),
            containerRootPath = if (sub.isEmpty()) AICODE_ROOT else "$AICODE_ROOT/$sub",
            root = ChangeRoot.AICODE,
            recursive = recursive,
            filter = filter,
            fallbackPoll = fallbackPoll,
            batchWindowMs = batchWindowMs
        )
    }

    /**
     * 订阅任意宿主目录（如同步引擎的本地镜像目录）。[containerRootPath] 为 null 时容器路径由
     * [WorkspacePathMapper.toContainerPath] 反推。目录尚不存在时不放弃订阅——等它出现后再挂 watch，
     * 并把「目录出现」当作一次变更上报（项目级 `.aicode/skills` 这类目录经常是后建的）。
     */
    fun watchHostDir(
        hostDir: File,
        containerRootPath: String? = null,
        root: ChangeRoot = ChangeRoot.OTHER,
        recursive: Boolean = false,
        filter: WatchFilter = WatchFilter.DEFAULT,
        fallbackPoll: Boolean = true,
        batchWindowMs: Int = DEFAULT_BATCH_WINDOW_MS
    ): Flow<FileChangeBatch> = watchHostDir(hostDir, containerRootPath, root, recursive, filter,
        fallbackPoll, batchWindowMs) { true }

    private fun watchHostDir(
        hostDir: File,
        containerRootPath: String?,
        root: ChangeRoot,
        recursive: Boolean,
        filter: WatchFilter,
        fallbackPoll: Boolean,
        batchWindowMs: Int,
        isCurrentScope: () -> Boolean
    ): Flow<FileChangeBatch> = flow {
        if (!isCurrentScope()) return@flow
        val subscription = Subscription(
            rootDir = hostDir,
            containerRootPath = containerRootPath,
            rootKind = root,
            recursive = recursive,
            filter = filter,
            fallbackPoll = fallbackPoll,
            windowMs = batchWindowMs,
            parentJob = currentCoroutineContext()[Job],
            isCurrentScope = isCurrentScope
        )
        try {
            withContext(ioDispatcher) { subscription.start() }
            emitAll(subscription.batches)
        } finally {
            subscription.stop()
        }
    }

    /** 一个订阅：持有自己注册的目录集合、过滤规则、批量窗口与快照轮询。 */
    private inner class Subscription(
        private val rootDir: File,
        private val containerRootPath: String?,
        private val rootKind: ChangeRoot,
        private val recursive: Boolean,
        private val filter: WatchFilter,
        private val fallbackPoll: Boolean,
        private val windowMs: Int,
        parentJob: Job?,
        private val isCurrentScope: () -> Boolean
    ) {
        private val events = PendingFileChanges(MAX_PENDING_EVENTS)
        private val batchesChannel = Channel<FileChangeBatch>(MAX_PENDING_BATCHES)
        val batches: Flow<FileChangeBatch> = batchesChannel.receiveAsFlow()

        private val lifecycleLock = Any()
        private val subscriptionJob = SupervisorJob(parentJob)
        private val scope = CoroutineScope(subscriptionJob + ioDispatcher)
        private val myDirs = HashMap<String, DirWatch>()

        @Volatile
        private var rules: IgnoreRules = IgnoreRules.of(filter, emptyList())
        private var pollLease: Closeable? = null
        private var attachJob: Job? = null
        private var stopped = false

        fun isCurrent(): Boolean = subscriptionJob.isActive && isCurrentScope()

        private fun ensureCurrent() {
            subscriptionJob.ensureActive()
            if (!isCurrentScope()) throw CancellationException("Workspace watch source changed")
        }

        fun start() {
            ensureCurrent()
            rules = IgnoreRules.of(
                filter,
                if (filter.followGitignore) readGitignorePatterns(rootDir) else emptyList()
            )
            ensureCurrent()
            scope.launch { mergeLoop() }
            if (rootDir.isDirectory) {
                attachTree()
            } else {
                awaitRoot()
            }
        }

        fun stop() {
            val stops = synchronized(lifecycleLock) {
                if (stopped) return
                stopped = true
                pollLease?.close()
                pollLease = null
                attachJob = null
                synchronized(dirLock) {
                    myDirs.values.mapNotNull { it.unsubscribe(this) }
                }.also { myDirs.clear() }
            }
            subscriptionJob.cancel()
            stops.forEach { it.start() }
            events.wakeup.cancel()
            batchesChannel.close()
        }

        private fun attachTree() {
            registerTree(rootDir, reportNew = false)
            startPolling()
        }

        /** 订阅根暂不存在：等它出现再挂 watch，出现本身算一次变更。 */
        private fun awaitRoot() {
            val job = synchronized(lifecycleLock) {
                if (stopped || !subscriptionJob.isActive || attachJob?.isActive == true) return
                scope.launch(start = CoroutineStart.LAZY) {
                    try {
                        while (isActive && !rootDir.isDirectory) delay(ROOT_WAIT_INTERVAL_MS)
                        subscriptionJob.ensureActive()
                        synchronized(lifecycleLock) { attachJob = null }
                        offerEvent(rootDir.absolutePath, ChangeKind.CREATED)
                        attachTree()
                    } finally {
                        val current = currentCoroutineContext()[Job]
                        synchronized(lifecycleLock) {
                            if (attachJob === current) attachJob = null
                        }
                    }
                }.also { attachJob = it }
            }
            job.start()
        }

        /** 递归注册目录；剪枝只针对父路径段，故先判后注册。[reportNew] 为真时对新挂上的目录补报其存量直接子项。 */
        private fun registerTree(dir: File, reportNew: Boolean) {
            ensureCurrent()
            val parts = relativePartsOf(rootDir, dir)
            if (parts.isNotEmpty() && (rules.isIgnoredDir(parts) || java.nio.file.Files.isSymbolicLink(dir.toPath()))) return
            register(dir, reportNew)
            if (!recursive) return
            subscriptionJob.ensureActive()
            dir.listFiles()?.forEach { child ->
                subscriptionJob.ensureActive()
                if (child.isDirectory && !java.nio.file.Files.isSymbolicLink(child.toPath())) registerTree(child, reportNew)
            }
        }

        private fun register(dir: File, reportNew: Boolean) {
            val start = synchronized(lifecycleLock) {
                if (stopped || !isCurrent()) return
                val path = dir.absolutePath
                if (myDirs.containsKey(path)) return
                synchronized(dirLock) {
                    if (dirWatches.size >= MAX_WATCHED_DIRS && !dirWatches.containsKey(path)) {
                        FileLogger.w(TAG, "监听目录数达上限 $MAX_WATCHED_DIRS，停止扩展: $path")
                        return
                    }
                    diag("注册目录监听: $path reportNew=$reportNew")
                    val watch = dirWatches.getOrPut(path) { DirWatch(dir) }
                    myDirs[path] = watch
                    watch.subscribe(this, reportNew)
                }
            }
            start?.start()
        }

        /** 挂载监听后补扫子项，覆盖首次扫描与 startWatching 之间新建的目录。 */
        private fun reportExistingChildren(dir: File) {
            subscriptionJob.ensureActive()
            val children = dir.listFiles() ?: return
            for (child in children) {
                subscriptionJob.ensureActive()
                offerEvent(child.absolutePath, ChangeKind.CREATED)
                if (recursive && child.isDirectory && !java.nio.file.Files.isSymbolicLink(child.toPath())) {
                    registerTree(child, reportNew = true)
                }
            }
        }

        fun onWatchReady(watch: DirWatch, dir: File) {
            launchForWatch(watch) { reportExistingChildren(dir) }
        }

        private fun launchForWatch(watch: DirWatch, action: () -> Unit) {
            val job = synchronized(lifecycleLock) {
                if (stopped || !subscriptionJob.isActive || myDirs[watch.path] !== watch) return
                scope.launch(start = CoroutineStart.LAZY) {
                    val current = synchronized(lifecycleLock) { myDirs[watch.path] === watch }
                    if (current) action()
                }
            }
            job.start()
        }

        fun onWatchInvalidated(watch: DirWatch) {
            val path = watch.path
            synchronized(lifecycleLock) {
                if (stopped || !subscriptionJob.isActive || myDirs[path] !== watch) return
                myDirs.remove(path)
            }
            scope.launch {
                FileLogger.w(TAG, "目录监听失效，准备重新挂载: $path")
                val dir = File(path)
                if (dir.isDirectory) {
                    registerTree(dir, reportNew = true)
                } else if (path == rootDir.absolutePath) {
                    awaitRoot()
                }
            }
        }

        fun onEvent(watch: DirWatch, hostPath: String, kind: ChangeKind) {
            synchronized(lifecycleLock) {
                if (stopped || !isCurrent() || myDirs[watch.path] !== watch) return
                offerEvent(hostPath, kind)
            }
            if (recursive && kind == ChangeKind.CREATED) {
                launchForWatch(watch) {
                    val child = File(hostPath)
                    if (child.isDirectory && !java.nio.file.Files.isSymbolicLink(child.toPath())) registerTree(child, reportNew = true)
                }
            }
        }

        /**
         * 快照轮询兜底：按订阅范围递归比对（非递归订阅只比根目录单层）。
         * 递归快照能发现 inotify 漏报的深层改动与「整棵子树被移动/替换」。
         */
        private fun startPolling() {
            synchronized(lifecycleLock) {
                if (stopped || !isCurrent() || !fallbackPoll || pollLease != null) return
                val key = SnapshotScope(rootDir.absoluteFile.toPath().normalize().toString(), recursive, rules)
                val leaseRules = rules
                pollLease = scopeSnapshots.subscribe(key) { last, now ->
                    synchronized(lifecycleLock) {
                        if (stopped || !isCurrent() || rules != leaseRules) return@subscribe
                        if (!last.complete || !now.complete) events.invalidate()
                    }
                    val diff = diffSnapshot(last.entries, now.entries)
                    for ((rel, kind) in diff) {
                        // A capped/unreadable scan cannot prove that an omitted path was deleted.
                        if (kind == ChangeKind.DELETED && !now.complete) continue
                        val hostPath = if (rel.isEmpty()) rootDir.absolutePath else File(rootDir, rel).absolutePath
                        offerEvent(hostPath, kind)
                        if (recursive && kind == ChangeKind.CREATED) {
                            scope.launch {
                                val child = File(hostPath)
                                if (child.isDirectory && !java.nio.file.Files.isSymbolicLink(child.toPath())) registerTree(child, reportNew = true)
                            }
                        }
                    }
                }
            }
        }

        private fun offerEvent(hostPath: String, kind: ChangeKind) {
            synchronized(lifecycleLock) {
                if (stopped || !isCurrent()) return
                val parents = relativePartsOf(rootDir, File(hostPath)).dropLast(1)
                if (!rules.isIgnoredDir(parents)) events.offer(hostPath, kind)
            }
        }

        private fun containerPathOf(hostPath: String): String {
            val base = containerRootPath ?: return pathMapper.toContainerPath(hostPath)
            val rel = relativePartsOf(rootDir, File(hostPath)).joinToString("/")
            return if (rel.isEmpty()) base else "$base/$rel"
        }

        private suspend fun mergeLoop() {
            while (true) {
                events.wakeup.receive()
                delay(windowMs.toLong())
                val (merged, truncated) = events.drain(MAX_BATCH_SIZE)
                if (filter.followGitignore && File(rootDir, ".gitignore").absolutePath in merged) {
                    val updated = IgnoreRules.of(filter, readGitignorePatterns(rootDir))
                    val changed = synchronized(lifecycleLock) {
                        if (rules == updated || stopped || !subscriptionJob.isActive) false else {
                            rules = updated
                            pollLease?.close()
                            pollLease = null
                            events.invalidate()
                            true
                        }
                    }
                    if (changed) {
                        startPolling()
                        registerTree(rootDir, reportNew = true)
                    }
                }
                if (!isCurrent() || (merged.isEmpty() && !truncated)) continue
                val changes = merged.map { (path, kind) ->
                    FileChange(rootKind, path, containerPathOf(path), kind)
                }
                batchesChannel.send(FileChangeBatch(changes, truncated))
            }
        }
    }

    /** 一个宿主目录的观察器，按订阅引用计数启停（多个订阅共享同一个）。 */
    private inner class DirWatch(private val dir: File) {
        val path = dir.absolutePath
        private val subscribers = HashSet<Subscription>()
        private val pendingReady = HashSet<Subscription>()
        private var observer: FileObserver? = null
        private var startJob: Job? = null

        fun subscribe(sub: Subscription, reportNew: Boolean): Job? {
            subscribers.add(sub)
            if (reportNew) pendingReady.add(sub)
            if (observer != null && pendingReady.isEmpty()) return null
            startJob?.takeUnless { it.isCompleted }?.let { return it }
            val previousStop = pendingStops[path]
            return mainScope.launch(start = CoroutineStart.LAZY) {
                val currentJob = currentCoroutineContext()[Job]
                previousStop?.join()
                val created = synchronized(dirLock) {
                    if (dirWatches[path] !== this@DirWatch || subscribers.none { it.isCurrent() }) {
                        if (startJob === currentJob) startJob = null
                        return@launch
                    }
                    if (observer == null) {
                        observerFactory(path, MASK) { current, event, child ->
                            diag("FileObserver.onEvent path=$path event=0x${Integer.toHexString(event)} child=$child")
                            if (event and IN_IGNORED != 0) {
                                invalidate(current)
                            } else {
                                val kind = kindOf(event)
                                if (child != null && kind != null) dispatch(current, child, kind)
                            }
                        }.also { observer = it }
                    } else null
                }
                // stop 会等本次 start 结束；系统回调可能在 startWatching 内同步到达，不能持有 dirLock。
                currentCoroutineContext().ensureActive()
                created?.startWatching()
                if (created != null) diag("startWatching path=$path")
                val ready = synchronized(dirLock) {
                    if (dirWatches[path] !== this@DirWatch || subscribers.none { it.isCurrent() }) {
                        if (startJob === currentJob) startJob = null
                        return@launch
                    }
                    if (startJob === currentJob) startJob = null
                    pendingReady.toList().also { pendingReady.clear() }
                }
                for (sub in ready) {
                    val active = synchronized(dirLock) { dirWatches[path] === this@DirWatch && sub in subscribers }
                    if (active) sub.onWatchReady(this@DirWatch, dir)
                }
            }.also { job ->
                startJob = job
                job.invokeOnCompletion {
                    synchronized(dirLock) { if (startJob === job) startJob = null }
                }
            }
        }

        fun unsubscribe(sub: Subscription): Job? {
            subscribers.remove(sub)
            pendingReady.remove(sub)
            if (subscribers.isNotEmpty()) return null
            dirWatches.remove(path, this)
            val starting = startJob
            startJob = null
            starting?.cancel()
            val current = observer ?: return null
            observer = null
            return queueStop(current, starting)
        }

        private fun queueStop(current: FileObserver, starting: Job?): Job {
            val previousStop = pendingStops[path]
            return mainScope.launch(start = CoroutineStart.LAZY) {
                try {
                    previousStop?.join()
                    starting?.join()
                    runCatching { current.stopWatching() }
                } finally {
                    val job = currentCoroutineContext()[Job]
                    synchronized(dirLock) { pendingStops.remove(path, job) }
                }
            }.also { pendingStops[path] = it }
        }

        private fun invalidate(current: FileObserver) {
            val (affected, stop) = synchronized(dirLock) {
                if (observer !== current || dirWatches[path] !== this) return
                observer = null
                val starting = startJob
                startJob = null
                starting?.cancel()
                pendingReady.clear()
                dirWatches.remove(path, this)
                subscribers.toList().also { subscribers.clear() } to queueStop(current, starting)
            }
            stop.start()
            FileLogger.w(TAG, "目录观察器被系统移除: $path，订阅数=${affected.size}")
            for (sub in affected) sub.onWatchInvalidated(this)
        }

        private fun dispatch(current: FileObserver, name: String, kind: ChangeKind) {
            val targets = synchronized(dirLock) {
                if (observer !== current || dirWatches[path] !== this) return
                subscribers.toList()
            }
            val hostPath = File(dir, name).absolutePath
            diag("dispatch hostPath=$hostPath kind=$kind subscribers=${targets.size}")
            for (sub in targets) sub.onEvent(this, hostPath, kind)
        }

        private fun kindOf(event: Int): ChangeKind? = when {
            event and (FileObserver.CREATE or FileObserver.MOVED_TO) != 0 -> ChangeKind.CREATED
            event and (FileObserver.DELETE or FileObserver.MOVED_FROM) != 0 -> ChangeKind.DELETED
            event and FileObserver.CLOSE_WRITE != 0 -> ChangeKind.MODIFIED
            else -> null
        }
    }
}