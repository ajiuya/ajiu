package com.aicode.feature.agent.domain.root

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.emptyPreferences
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.InputStream
import java.io.OutputStream
import java.util.concurrent.TimeUnit

internal class RootMemoryDataStore(
    initial: Preferences = emptyPreferences()
) : DataStore<Preferences> {
    override val data = MutableStateFlow(initial)
    private val mutex = Mutex()
    var failWrites = false

    override suspend fun updateData(transform: suspend (Preferences) -> Preferences): Preferences =
        mutex.withLock {
            if (failWrites) throw java.io.IOException("storage unavailable")
            transform(data.value).also { data.value = it }
        }
}

internal class FakeRootProcess(
    output: String = "done",
    private var alive: Boolean = false,
    private val code: Int = 0
) : Process() {
    var destroyed = false
        private set
    var inputClosed = false
        private set
    var errorClosed = false
        private set
    var outputClosed = false
        private set
    var blockingReadAttempted = false
        private set
    var failAvailable = false

    private val input = object : ByteArrayInputStream(output.toByteArray(Charsets.UTF_8)) {
        override fun available(): Int {
            if (failAvailable) throw java.io.IOException("read failed")
            return super.available()
        }

        override fun read(bytes: ByteArray, offset: Int, count: Int): Int {
            if (available() == 0) {
                blockingReadAttempted = true
                throw AssertionError("Reading an empty live pipe would block")
            }
            return super.read(bytes, offset, count)
        }

        override fun close() {
            inputClosed = true
            super.close()
        }
    }
    private val error = object : ByteArrayInputStream(byteArrayOf()) {
        override fun close() {
            errorClosed = true
            super.close()
        }
    }
    private val stdin = object : ByteArrayOutputStream() {
        override fun close() {
            outputClosed = true
            super.close()
        }
    }

    override fun getInputStream(): InputStream = input
    override fun getErrorStream(): InputStream = error
    override fun getOutputStream(): OutputStream = stdin
    override fun isAlive(): Boolean = alive
    override fun waitFor(): Int = throw AssertionError("Blocking waitFor is forbidden")
    override fun waitFor(timeout: Long, unit: TimeUnit): Boolean = throw AssertionError("Blocking waitFor is forbidden")
    override fun exitValue(): Int {
        if (alive) throw IllegalThreadStateException()
        return code
    }
    override fun destroy() {
        destroyed = true
        alive = false
    }
    override fun destroyForcibly(): Process {
        destroy()
        return this
    }
}

internal class FakeRootProcessFactory : RootProcessFactory {
    val commands = mutableListOf<String>()
    val destroyed = mutableListOf<Process>()
    var next = FakeRootProcess()
    var failure: Exception? = null

    override fun create(command: String): Process {
        commands.add(command)
        failure?.let { throw it }
        return next
    }

    override fun destroy(process: Process) {
        destroyed.add(process)
        process.destroyForcibly()
        process.inputStream.close()
        process.errorStream.close()
        process.outputStream.close()
    }
}
