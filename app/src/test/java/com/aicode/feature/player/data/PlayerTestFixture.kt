package com.aicode.feature.player.data

import com.aicode.feature.workspace.domain.FileAccessProvider
import com.aicode.feature.workspace.domain.FileEntry
import com.aicode.feature.workspace.domain.identityCheckedRead
import com.aicode.feature.workspace.domain.readBytesBounded
import com.aicode.testutil.TestFileAccessProvider
import java.io.File
import java.io.InputStream
import java.lang.reflect.Proxy
import java.util.concurrent.CancellationException

internal class TestPlayerFileExecution(
    override val access: FileAccessProvider,
    private val current: () -> Unit = {}
) : PlayerFileExecution {
    override val identity = access.cacheIdentity()
    override fun checkCurrent() {
        current()
        if (access.cacheIdentity() != identity) throw CancellationException("Fixture source changed")
    }
    override fun <T> run(block: () -> T): T {
        checkCurrent()
        return block().also { checkCurrent() }
    }
}

internal fun playerFileAccess(
    identity: () -> String = { "fixture:1" },
    entries: (String) -> List<FileEntry> = { emptyList() },
    isDirectory: (String) -> Boolean = { true },
    stream: (String) -> InputStream = { error("Unexpected stream: $it") },
    copy: (String) -> File = { error("Unexpected local copy: $it") }
): FileAccessProvider = Proxy.newProxyInstance(
    FileAccessProvider::class.java.classLoader, arrayOf(FileAccessProvider::class.java)
) { proxy, method, args ->
    when (method.name) {
        "cacheIdentity" -> identity()
        "toDisplayPath" -> args!![0]
        "isDirectory" -> isDirectory(args!![0] as String)
        "listFiles" -> entries(args!![0] as String)
        "openRead" -> {
            if (args!!.size == 2 && args[1] != identity()) throw CancellationException("Fixture source changed")
            stream(args[0] as String)
        }
        "readBytes" -> {
            val path = args!![0] as String
            val captured = identity()
            val maxBytes = if (args.size == 2) args[1] as Long else null
            if (maxBytes != null) require(maxBytes in 0..Int.MAX_VALUE.toLong())
            identityCheckedRead(stream(path)) {
                if (identity() != captured) throw CancellationException("Fixture source changed")
            }.use { input -> if (maxBytes == null) input.readBytes() else input.readBytesBounded(maxBytes) }
        }
        "resolvePath" -> TestFileAccessProvider().resolvePath(args!![0] as String, args[1] as Boolean)
        "copyToLocal" -> copy(args!![0] as String)
        "toString" -> "PlayerFileAccessFixture"
        "hashCode" -> System.identityHashCode(proxy)
        "equals" -> proxy === args!![0]
        else -> error("Unexpected file access: ${method.name}")
    }
} as FileAccessProvider
