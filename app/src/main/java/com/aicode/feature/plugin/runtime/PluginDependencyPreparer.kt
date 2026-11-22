package com.aicode.feature.plugin.runtime

import com.aicode.feature.agent.domain.container.ContainerProfile
import com.aicode.feature.plugin.data.PluginFiles
import com.aicode.feature.plugin.domain.PluginCapture
import com.aicode.feature.plugin.domain.PluginManager
import com.aicode.feature.plugin.domain.PluginOperationResult
import java.io.File
import java.nio.file.Files
import java.nio.file.LinkOption.NOFOLLOW_LINKS
import java.nio.file.StandardOpenOption.CREATE_NEW
import java.nio.file.SimpleFileVisitor
import java.nio.file.FileVisitResult
import java.nio.file.Path
import java.nio.file.attribute.BasicFileAttributes
import java.nio.file.StandardOpenOption.WRITE
import java.util.UUID
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.cancel
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.joinAll
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.coroutines.CompletableDeferred

sealed interface PluginDependencyResult {
    data class Ready(val hash: String) : PluginDependencyResult
    data class MissingRuntime(val message: String) : PluginDependencyResult
    data class Failed(val message: String, val code: String? = null) : PluginDependencyResult
}

data class PluginDependencyRequest(
    val requestId: String,
    val pluginId: String,
    val installRevision: String,
    val activationEpoch: Long,
    val inputDigest: String,
    val tool: String,
    val expiresAt: Long
)

@Singleton
class PluginDependencyPreparer internal constructor(
    private val pluginManager: PluginManager,
    private val runtimeManager: PluginRuntimeManager,
    private val clock: () -> Long = System::currentTimeMillis
) {
    @Inject constructor(pluginManager: PluginManager, runtimeManager: PluginRuntimeManager) :
        this(pluginManager, runtimeManager, System::currentTimeMillis)

    private class Pending(val request: PluginDependencyRequest, val profile: ContainerProfile, val checkCurrent: () -> Unit)
    private class PreparationLane(val mutex: Mutex = Mutex(), var users: Int = 0)
    private val lock = Any()
    private val requests = mutableMapOf<String, Pending>()
    private val confirmations = mutableMapOf<String, Pending>()
    private val preparations = mutableMapOf<String, PreparationLane>()

    suspend fun describe(capture: PluginCapture): PluginDependencyRequest? = withContext(Dispatchers.IO) {
        capture.checkCurrent()
        if (capture.content.runtime?.dependencies == null) return@withContext null
        val inputs = PluginDependencyImage.inputs(capture)
        val profile = runtimeManager.captureLocalProfile()
        capture.checkCurrent()
        val request = PluginDependencyRequest(UUID.randomUUID().toString(), capture.id, capture.installRevision,
            capture.activationEpoch, inputs.digest, inputs.tool, clock() + 5 * 60_000)
        synchronized(lock) {
            prune()
            if (requests.size + confirmations.size >= 32) throw PluginProcessException("PLUGIN_DEPENDENCY_BUSY", "Dependency confirmation limit reached")
            requests[request.requestId] = Pending(request, profile, capture::checkActive)
        }
        request
    }

    fun confirm(request: PluginDependencyRequest): String = synchronized(lock) {
        prune()
        val pending = requests.remove(request.requestId)
        if (pending == null || pending.request != request || request.expiresAt <= clock()) {
            throw PluginProcessException("PLUGIN_CONFIRMATION_REQUIRED", "A current native dependency confirmation is required")
        }
        pending.checkCurrent()
        UUID.randomUUID().toString().also { confirmations[it] = pending }
    }

    suspend fun prepareDependencies(
        capture: PluginCapture,
        confirmedToken: String,
        expectedDigest: String
    ): PluginDependencyResult = withContext(Dispatchers.IO) {
        val pending = synchronized(lock) { prune(); confirmations.remove(confirmedToken) }
        if (pending == null || pending.request.expiresAt <= clock() ||
            pending.request.pluginId != capture.id || pending.request.installRevision != capture.installRevision ||
            pending.request.activationEpoch != capture.activationEpoch || pending.request.inputDigest != expectedDigest) {
            return@withContext PluginDependencyResult.Failed("A current one-use native dependency confirmation is required", "PLUGIN_CONFIRMATION_REQUIRED")
        }
        suspend fun checkTarget() {
            capture.checkCurrent()
            if (runtimeManager.captureLocalProfile() != pending.profile) {
                throw PluginProcessException("PLUGIN_DEPENDENCY_TARGET_CHANGED", "Local container changed; confirm dependency preparation again")
            }
            capture.checkCurrent()
        }
        val lane = synchronized(lock) { preparations.getOrPut(capture.id) { PreparationLane() }.also { it.users++ } }
        try {
            lane.mutex.withLock {
                if (pending.request.expiresAt <= clock()) {
                    return@withContext PluginDependencyResult.Failed("Dependency confirmation expired while waiting", "PLUGIN_CONFIRMATION_REQUIRED")
                }
                checkTarget()
                val dependency = capture.content.runtime?.dependencies
                    ?: return@withContext PluginDependencyResult.Failed("Dependency declaration changed", "PLUGIN_DEPENDENCY_CHANGED")
                val inputs = PluginDependencyImage.inputs(capture)
                if (inputs.digest != expectedDigest) {
                    return@withContext PluginDependencyResult.Failed("Dependency inputs changed; confirm the new request", "PLUGIN_DEPENDENCY_CHANGED")
                }
                val directory = pluginManager.dependencyDirectory(capture)
                if (pluginManager.areDependenciesPrepared(capture)) {
                    PluginDependencyImage.verify(capture, directory)
                    checkTarget()
                    return@withContext PluginDependencyResult.Ready(dependency.sha256)
                }
                val image = File(directory, "image")
                val command = if (inputs.tool == "npm") {
                    listOf("/bin/sh", "-c",
                        "command -v node >/dev/null 2>&1 && command -v npm >/dev/null 2>&1 || exit 127; exec npm \"\$@\"",
                        "plugin-dependencies") + if (inputs.hasLock) {
                        listOf("ci", "--no-audit", "--no-fund")
                    } else listOf("install", "--no-audit", "--no-fund", "--package-lock=false")
                } else {
                    listOf("/bin/sh", "-c",
                        "command -v python3 >/dev/null 2>&1 && python3 -m pip --version >/dev/null 2>&1 || exit 127; exec python3 -m pip \"\$@\"",
                        "plugin-dependencies", "install", "--disable-pip-version-check", "--no-input", "--target", "../python", "-r", inputs.relativeFile)
                }
                val result = runtimeManager.runPreparation(capture, pending.profile, image, command) {
                    PluginDependencyImage.copy(capture, directory)
                }
                checkTarget()
                if (result == 127) return@withContext PluginDependencyResult.MissingRuntime(
                    "Install node/npm or python3/pip in the local container environment before retrying")
                if (result != 0) return@withContext PluginDependencyResult.Failed("Dependency preparation failed (exit $result)", "PLUGIN_DEPENDENCY_FAILED")
                if (PluginDependencyImage.inputs(capture).digest != expectedDigest) {
                    return@withContext PluginDependencyResult.Failed("Dependency inputs changed during preparation", "PLUGIN_DEPENDENCY_CHANGED")
                }
                PluginFiles.ensureDirectory(if (inputs.tool == "npm") File(image, "node_modules") else File(directory, "python"))
                PluginDependencyImage.checkFiles(capture, image)
                val marker = File(directory, "prepared-input.sha256")
                if (Files.exists(marker.toPath(), NOFOLLOW_LINKS)) Files.delete(marker.toPath())
                Files.newOutputStream(marker.toPath(), CREATE_NEW, WRITE).use { it.write(expectedDigest.toByteArray(Charsets.US_ASCII)) }
                PluginDependencyImage.verify(capture, directory)
                checkTarget()
                when (pluginManager.markDependenciesPrepared(capture, dependency.sha256)) {
                    is PluginOperationResult.Failure -> PluginDependencyResult.Failed("Plugin changed before dependency preparation completed", "PLUGIN_DEPENDENCY_CHANGED")
                    PluginOperationResult.Success -> {
                        checkTarget()
                        PluginDependencyResult.Ready(dependency.sha256)
                    }
                }
            }
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (error: PluginProcessException) {
            if (error.code in setOf("PLUGIN_RUNTIME_MISSING", "PLUGIN_RUNTIME_UNAVAILABLE")) {
                PluginDependencyResult.MissingRuntime("Initialize the local container and install the declared runtime before retrying")
            } else PluginDependencyResult.Failed(error.message.take(240), error.code)
        } catch (_: Exception) {
            PluginDependencyResult.Failed("Dependency preparation failed; review the local runtime and confirm again", "PLUGIN_DEPENDENCY_FAILED")
        } finally {
            synchronized(lock) {
                lane.users--
                if (lane.users == 0 && preparations[capture.id] === lane) preparations.remove(capture.id)
            }
        }
    }

    private fun prune() {
        val now = clock()
        requests.entries.removeAll { it.value.request.expiresAt <= now }
        confirmations.entries.removeAll { it.value.request.expiresAt <= now }
    }
}

internal object PluginDependencyImage {
    data class Inputs(val digest: String, val tool: String, val relativeFile: String, val hasLock: Boolean)

    fun inputs(capture: PluginCapture): Inputs {
        val dependency = capture.content.runtime?.dependencies
            ?: throw PluginProcessException("PLUGIN_DEPENDENCY_CHANGED", "Plugin has no dependency declaration")
        val relative = capture.installDir.toPath().relativize(dependency.file.toPath()).joinToString("/")
        val bytes = PluginFiles.readRegular(capture.installDir, relative, 4 * 1024 * 1024)
        if (PluginFiles.sha256(bytes) != dependency.sha256) throw PluginProcessException("PLUGIN_DEPENDENCY_CHANGED", "Dependency input changed")
        if (dependency.tool == "npm" && relative != "package.json") {
            throw PluginProcessException("PLUGIN_DEPENDENCY_INPUT", "npm dependencies must declare the package-root package.json")
        }
        if (dependency.tool !in setOf("npm", "pip")) throw PluginProcessException("PLUGIN_DEPENDENCY_TOOL", "Unsupported dependency tool")
        val hasLock = dependency.tool == "npm" && capture.record.fileHashes.containsKey("package-lock.json")
        val digest = PluginFiles.sha256(buildString {
            append(capture.contentHash).append('\n').append(dependency.tool).append('\n')
            append(relative).append(':').append(dependency.sha256).append('\n')
            if (hasLock) append("package-lock.json:").append(capture.record.fileHashes.getValue("package-lock.json")).append('\n')
        }.toByteArray(Charsets.UTF_8))
        return Inputs(digest, dependency.tool, relative, hasLock)
    }

    fun copy(capture: PluginCapture, directory: File): File {
        capture.checkCurrent()
        val image = File(directory, "image")
        if (Files.exists(image.toPath(), NOFOLLOW_LINKS)) PluginFiles.deleteOwned(image)
        PluginFiles.ensureDirectory(image)
        val python = File(directory, "python")
        if (Files.exists(python.toPath(), NOFOLLOW_LINKS)) PluginFiles.deleteOwned(python)
        var total = 0L
        if (capture.record.fileHashes.size > 512) throw PluginProcessException("PLUGIN_IMAGE_LIMIT", "Plugin image entry limit reached")
        capture.record.fileHashes.forEach { (relative, expected) ->
            capture.checkActive()
            val bytes = PluginFiles.readRegular(capture.installDir, relative, 4 * 1024 * 1024)
            total += bytes.size
            if (total > 32L * 1024 * 1024 || PluginFiles.sha256(bytes) != expected) {
                throw PluginProcessException("PLUGIN_IMAGE_CHANGED", "Plugin image source changed or exceeded the byte limit")
            }
            val target = File(image, relative)
            PluginFiles.ensureDirectory(target.parentFile)
            Files.newOutputStream(target.toPath(), CREATE_NEW, WRITE).use { it.write(bytes) }
        }
        capture.checkCurrent()
        checkFiles(capture, image)
        return image
    }

    fun verify(capture: PluginCapture, directory: File): File {
        val marker = PluginFiles.readRegularText(directory, "prepared-input.sha256", 64)
        if (marker != inputs(capture).digest) throw PluginProcessException("PLUGIN_IMAGE_CHANGED", "Dependency input descriptor changed; reinstall the plugin")
        val image = File(directory, "image")
        checkFiles(capture, image)
        val dependency = requireNotNull(capture.content.runtime?.dependencies)
        val output = if (dependency.tool == "npm") File(image, "node_modules") else File(directory, "python")
        if (!Files.isDirectory(output.toPath(), NOFOLLOW_LINKS)) {
            throw PluginProcessException("PLUGIN_IMAGE_CHANGED", "Prepared dependency output is missing; reinstall the plugin")
        }
        return image
    }

    fun checkFiles(capture: PluginCapture, image: File) {
        var total = 0L
        capture.record.fileHashes.forEach { (relative, expected) ->
            capture.checkActive()
            val bytes = PluginFiles.readRegular(image, relative, 4 * 1024 * 1024)
            total += bytes.size
            if (total > 32L * 1024 * 1024 || PluginFiles.sha256(bytes) != expected) {
                throw PluginProcessException("PLUGIN_IMAGE_CHANGED", "Plugin image file changed; reinstall the plugin")
            }
        }
        val expected = capture.record.fileHashes.keys
        val npm = capture.content.runtime?.dependencies?.tool == "npm"
        var entries = 0
        Files.walkFileTree(image.toPath(), object : SimpleFileVisitor<Path>() {
            private fun relative(path: Path) = image.toPath().relativize(path).joinToString("/")
            override fun preVisitDirectory(dir: Path, attrs: BasicFileAttributes): FileVisitResult {
                capture.checkActive()
                if (++entries > 1024) throw PluginProcessException("PLUGIN_IMAGE_LIMIT", "Plugin image entry limit reached")
                if (dir == image.toPath()) return FileVisitResult.CONTINUE
                val name = relative(dir)
                if (npm && name == "node_modules") return FileVisitResult.SKIP_SUBTREE
                if (expected.none { it.startsWith("$name/") }) {
                    throw PluginProcessException("PLUGIN_IMAGE_CHANGED", "Plugin image contains an undeclared directory")
                }
                return FileVisitResult.CONTINUE
            }
            override fun visitFile(file: Path, attrs: BasicFileAttributes): FileVisitResult {
                capture.checkActive()
                if (++entries > 1024) throw PluginProcessException("PLUGIN_IMAGE_LIMIT", "Plugin image entry limit reached")
                val name = relative(file)
                if (name !in expected || attrs.isSymbolicLink || !attrs.isRegularFile) {
                    throw PluginProcessException("PLUGIN_IMAGE_CHANGED", "Plugin image contains an undeclared file")
                }
                return FileVisitResult.CONTINUE
            }
        })
    }
}

internal class PluginPreparationProcess(
    private val start: suspend () -> Process,
    private val checkCurrent: () -> Unit,
    dispatcher: CoroutineDispatcher = Dispatchers.IO,
    private val onClosed: () -> Unit
) : PluginOwnedProcess {
    private val lock = Any()
    private val owner = SupervisorJob()
    private val scope = CoroutineScope(owner + dispatcher)
    private val stopped = CompletableDeferred<Unit>()
    private var process: Process? = null
    private var closed = false

    suspend fun run(): Int {
        try {
            return withTimeoutOrNull(10 * 60_000L) {
                scope.async {
                    var acquired: Process? = null
                    try {
                        synchronized(lock) { if (closed) throw CancellationException("Dependency preparation closed") }
                        currentCoroutineContext().ensureActive()
                        checkCurrent()
                        val target = start()
                        acquired = target
                        currentCoroutineContext().ensureActive()
                        checkCurrent()
                        synchronized(lock) {
                            if (closed) throw CancellationException("Dependency preparation closed")
                            process = target
                            acquired = null
                        }
                        target.outputStream.close()
                        coroutineScope {
                            val output = async { drain(target.inputStream, PLUGIN_MAX_OUTPUT_BYTES) }
                            val errors = async { drain(target.errorStream, PLUGIN_MAX_STDERR_BYTES) }
                            try {
                                while (!target.waitFor(250, java.util.concurrent.TimeUnit.MILLISECONDS)) {
                                    currentCoroutineContext().ensureActive()
                                    checkCurrent()
                                    delay(10)
                                }
                                output.await()
                                errors.await()
                                checkCurrent()
                                target.exitValue()
                            } catch (error: Exception) {
                                // A failed drain must close the process before structured children can join a blocked peer stream.
                                withContext(NonCancellable) { dispose(target) }
                                throw error
                            }
                        }
                    } finally {
                        acquired?.let { withContext(NonCancellable) { dispose(it) } }
                    }
                }.await()
            } ?: throw PluginProcessException("PLUGIN_TIMEOUT", "Dependency preparation timed out")
        } finally { close() }
    }

    private suspend fun drain(input: java.io.InputStream, maximum: Long) {
        val buffer = ByteArray(8192)
        var total = 0L
        while (true) {
            currentCoroutineContext().ensureActive()
            checkCurrent()
            val count = input.read(buffer)
            checkCurrent()
            if (count < 0) return
            if (count == 0) throw PluginProcessException("PLUGIN_STREAM_STALLED", "Dependency process stream made no progress")
            total += count
            if (total > maximum) throw PluginProcessException("PLUGIN_OUTPUT_LIMIT", "Dependency output exceeded the byte limit")
        }
    }

    override fun close() {
        synchronized(lock) { if (closed) return; closed = true }
        scope.launch {
            val self = currentCoroutineContext()[Job]
            val children = owner.children.filter { it !== self }.toList()
            children.forEach { it.cancel() }
            synchronized(lock) { process }?.let { dispose(it) }
            children.joinAll()
            synchronized(lock) { process = null }
            try { onClosed() } finally { stopped.complete(Unit); owner.cancel() }
        }
    }

    override suspend fun awaitClosed() { stopped.await() }

    private suspend fun dispose(target: Process) {
        runCatching { target.destroy() }
        runCatching { if (target.isAlive) target.destroyForcibly() }
        runCatching { target.outputStream.close() }
        runCatching { target.inputStream.close() }
        runCatching { target.errorStream.close() }
        while (runCatching { target.isAlive }.getOrDefault(false)) {
            runCatching { target.waitFor(250, java.util.concurrent.TimeUnit.MILLISECONDS) }
            if (runCatching { target.isAlive }.getOrDefault(false)) delay(250)
        }
    }
}
