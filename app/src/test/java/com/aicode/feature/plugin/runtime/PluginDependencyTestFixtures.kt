package com.aicode.feature.plugin.runtime

import com.aicode.feature.agent.domain.container.ContainerProfile
import com.aicode.feature.plugin.domain.PluginCapture
import com.aicode.feature.plugin.domain.PluginCoreFixtures
import com.aicode.feature.plugin.domain.PluginManager
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.InputStream
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import kotlinx.coroutines.Dispatchers

internal class PluginDependencyTestFixture(root: File) {
    val manager = PluginManager(File(root, "plugins"), File(root, "registry.json"))
    val launches = CopyOnWriteArrayList<PluginLocalLaunch>()
    var now = 1_000L
    @Volatile var localProfile = ContainerProfile.BUILTIN_ALPINE
    var onStart: (PluginLocalLaunch) -> Process = { DependencyFakeProcess() }
    private val factory = object : PluginRuntimeProcessFactory {
        override suspend fun captureProfile() = localProfile
        override fun containerPath(file: File) = file.path
        override fun start(launch: PluginLocalLaunch): Process {
            launches += launch
            return onStart(launch)
        }
    }
    val runtime = PluginRuntimeManager(manager, factory, Dispatchers.IO)
    val preparer = PluginDependencyPreparer(manager, runtime) { now }

    suspend fun install(
        tool: String = "npm",
        version: String = "1.0.0",
        lock: Boolean = false,
        input: String = if (tool == "npm") "package.json" else "requirements.txt",
        entry: String = if (tool == "npm") "src/main.cjs" else "src/main.py",
        extra: Map<String, String> = emptyMap()
    ): PluginCapture {
        val type = if (tool == "npm") "javascript" else "python"
        val manifest = PluginCoreFixtures.manifest(
            runtime = """{"type":"$type","entry":"$entry","dependencies":{"tool":"$tool","file":"$input"}}""",
            version = version
        )
        val files = linkedMapOf(
            input to if (tool == "npm") """{"name":"fixture-dependencies","version":"1.0.0","dependencies":{"fixture-dep":"1.0.0"}}""" else "fixture-dep==1.0.0\n",
            "src/main.cjs" to "const value = require('fixture-dep'); module.exports = value;",
            "src/main.mjs" to "import value from 'fixture-dep'; export default value;",
            "src/main.py" to "import fixture_dep\n"
        )
        if (lock) files["package-lock.json"] = """{"name":"fixture-dependencies","version":"1.0.0","lockfileVersion":3,"packages":{}}"""
        files.putAll(extra)
        PluginCoreFixtures.install(manager, PluginCoreFixtures.archive(manifest,
            files.map { (name, content) -> name to content.toByteArray() }))
        return PluginCoreFixtures.activate(manager)
    }

    suspend fun prepare(capture: PluginCapture): PluginDependencyResult {
        val request = requireNotNull(preparer.describe(capture))
        return preparer.prepareDependencies(capture, preparer.confirm(request), request.inputDigest)
    }

    suspend fun close() {
        runtime.close()
        runtime.awaitClosed()
    }
}

internal class DependencyFakeProcess(
    private val exitCode: Int = 0,
    private val stdout: InputStream = byteArrayOf().inputStream(),
    private val stderr: InputStream = byteArrayOf().inputStream(),
    initiallyAlive: Boolean = false
) : Process() {
    private val alive = AtomicBoolean(initiallyAlive)
    val destroyed = CountDownLatch(1)
    val waiting = CountDownLatch(1)
    val inputClosed = AtomicBoolean()
    private val stdin = object : ByteArrayOutputStream() {
        override fun close() { inputClosed.set(true); super.close() }
    }
    override fun getOutputStream() = stdin
    override fun getInputStream() = stdout
    override fun getErrorStream() = stderr
    override fun isAlive() = alive.get()
    override fun waitFor(): Int {
        if (alive.get()) check(destroyed.await(10, TimeUnit.SECONDS))
        return exitCode
    }
    override fun waitFor(timeout: Long, unit: TimeUnit): Boolean {
        waiting.countDown()
        return !alive.get() || destroyed.await(timeout, unit)
    }
    override fun exitValue(): Int {
        if (alive.get()) throw IllegalThreadStateException("still alive")
        return exitCode
    }
    override fun destroy() { alive.set(false); destroyed.countDown() }
    override fun destroyForcibly(): Process { destroy(); return this }
}

internal class DependencyBlockingInput : InputStream() {
    val entered = CountDownLatch(1)
    val closed = CountDownLatch(1)
    override fun read(): Int {
        entered.countDown()
        check(closed.await(10, TimeUnit.SECONDS)) { "stream was not closed" }
        return -1
    }
    override fun read(bytes: ByteArray, offset: Int, length: Int): Int = read()
    override fun close() { closed.countDown() }
}

internal class DependencySizedInput(private var remaining: Long) : InputStream() {
    override fun read(): Int = if (remaining-- > 0) 0 else -1
    override fun read(bytes: ByteArray, offset: Int, length: Int): Int {
        if (remaining <= 0) return -1
        val count = minOf(remaining, length.toLong()).toInt()
        bytes.fill(0, offset, offset + count)
        remaining -= count
        return count
    }
}
