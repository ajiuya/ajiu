package com.aicode.feature.workspace.domain.remote

import com.aicode.core.util.FileLogger
import com.aicode.core.util.GitIgnoreMatcher
import com.aicode.feature.workspace.domain.model.SyncConnectionState
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.async
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File

class SyncEngine(
    private val mount: com.aicode.feature.workspace.domain.model.RemoteMount,
    private val connection: com.aicode.feature.workspace.domain.model.RemoteConnection,
    private val syncClient: RemoteSyncClient,
    private val auth: RemoteAuth,
    private val ignoredPatternsStr: String,
    private val useGitIgnore: Boolean,
    private val maxSyncBatchSize: Int,
    /** 同步指纹索引：未变文件据此跳过重传（见 [SyncDecision]）。 */
    private val indexStore: SyncIndexStore,
    private val dispatcher: CoroutineDispatcher = Dispatchers.IO
) {
    companion object {
        private const val TAG = "SyncEngine"
        private const val PING_INTERVAL_MS = 15_000L
        private const val RECONNECT_BASE_MS = 5_000L
        private const val RECONNECT_MAX_MS = 60_000L
        private const val FILE_SYNC_DELAY_MS = 50L

        /** 待同步路径上限；溢出要求全量对账，不静默丢失同步状态。 */
        private const val MAX_PENDING_SYNC = 4096

        private val HEX = "0123456789abcdef".toCharArray()
    }

    private val customIgnores = ignoredPatternsStr.split(",").map { it.trim() }.filter { it.isNotEmpty() }.toSet()
    private var gitIgnorePatterns = emptyList<String>()

    private fun isIgnored(path: String): Boolean {
        if (indexStore.ownsPath(path)) return true
        // 统一转为「相对挂载根」的路径段再匹配：GitIgnoreMatcher 契约是相对路径段，
        // 直接传宿主/远程绝对路径会让前缀段参与匹配，可能误命中（如 gitignore 写了 `data` 而
        // 宿主路径恰好有 /data 前缀）。本地路径剥 localMountPath，远程路径剥 remotePath。
        val relative = path
            .removePrefix(mount.localMountPath)
            .removePrefix(mount.remotePath)
            .trimStart('/', File.separatorChar)
        val parts = relative.split(File.separatorChar, '/').filter { it.isNotEmpty() }

        // 1. 检查自定义忽略规则 (主要针对目录名和文件名)
        if (parts.any { it in customIgnores }) return true

        // 2. 检查 .gitignore 规则
        if (useGitIgnore && gitIgnorePatterns.isNotEmpty()) {
            if (GitIgnoreMatcher.isIgnored(gitIgnorePatterns, parts)) {
                return true
            }
        }
        return false
    }

    /** 相对挂载根的路径，作为索引 key（挂载本地路径改动后仍可复用）。 */
    private fun relativeKey(absolutePath: String): String =
        File(mount.localMountPath).absoluteFile.toPath().normalize()
            .relativize(File(absolutePath).absoluteFile.toPath().normalize()).toString()

    /** 计算文件内容 SHA-256（十六进制小写）；读盘失败返回 null（调用方据此保守上传）。 */
    private fun sha256Of(file: File): String? = runCatching {
        val digest = java.security.MessageDigest.getInstance("SHA-256")
        file.inputStream().use { input ->
            val buf = ByteArray(64 * 1024)
            while (true) {
                val n = input.read(buf)
                if (n < 0) break
                if (n > 0) digest.update(buf, 0, n)
            }
        }
        val hex = StringBuilder(64)
        for (b in digest.digest()) hex.append(HEX[(b.toInt() ushr 4) and 0xF]).append(HEX[b.toInt() and 0xF])
        hex.toString()
    }.getOrNull()

    /** 判定某文件是否需要上传；命中跳过时顺带刷新 mtime，避免下次重复算哈希。 */
    private fun shouldUpload(rel: String, file: File): Boolean {
        val action = SyncDecision.decide(currentFingerprint(rel), file.length(), file.lastModified()) { sha256Of(file) }
        if (action == SyncDecision.Action.SKIP_UNCHANGED) {
            currentFingerprint(rel)?.let { stored ->
                if (stored.mtimeMs != file.lastModified()) {
                    updateFingerprint(rel, stored.copy(mtimeMs = file.lastModified()))
                }
            }
            return false
        }
        return true
    }

    private suspend fun uploadAndRecord(rel: String, file: File, remotePath: String) {
        val before = FileFingerprint(file.length(), file.lastModified(), sha256Of(file))
        syncClient.uploadFile(file.absolutePath, remotePath)
        val after = FileFingerprint(file.length(), file.lastModified(), sha256Of(file))
        if (before.sha256 == null || before != after) {
            requestReconcile()
            return
        }
        updateFingerprint(rel, after)
    }

    private fun matchesContent(rel: String, file: File): Boolean {
        val stored = currentFingerprint(rel) ?: return false
        return stored.size == file.length() && stored.sha256 != null && stored.sha256 == sha256Of(file)
    }

    private val operationMutex = Mutex()
    private val pendingIndex = LinkedHashMap<String, FileFingerprint?>()
    private val batchLimit = maxSyncBatchSize.coerceIn(1, MAX_PENDING_SYNC)

    private fun currentFingerprint(path: String): FileFingerprint? =
        if (pendingIndex.containsKey(path)) pendingIndex[path] else fingerprints[path]

    private fun updateFingerprint(path: String, value: FileFingerprint?) {
        if (currentFingerprint(path) == value && path !in observed) return
        pendingIndex[path] = value
        if (pendingIndex.size >= batchLimit) saveIndex()
    }

    private class IndexWriteFailure(cause: Exception) : java.io.IOException("Sync index checkpoint failed", cause)

    private fun saveIndex() {
        if (pendingIndex.isEmpty()) return
        try {
            indexStore.applyChanges(mount.id, pendingIndex)
        } catch (failure: Exception) {
            pendingIndex.clear()
            requestReconcile()
            throw IndexWriteFailure(failure)
        }
        pendingIndex.forEach { (key, value) ->
            if (value == null) fingerprints.remove(key) else fingerprints[key] = value
            observed.remove(key)
        }
        pendingIndex.clear()
    }

    private val scope = CoroutineScope(SupervisorJob() + dispatcher)

    /** 相对挂载根路径 → 上次成功同步的指纹；用于跳过未变文件。 */
    private val fingerprints = java.util.concurrent.ConcurrentHashMap<String, FileFingerprint>()

    private val _connectionState = MutableStateFlow(SyncConnectionState.CONNECTED)
    /** 连接健康度：探活失败进 RECONNECTING，重连成功回 CONNECTED，供 UI 展示。 */
    val connectionState: StateFlow<SyncConnectionState> = _connectionState.asStateFlow()
    
    private val pending = SyncPendingQueue(MAX_PENDING_SYNC)
    private val observed = LinkedHashMap<String, FileFingerprint>()
    private var observationInitialized = false

    init {
        fingerprints.putAll(indexStore.load(mount.id))
        val reconcileOnStart = indexStore.needsReconcile(mount.id) || fingerprints.isNotEmpty()
        // A watcher cannot durably log its last event before process death; keep the lease dirty.
        indexStore.requireReconcile(mount.id)
        if (reconcileOnStart) requestReconcile()
        if (useGitIgnore) {
            val gitignore = File(mount.localMountPath, ".gitignore")
            if (gitignore.exists()) {
                gitIgnorePatterns = gitignore.readLines()
                    .filter { it.isNotBlank() && !it.startsWith("#") }
                    .map { it.trim().removeSuffix("/") }
            }
        }

        scope.launch {
            while (isActive && !observationInitialized) {
                try {
                    operationMutex.withLock { ensureObservationBaseline() }
                } catch (failure: Exception) {
                    if (failure is CancellationException) throw failure
                    FileLogger.e(TAG, "无法建立同步观测基线，等待重试", failure)
                    delay(1000)
                }
            }
            var retryPaths = emptyList<String>()
            while (isActive) {
                val batch = if (retryPaths.isNotEmpty()) SyncPendingQueue.Batch(retryPaths, reconcile = true)
                else {
                    pending.wakeup.receive()
                    pending.take(batchLimit)
                }
                var completed = 0
                try {
                    operationMutex.withLock {
                        if (batch.reconcile) reconcileWorkspace()
                        for (localPath in batch.paths) {
                            handleLocalChange(localPath, force = true)
                            completed++
                        }
                        saveIndex()
                    }
                    retryPaths = emptyList()
                } catch (failure: Exception) {
                    if (failure is CancellationException) throw failure
                    // Keep the bounded in-flight force batch even when the pending queue is full.
                    retryPaths = batch.paths.drop(completed)
                    FileLogger.e(TAG, "同步批次失败，保留全量对账请求", failure)
                    requestReconcile()
                    delay(1000)
                }
                if (batch.paths.size >= batchLimit) delay(300)
            }
        }

        // 断线自动重连 supervisor：定期探活，断开则按退避重连。
        scope.launch {
            var backoffMs = RECONNECT_BASE_MS
            while (isActive) {
                delay(PING_INTERVAL_MS)
                if (!syncClient.ping()) {
                    FileLogger.w(TAG, "Sync: 连接已断开（${connection.name}），开始自动重连")
                    _connectionState.value = SyncConnectionState.RECONNECTING
                    while (isActive && !syncClient.ping()) {
                        delay(backoffMs)
                        try {
                            syncClient.reconnect(connection.host, connection.port, connection.username, auth)
                            backoffMs = RECONNECT_BASE_MS
                            _connectionState.value = SyncConnectionState.CONNECTED
                            requestReconcile()
                            FileLogger.i(TAG, "Sync: 自动重连成功（${connection.name}）")
                        } catch (e: Exception) {
                            if (e is CancellationException) throw e
                            FileLogger.e(TAG, "Sync: 自动重连失败（${connection.name}），${backoffMs / 1000}s 后重试: ${e.message}")
                            backoffMs = (backoffMs * 2).coerceAtMost(RECONNECT_MAX_MS)
                        }
                    }
                    _connectionState.value = SyncConnectionState.CONNECTED
                    requestReconcile()
                }
            }
        }
    }

    /**
     * 全量拉取远程工作区到本地
     */
    suspend fun downloadWorkspace() = runFullSync {
        if (!syncClient.isConnected()) {
            throw IllegalStateException("Client not connected")
        }
        val localRoot = File(mount.localMountPath)
        if (!localRoot.exists()) {
            localRoot.mkdirs()
        }
        
        // 简单递归下载实现
        suspend fun pull(remoteDir: String, localDir: File) {
            val files = syncClient.listFiles(remoteDir)
            for (file in files) {
                currentCoroutineContext().ensureActive()
                if (isIgnored("$remoteDir/${file.name}")) continue
                val rPath = "$remoteDir/${file.name}"
                val lFile = File(localDir, file.name)
                if (file.isDirectory) {
                    lFile.mkdirs()
                    updateFingerprint(relativeKey(lFile.absolutePath), FileFingerprint(-1, 0))
                    pull(rPath, lFile)
                } else {
                    try {
                        syncClient.downloadFile(rPath, lFile.absolutePath)
                        val sha = sha256Of(lFile)
                        if (sha != null) updateFingerprint(relativeKey(lFile.absolutePath),
                            FileFingerprint(lFile.length(), lFile.lastModified(), sha))
                        else requestReconcile()
                        delay(FILE_SYNC_DELAY_MS)
                    } catch (e: Exception) {
                        if (e is CancellationException) throw e
                        FileLogger.e(TAG, "Download Error for $rPath: ${e.message}")
                        if (e is IndexWriteFailure) throw e
                        if (e !is RemoteFileRejectedException) { requestReconcile(); forceReconnect() }
                    }
                }
            }
        }
        pull(mount.remotePath, localRoot)
        saveIndex()
    }

    /**
     * 全量推送本地工作区到远程
     */
    suspend fun uploadWorkspace(force: Boolean = false) = runFullSync {
        if (!syncClient.isConnected()) {
            throw IllegalStateException("Client not connected")
        }
        val localRoot = File(mount.localMountPath)
        if (!localRoot.exists()) return@runFullSync

        suspend fun push(localDir: File, remoteDir: String) {
            val files = localDir.listFiles() ?: return
            for (file in files) {
                currentCoroutineContext().ensureActive()
                if (isIgnored(file.absolutePath)) continue
                val rPath = "$remoteDir/${file.name}"
                if (file.isDirectory) {
                    // 远程目录可能已由先前同步创建，已存在时报错可忽略。
                    try {
                        syncClient.createDirectory(rPath)
                        updateFingerprint(relativeKey(file.absolutePath), FileFingerprint(-1, 0))
                    } catch (e: Exception) {
                        if (e is CancellationException || e is IndexWriteFailure) throw e
                        FileLogger.w(TAG, "Sync: 远程目录创建失败 $rPath: ${e.message}")
                        if (e !is RemoteFileRejectedException) requestReconcile()
                    }
                    push(file, rPath)
                } else {
                    val rel = relativeKey(file.absolutePath)
                    if (!force && !shouldUpload(rel, file)) {
                        FileLogger.d(TAG, "Sync: 未变化，跳过上传 $rPath")
                        continue
                    }
                    try {
                        uploadAndRecord(rel, file, rPath)
                        delay(FILE_SYNC_DELAY_MS)
                    } catch (e: Exception) {
                        if (e is CancellationException) throw e
                        FileLogger.e(TAG, "Upload Error for $rPath: ${e.message}")
                        if (e is IndexWriteFailure) throw e
                        if (e is RemoteFileRejectedException) continue
                        requestReconcile()
                        forceReconnect()
                    }
                }
            }
        }
        push(localRoot, mount.remotePath)
        saveIndex()
    }

    /**
     * 外部（[com.aicode.feature.workspace.domain.repository.RemoteRepository] 的中心服务订阅）发现
     * 本地变更后投递进来；与失败重试共用同一队列，保持原有批处理与节流语义。
     */
    fun enqueueLocalChange(localPath: String) {
        val root = File(mount.localMountPath).absoluteFile.toPath().normalize()
        val candidate = File(localPath).absoluteFile.toPath().normalize()
        if (candidate == root || !candidate.startsWith(root) || indexStore.ownsPath(localPath)) return
        if (useGitIgnore && candidate == root.resolve(".gitignore")) requestReconcile()
        if (!isIgnored(localPath)) pending.offer(candidate.toString())
    }

    fun requestReconcile() = pending.requestReconcile()

    suspend fun clearSyncIndex() {
        val operation = scope.async {
            operationMutex.withLock {
                indexStore.clear(mount.id)
                fingerprints.clear()
                pendingIndex.clear()
                observed.clear()
                val baseline = indexStore.observationBaseline(mount.id)
                if (baseline != null) observed.putAll(baseline)
                observationInitialized = baseline != null
            }
        }
        try { operation.await() } finally { operation.cancel() }
    }

    private suspend fun ensureObservationBaseline() {
        if (observationInitialized) return
        val existing = indexStore.observationBaseline(mount.id)
        val baseline = existing ?: run {
            val root = File(mount.localMountPath)
            check(root.isDirectory) { "Local sync root unavailable" }
            val result = LinkedHashMap<String, FileFingerprint>()
            suspend fun visit(dir: File) {
                val files = dir.listFiles() ?: error("Cannot enumerate local sync directory")
                for (file in files) {
                    currentCoroutineContext().ensureActive()
                    if (isIgnored(file.absolutePath)) continue
                    val rel = relativeKey(file.absolutePath)
                    if (!fingerprints.containsKey(rel)) result[rel] = if (file.isDirectory) FileFingerprint(-1, 0)
                    else FileFingerprint(file.length(), file.lastModified(),
                        sha256Of(file) ?: error("Cannot fingerprint local sync baseline"))
                    if (file.isDirectory && !java.nio.file.Files.isSymbolicLink(file.toPath())) visit(file)
                }
            }
            visit(root)
            indexStore.initializeObservationBaseline(mount.id, result)
            result
        }
        observed.putAll(baseline)
        observationInitialized = true
    }

    private suspend fun reconcileWorkspace() {
        val root = File(mount.localMountPath)
        check(root.isDirectory) { "Local sync root unavailable" }
        if (useGitIgnore) gitIgnorePatterns = File(root, ".gitignore").takeIf { it.isFile }?.readLines()
            ?.map { it.trim().removeSuffix("/") }?.filter { it.isNotEmpty() && !it.startsWith("#") }.orEmpty()
        suspend fun visit(dir: File) {
            val files = dir.listFiles() ?: error("Cannot enumerate local sync directory")
            for (file in files) {
                currentCoroutineContext().ensureActive()
                if (isIgnored(file.absolutePath)) continue
                handleLocalChange(file.absolutePath, reconcile = true)
                if (file.isDirectory && !java.nio.file.Files.isSymbolicLink(file.toPath())) visit(file)
            }
        }
        visit(root)
        val missing = (fingerprints.keys + pendingIndex.keys + observed.keys).filter { rel ->
            (currentFingerprint(rel) != null || rel in observed) &&
                !isIgnored(File(root, rel).absolutePath) && !File(root, rel).exists()
        }.sortedByDescending { it.length }
        for (rel in missing) if (currentFingerprint(rel) != null || rel in observed) {
            handleLocalChange(File(root, rel).absolutePath, reconcile = true)
        }
        saveIndex()
    }

    private suspend fun handleLocalChange(localPath: String, force: Boolean = false, reconcile: Boolean = false) {
        if (isIgnored(localPath)) return
        val file = File(localPath)
        // 计算对应的远程路径
        val relativePath = relativeKey(localPath)
        val remotePath = "${mount.remotePath}/$relativePath"

        try {
            if (file.exists()) {
                if (file.isDirectory) {
                    if (reconcile && currentFingerprint(relativePath) == null && observed[relativePath]?.size == -1L) return
                    syncClient.createDirectory(remotePath)
                    updateFingerprint(relativePath, FileFingerprint(-1, 0))
                    if (!reconcile) requestReconcile()
                    FileLogger.i(TAG, "Sync: Created remote directory $remotePath")
                } else if (!force && (if (reconcile) {
                    matchesContent(relativePath, file) || (currentFingerprint(relativePath) == null &&
                        observed[relativePath]?.let { it.size == file.length() && it.sha256 == sha256Of(file) } == true)
                } else !shouldUpload(relativePath, file))) {
                    FileLogger.d(TAG, "Sync: 未变化，跳过上传 $remotePath")
                } else {
                    uploadAndRecord(relativePath, file, remotePath)
                    FileLogger.i(TAG, "Sync: Uploaded to $remotePath")
                    delay(FILE_SYNC_DELAY_MS)
                }
            } else {
                val descendants = (fingerprints.keys + pendingIndex.keys + observed.keys).filter {
                    it.startsWith("$relativePath/") && (currentFingerprint(it) != null || it in observed)
                }.sortedByDescending { it.length }
                for (child in descendants) {
                    syncClient.delete("${mount.remotePath}/$child")
                    updateFingerprint(child, null)
                }
                syncClient.delete(remotePath)
                updateFingerprint(relativePath, null)
                FileLogger.i(TAG, "Sync: Deleted $remotePath")
                delay(FILE_SYNC_DELAY_MS)
            }
        } catch (e: Exception) {
            if (e is CancellationException) throw e
            FileLogger.e(TAG, "Sync Error for $localPath: ${e.message}", e)
            if (e is RemoteFileRejectedException) {
                FileLogger.w(TAG, "Sync: 服务器拒绝该路径，跳过重连和重试: $remotePath")
                return
            }
            requestReconcile()
            if (e is IndexWriteFailure || reconcile) throw e
            forceReconnect()
            if (force) throw e
        }
    }

    private suspend fun runFullSync(block: suspend () -> Unit) {
        val operation = scope.async {
            operationMutex.withLock {
                ensureObservationBaseline()
                try { block() } finally { saveIndex() }
            }
        }
        try {
            operation.await()
        } finally {
            operation.cancel()
        }
    }

    suspend fun shutdown() {
        _connectionState.value = SyncConnectionState.DISCONNECTED
        scope.cancel()
        withContext(NonCancellable + Dispatchers.IO) {
            scope.coroutineContext[Job]?.join()
            try {
                operationMutex.withLock { saveIndex() }
            } catch (failure: Exception) {
                FileLogger.w(TAG, "关闭同步前保存索引失败", failure)
                throw failure
            } finally {
                runCatching { syncClient.disconnect() }
                    .onFailure { FileLogger.w(TAG, "关闭同步连接失败", it) }
            }
        }
    }

    private suspend fun forceReconnect() {
        currentCoroutineContext().ensureActive()
        try {
            // 断开与重连必须原子完成，否则其它协程可能插入到半连接状态上操作。
            syncClient.reconnect(connection.host, connection.port, connection.username, auth)
            _connectionState.value = SyncConnectionState.CONNECTED
            requestReconcile()
            FileLogger.i(TAG, "Sync: Force reconnected to server successfully.")
        } catch (e: Exception) {
            if (e is CancellationException) throw e
            FileLogger.e(TAG, "Sync: Failed to force reconnect: ${e.message}")
        }
    }
}
