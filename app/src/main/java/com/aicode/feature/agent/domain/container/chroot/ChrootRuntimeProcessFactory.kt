package com.aicode.feature.agent.domain.container.chroot

import android.content.Context
import android.os.Process as AndroidProcess
import android.system.Os
import android.system.OsConstants
import com.aicode.feature.agent.domain.root.RootAccessState
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.withContext
import dagger.hilt.android.qualifiers.ApplicationContext
import java.io.File
import java.io.IOException
import java.security.MessageDigest
import javax.inject.Inject
import javax.inject.Singleton

internal data class ChrootHelperIdentity(
    val path: String,
    val device: Long,
    val inode: Long,
    val size: Long,
    val digest: String
)

internal class ChrootRuntimeUnavailable(message: String) : IOException(message)

internal interface ChrootProcessFactory {
    fun captureHelper(): ChrootHelperIdentity
    fun start(helper: ChrootHelperIdentity): Process
    fun startChecked(helper: ChrootHelperIdentity, canDispatch: () -> Boolean): Process {
        if (!canDispatch()) throw ChrootRuntimeUnavailable("Runtime request is no longer current")
        return start(helper)
    }
}

@Singleton
internal class ChrootRuntimeProcessFactory @Inject constructor(
    @ApplicationContext private val context: Context,
    private val broker: ChrootRootBroker
) : ChrootProcessFactory {
    val currentSession: StateFlow<ChrootRuntimeSession?> get() = broker.currentSession
    val bootstrapCount: Int get() = broker.bootstrapCount
    val registeredProcessCount: StateFlow<Int> get() = broker.registeredProcessCount

    suspend fun closeIdleFromUser(expected: ChrootRuntimeSession) = broker.closeIdleFromUser(expected)

    suspend fun ensureFromUser(expectedRoot: RootAccessState): ChrootRuntimeSession = ensureSession(expectedRoot)

    suspend fun ensureSession(expectedRoot: RootAccessState): ChrootRuntimeSession = withContext(Dispatchers.IO) {
        val helper = capture(BROKER_NAME, "broker")
        broker.ensureSession(expectedRoot) { arguments ->
            checkCaptured(helper, BROKER_NAME)
            startBroker(helper, arguments)
        }
    }

    fun checkSession(expected: ChrootRuntimeSession) = broker.checkSession(expected)

    fun registerSessionRevocation(onRevoke: () -> Unit): AutoCloseable = broker.registerSessionRevocation(onRevoke)

    override fun captureHelper(): ChrootHelperIdentity = capture(HELPER_NAME, "helper")

    private fun capture(name: String, manifest: String): ChrootHelperIdentity {
        if (AndroidProcess.myUid() < 10_000) throw ChrootRuntimeUnavailable("Runtime requires an application identity")
        val directory = File(context.applicationInfo.nativeLibraryDir).canonicalFile
        val helper = File(directory, name)
        if (!directory.path.startsWith("/data/app/") || helper.canonicalFile != helper) {
            throw ChrootRuntimeUnavailable("Runtime helper is not in the installed APK directory")
        }
        var ancestor: File? = helper
        while (ancestor != null && ancestor.path != "/") {
            val stat = Os.lstat(ancestor.path)
            if (OsConstants.S_ISLNK(stat.st_mode) || stat.st_uid !in listOf(0, 1000) ||
                stat.st_mode and OsConstants.S_IWOTH != 0 ||
                (stat.st_mode and OsConstants.S_IWGRP != 0 && stat.st_gid !in listOf(0, 1000))) {
                throw ChrootRuntimeUnavailable("Runtime helper ancestry is not protected")
            }
            ancestor = ancestor.parentFile
        }
        val before = Os.lstat(helper.path)
        if (!OsConstants.S_ISREG(before.st_mode) || !helper.canExecute() || helper.canWrite()) {
            throw ChrootRuntimeUnavailable("Runtime helper is unavailable or writable")
        }
        val expected = context.assets.open("chroot/$manifest.sha256").use { input ->
            val bytes = ByteArray(256)
            var size = 0
            while (size < bytes.size) {
                val count = input.read(bytes, size, bytes.size - size)
                if (count < 0) break
                size += count
            }
            if (size == bytes.size) throw ChrootRuntimeUnavailable("Runtime helper manifest is oversized")
            String(bytes, 0, size, Charsets.US_ASCII).trim()
        }
        if (!expected.matches(Regex("[0-9a-f]{64}"))) throw ChrootRuntimeUnavailable("Runtime helper manifest is invalid")
        val digest = MessageDigest.getInstance("SHA-256")
        helper.inputStream().use { input ->
            val buffer = ByteArray(65536)
            while (true) {
                val read = input.read(buffer)
                if (read < 0) break
                digest.update(buffer, 0, read)
            }
        }
        val actual = digest.digest().joinToString("") { "%02x".format(it.toInt() and 0xff) }
        val after = Os.lstat(helper.path)
        if (before.st_dev != after.st_dev || before.st_ino != after.st_ino || before.st_size != after.st_size ||
            before.st_mode != after.st_mode || actual != expected) {
            throw ChrootRuntimeUnavailable("Runtime helper identity changed")
        }
        return ChrootHelperIdentity(helper.path, after.st_dev, after.st_ino, after.st_size, actual)
    }

    private fun checkCaptured(helper: ChrootHelperIdentity, name: String) {
        val expectedPath = File(File(context.applicationInfo.nativeLibraryDir).canonicalFile, name)
        val current = Os.lstat(helper.path)
        if (expectedPath.path != helper.path || current.st_dev != helper.device || current.st_ino != helper.inode ||
            current.st_size != helper.size || !OsConstants.S_ISREG(current.st_mode) || current.st_uid !in listOf(0, 1000) ||
            current.st_mode and (OsConstants.S_IWOTH or OsConstants.S_IWGRP) != 0) {
            throw ChrootRuntimeUnavailable("Runtime helper capture expired")
        }
    }

    override fun start(helper: ChrootHelperIdentity): Process {
        return broker.start(ChrootBrokerWire.RUNTIME, checkHelper = { checkCaptured(helper, HELPER_NAME) })
    }

    override fun startChecked(helper: ChrootHelperIdentity, canDispatch: () -> Boolean): Process {
        return broker.startRuntimeChecked(checkHelper = { checkCaptured(helper, HELPER_NAME) }, canDispatch = canDispatch)
    }

    fun startInstaller(instanceId: String, removeIdentity: ChrootFileIdentity?, expectedSession: ChrootRuntimeSession): Process {
        require(instanceId.matches(Regex("[0-9a-f]{24}")))
        return broker.start(if (removeIdentity == null) ChrootBrokerWire.INSTALL else ChrootBrokerWire.REMOVE, instanceId, removeIdentity, expectedSession) {
            val helper = capture(INSTALLER_NAME, "installer")
            checkCaptured(helper, INSTALLER_NAME)
        }
    }

    fun startRecoveryRemoval(
        instanceId: String,
        identity: ChrootFileIdentity,
        expectedSession: ChrootRuntimeSession,
        checkCurrent: () -> Unit = {}
    ): Process {
        require(instanceId.matches(Regex("[0-9a-f]{24}")))
        return broker.startChecked(ChrootBrokerWire.RECOVER_REMOVE, instanceId, identity, expectedSession, checkHelper = {
            val helper = capture(INSTALLER_NAME, "installer")
            checkCaptured(helper, INSTALLER_NAME)
        }, checkCurrent = checkCurrent)
    }

    private fun startBroker(helper: ChrootHelperIdentity, arguments: List<String>): Process {
        val su = SU_PATHS.firstOrNull { File(it).canExecute() }
            ?: throw ChrootRuntimeUnavailable("Existing device root is unavailable")
        val command = "exec ${quote(helper.path)} " + arguments.joinToString(" ", transform = ::quote)
        return ProcessBuilder(su, "-c", command)
            .directory(File("/"))
            .redirectErrorStream(false)
            .apply {
                environment().clear()
                environment().putAll(mapOf("PATH" to "/system/bin:/system/xbin:/sbin", "LANG" to "C", "HOME" to "/"))
            }
            .start()
    }

    companion object {
        const val HELPER_NAME = "libajiu_chroot_runtime.so"
        const val INSTALLER_NAME = "libajiu_chroot_installer.so"
        const val BROKER_NAME = "libajiu_chroot_broker.so"
        private val SU_PATHS = listOf("/system/bin/su", "/system/xbin/su", "/sbin/su", "/debug_ramdisk/su")
        private fun quote(value: String) = "'" + value.replace("'", "'\"'\"'") + "'"
    }
}
