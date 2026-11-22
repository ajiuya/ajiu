package com.aicode.feature.agent.domain.shizuku

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import android.content.pm.ApplicationInfo
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.os.RemoteException
import com.aicode.R
import com.aicode.core.util.FileLogger
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import moe.shizuku.server.IShizukuService
import rikka.shizuku.Shizuku
import java.util.UUID
import javax.inject.Inject
import javax.inject.Singleton

/** Shizuku 可用状态，供设置页展示与工具执行前判定。 */
enum class ShizukuState {
    /** 未安装 Shizuku（或 Sui）。 */
    NOT_INSTALLED,

    /** 已安装但服务未运行，需用户先在 Shizuku 内启动服务。 */
    NOT_RUNNING,

    /** 服务运行中，但本应用尚未获得授权。 */
    PERMISSION_DENIED,

    SHELL_REQUIRED,

    IDENTITY_UNAVAILABLE,

    /** adb shell 身份已就绪。 */
    READY,

    READY_ROOT
}

data class ShizukuIdentity(val uid: Int, val connectionId: String)

/** 一次 Shizuku 命令执行结果。[exitCode] 为负值表示超时或启动异常。 */
data class ShizukuCommandResult(val output: String, val exitCode: Int)

/** 通过 UserService 执行命令；聊天许可绑定本次连接身份，插件入口仅使用 adb shell。 */
@Singleton
class ShizukuManager @Inject constructor(
    @param:ApplicationContext private val context: Context
) {
    private companion object {
        const val TAG = "ShizukuManager"

        /** Shizuku 官方应用包名（Sui 也用它作为入口）。 */
        const val SHIZUKU_PACKAGE = "moe.shizuku.privileged.api"

        /**
         * Shizuku 客户端库依赖的权限名（见 provider aar 的 `<uses-permission>`）。
         * 实现方（官方或第三方分叉）必须定义该权限，客户端才能取得授权，故可据此反查实现包，
         * 不受实现包名变化影响。
         */
        const val SHIZUKU_PERMISSION = "moe.shizuku.manager.permission.API_V23"

        const val PERMISSION_REQUEST_CODE = 1001

        /** 服务身份 tag：不设时用类名，类名经 R8 混淆后不稳定，故显式固定。 */
        const val SERVICE_TAG = "shizuku_command_v2"

        /** 绑定 UserService 的等待上限（毫秒）。Shizuku 启动服务自身超时为 30 秒。 */
        const val BIND_TIMEOUT_MS = 30_000L

        /** 命令超时上限（毫秒），与 [com.aicode.feature.agent.domain.container.CommandEngine.MAX_TIMEOUT_MS] 对齐。 */
        const val MAX_TIMEOUT_MS = 1_800_000L

        /** 命令结束后 UserService 的空闲保留时间；到期解绑以释放独立 ART 进程。 */
        const val USER_SERVICE_IDLE_RELEASE_MS = 15_000L

        const val DOWNLOAD_URL = "https://shizuku.rikka.app/download/"
    }

    private val _state = MutableStateFlow(ShizukuState.NOT_INSTALLED)
    val state: StateFlow<ShizukuState> = _state.asStateFlow()

    private val _identity = MutableStateFlow<ShizukuIdentity?>(null)
    val identity: StateFlow<ShizukuIdentity?> = _identity.asStateFlow()

    private data class CommandPermit(
        val sessionId: String,
        val command: String,
        val identity: ShizukuIdentity
    )

    private val gate = Any()
    private val bindMutex = Mutex()
    private val permits = mutableMapOf<String, CommandPermit>()
    private var shizukuBinder: IBinder? = null
    private var shellService: IShizukuShellService? = null
    private var shellServiceBinder: IBinder? = null
    private var shellServiceIdentity: ShizukuIdentity? = null
    private var shellServiceDeathRecipient: IBinder.DeathRecipient? = null
    private var pendingBind: CompletableDeferred<IShizukuShellService>? = null
    private var serviceConnection: ServiceConnection? = null
    private var activeExecCount = 0
    private val mainHandler = Handler(Looper.getMainLooper())
    private val releaseIdleService = Runnable {
        synchronized(gate) {
            if (activeExecCount == 0) releaseUserServiceLocked()
        }
    }

    private val binderReceivedListener = Shizuku.OnBinderReceivedListener { refreshState() }

    private val binderDeadListener = Shizuku.OnBinderDeadListener {
        synchronized(gate) {
            invalidateIdentityLocked()
            refreshStateLocked()
        }
    }

    private val permissionResultListener = Shizuku.OnRequestPermissionResultListener { _, _ ->
        refreshState()
    }

    private val userServiceArgs: Shizuku.UserServiceArgs by lazy {
        Shizuku.UserServiceArgs(
            ComponentName(context.packageName, ShizukuShellService::class.java.name)
        )
            .daemon(false)
            .tag(SERVICE_TAG)
            .processNameSuffix("shizuku_shell")
            .debuggable(isDebuggable)
            .version(appVersionCode)
    }

    /** 项目未开启 BuildConfig，版本号从 PackageManager 读取（升级后自动重建 UserService）。 */
    @Suppress("DEPRECATION")
    private val appVersionCode: Int by lazy {
        runCatching {
            val info = context.packageManager.getPackageInfo(context.packageName, 0)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                info.longVersionCode.toInt()
            } else {
                info.versionCode
            }
        }.getOrDefault(1)
    }

    private val isDebuggable: Boolean by lazy {
        (context.applicationInfo.flags and ApplicationInfo.FLAG_DEBUGGABLE) != 0
    }

    init {
        runCatching {
            // Sticky：binder 已就绪时立即回调，无需额外探测首帧状态。
            Shizuku.addBinderReceivedListenerSticky(binderReceivedListener)
            Shizuku.addBinderDeadListener(binderDeadListener)
            Shizuku.addRequestPermissionResultListener(permissionResultListener)
        }.onFailure { FileLogger.w(TAG, "注册 Shizuku 监听失败: ${it.message}") }
        refreshState()
    }

    fun refreshState() {
        accessSnapshot()
    }

    fun accessSnapshot(): ShizukuIdentity? = synchronized(gate) {
        refreshStateLocked()
    }

    private fun refreshStateLocked(): ShizukuIdentity? {
        if (!runCatching { Shizuku.pingBinder() }.getOrDefault(false)) {
            return unavailableLocked(
                if (isShizukuInstalled()) ShizukuState.NOT_RUNNING else ShizukuState.NOT_INSTALLED
            )
        }
        if (runCatching { Shizuku.isPreV11() }.getOrDefault(false)) {
            return unavailableLocked(ShizukuState.NOT_RUNNING)
        }
        val binder = runCatching { Shizuku.getBinder() }.getOrNull()
        val granted = runCatching {
            Shizuku.checkSelfPermission() == PackageManager.PERMISSION_GRANTED
        }.getOrDefault(false)
        if (!granted) return unavailableLocked(ShizukuState.PERMISSION_DENIED)
        if (binder == null) return unavailableLocked(ShizukuState.IDENTITY_UNAVAILABLE)
        val remote = runCatching { IShizukuService.Stub.asInterface(binder) }.getOrNull()
            ?: return unavailableLocked(ShizukuState.IDENTITY_UNAVAILABLE)
        // SDK 会缓存授权与 UID，执行边界必须重新向当前服务端核实。
        if (!runCatching { remote.checkSelfPermission() }.getOrDefault(false)) {
            return unavailableLocked(ShizukuState.PERMISSION_DENIED)
        }
        val uid = runCatching { remote.getUid() }.getOrNull()
        if (uid == null || uid < 0 ||
            runCatching { Shizuku.getBinder() }.getOrNull() !== binder
        ) {
            return unavailableLocked(ShizukuState.IDENTITY_UNAVAILABLE)
        }
        if (uid != 0 && uid != 2000) return unavailableLocked(ShizukuState.SHELL_REQUIRED)

        val current = _identity.value
        val cacheInvalid = shellService != null && (
            shellServiceIdentity != current || shellServiceBinder == null ||
                !runCatching {
                    shellService?.asBinder() === shellServiceBinder && shellServiceBinder?.pingBinder() == true
                }.getOrDefault(false)
            )
        if (current?.uid != uid || shizukuBinder !== binder || cacheInvalid) {
            invalidateIdentityLocked()
            shizukuBinder = binder
            _identity.value = ShizukuIdentity(uid, UUID.randomUUID().toString())
        }
        _state.value = if (uid == 0) ShizukuState.READY_ROOT else ShizukuState.READY
        return _identity.value
    }

    private fun unavailableLocked(state: ShizukuState): ShizukuIdentity? {
        invalidateIdentityLocked()
        _state.value = state
        return null
    }

    private fun invalidateIdentityLocked() {
        mainHandler.removeCallbacks(releaseIdleService)
        shellServiceDeathRecipient?.let { recipient ->
            runCatching { shellServiceBinder?.unlinkToDeath(recipient, 0) }
        }
        val connection = serviceConnection
        shellServiceDeathRecipient = null
        shellService = null
        shellServiceBinder = null
        shellServiceIdentity = null
        serviceConnection = null
        pendingBind?.completeExceptionally(identityChanged())
        pendingBind = null
        connection?.let(::unbindUserService)
        activeExecCount = 0
        permits.clear()
        shizukuBinder = null
        _identity.value = null
    }

    /** UserService 是独立进程；只清空本地 binder 引用不会结束它，会把整套 ART/堆内存留在应用 UID 下。 */
    private fun unbindUserService(connection: ServiceConnection) {
        val release = {
            runCatching { Shizuku.unbindUserService(userServiceArgs, connection, true) }
                .onFailure { FileLogger.w(TAG, "解绑 Shizuku UserService 失败: ${it.message}") }
            Unit
        }
        if (Looper.myLooper() == Looper.getMainLooper()) release()
        else Handler(Looper.getMainLooper()).post(release)
    }

    /**
     * 是否已安装任一 Shizuku 实现。先查官方包名（快路径），未命中再按权限定义反查，
     * 以覆盖改了包名的第三方分叉。
     */
    private fun isShizukuInstalled(): Boolean = findShizukuPackage() != null

    @Suppress("DEPRECATION")
    private fun findShizukuPackage(): String? {
        runCatching { context.packageManager.getPackageInfo(SHIZUKU_PACKAGE, 0) }
            .onSuccess { return SHIZUKU_PACKAGE }
        return runCatching {
            context.packageManager.getInstalledPackages(PackageManager.GET_PERMISSIONS)
                .firstOrNull { pkg ->
                    pkg.permissions?.any { it.name == SHIZUKU_PERMISSION } == true
                }
                ?.packageName
        }.getOrNull()
    }

    /** 申请 Shizuku 授权。需在主线程调用（Shizuku 内部要求）。 */
    fun requestPermission() {
        refreshState()
        if (_state.value != ShizukuState.PERMISSION_DENIED) return
        runCatching { Shizuku.requestPermission(PERMISSION_REQUEST_CODE) }
            .onFailure { FileLogger.w(TAG, "申请 Shizuku 授权失败: ${it.message}") }
    }

    /** 打开 Shizuku 应用（含第三方分叉）；未安装则跳转官方下载页。 */
    fun openShizukuApp() {
        val launch = findShizukuPackage()?.let {
            context.packageManager.getLaunchIntentForPackage(it)
        }
        runCatching {
            if (launch != null) {
                launch.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                context.startActivity(launch)
            } else {
                context.startActivity(
                    Intent(Intent.ACTION_VIEW, Uri.parse(DOWNLOAD_URL))
                        .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                )
            }
        }.onFailure { FileLogger.w(TAG, "打开 Shizuku 失败: ${it.message}") }
    }

    fun authorize(sessionId: String, command: String, expectedIdentity: ShizukuIdentity): String? {
        if (sessionId.isBlank() || command.isBlank()) return null
        return synchronized(gate) {
            if (refreshStateLocked() != expectedIdentity) return@synchronized null
            val permitId = UUID.randomUUID().toString()
            permits[permitId] = CommandPermit(sessionId, command, expectedIdentity)
            permitId
        }
    }

    fun revokePermit(permitId: String) {
        synchronized(gate) {
            permits.remove(permitId)
        }
    }

    suspend fun runApprovedCommand(
        command: String,
        timeoutMs: Long,
        sessionId: String,
        permitId: String?
    ): ShizukuCommandResult {
        val expectedIdentity = synchronized(gate) {
            val permit = permitId?.let { permits.remove(it) } ?: throw approvalRequired()
            if (sessionId.isBlank() || command.isBlank() ||
                permit.sessionId != sessionId || permit.command != command
            ) {
                throw approvalRequired()
            }
            requireIdentityLocked(permit.identity)
            permit.identity
        }
        return executeCommand(command, timeoutMs, expectedIdentity)
    }

    /** 插件入口只开放 adb shell，不继承聊天会话的 Root 授权。 */
    suspend fun runCommand(command: String, timeoutMs: Long): ShizukuCommandResult {
        val expectedIdentity = synchronized(gate) {
            val current = refreshStateLocked() ?: throw unavailableError()
            if (current.uid != 2000) throw approvalRequired()
            current
        }
        return executeCommand(command, timeoutMs, expectedIdentity)
    }

    private suspend fun executeCommand(
        command: String,
        timeoutMs: Long,
        expectedIdentity: ShizukuIdentity
    ): ShizukuCommandResult {
        val service = ensureBound(expectedIdentity)
        val timeout = timeoutMs.coerceIn(1_000L, MAX_TIMEOUT_MS).toInt()
        synchronized(gate) {
            requireIdentityLocked(expectedIdentity)
            if (shellService !== service || shellServiceIdentity != expectedIdentity) throw identityChanged()
            activeExecCount++
            mainHandler.removeCallbacks(releaseIdleService)
        }
        return try {
            withContext(Dispatchers.IO) {
                val bundle = try {
                    service.exec(command, timeout, expectedIdentity.uid)
                } catch (e: RemoteException) {
                    invalidateService(service)
                    throw identityChanged()
                }
                val exitCode = bundle.getInt(ShizukuShellService.KEY_EXIT_CODE, ShizukuShellService.EXIT_FAILURE)
                if (exitCode == ShizukuShellService.EXIT_SHELL_REQUIRED) {
                    invalidateService(service)
                    throw identityChanged()
                }
                ShizukuCommandResult(
                    output = bundle.getString(ShizukuShellService.KEY_OUTPUT).orEmpty(),
                    exitCode = exitCode
                )
            }
        } finally {
            synchronized(gate) {
                activeExecCount = (activeExecCount - 1).coerceAtLeast(0)
                if (activeExecCount == 0 && shellService === service) {
                    mainHandler.postDelayed(releaseIdleService, USER_SERVICE_IDLE_RELEASE_MS)
                }
            }
        }
    }

    private fun releaseUserServiceLocked() {
        val connection = serviceConnection ?: return
        mainHandler.removeCallbacks(releaseIdleService)
        shellServiceDeathRecipient?.let { recipient ->
            runCatching { shellServiceBinder?.unlinkToDeath(recipient, 0) }
        }
        shellServiceDeathRecipient = null
        shellService = null
        shellServiceBinder = null
        shellServiceIdentity = null
        serviceConnection = null
        unbindUserService(connection)
    }

    private fun invalidateService(service: IShizukuShellService) {
        synchronized(gate) {
            if (shellService === service) {
                invalidateIdentityLocked()
                refreshStateLocked()
            }
        }
    }

    private fun requireIdentityLocked(expectedIdentity: ShizukuIdentity) {
        val current = refreshStateLocked() ?: throw unavailableError()
        if (current != expectedIdentity) throw identityChanged()
    }

    private fun identityChanged() = IllegalStateException(context.getString(R.string.shizuku_identity_changed))

    private fun approvalRequired() = IllegalStateException(context.getString(R.string.shizuku_approval_required))

    private fun unavailableError(): IllegalStateException {
        val message = when (_state.value) {
            ShizukuState.READY, ShizukuState.READY_ROOT -> R.string.shizuku_identity_changed
            ShizukuState.NOT_INSTALLED -> R.string.settings_shizuku_hint_not_installed
            ShizukuState.NOT_RUNNING -> R.string.settings_shizuku_hint_not_running
            ShizukuState.PERMISSION_DENIED -> R.string.settings_shizuku_hint_denied
            ShizukuState.SHELL_REQUIRED -> R.string.shizuku_shell_required
            ShizukuState.IDENTITY_UNAVAILABLE -> R.string.shizuku_identity_unavailable
        }
        return IllegalStateException(context.getString(message))
    }

    private fun createServiceConnection(expectedIdentity: ShizukuIdentity): ServiceConnection =
        object : ServiceConnection {
            override fun onServiceConnected(name: ComponentName?, service: IBinder?) {
                synchronized(gate) {
                    if (serviceConnection !== this) return
                    if (refreshStateLocked() != expectedIdentity || serviceConnection !== this) return
                    if (service == null || !runCatching { service.pingBinder() }.getOrDefault(false) ||
                        (shellServiceBinder != null && shellServiceBinder !== service)
                    ) {
                        invalidateIdentityLocked()
                        refreshStateLocked()
                        return
                    }
                    if (shellServiceBinder === service) return
                    val bound = IShizukuShellService.Stub.asInterface(service)
                    if (bound == null) {
                        invalidateIdentityLocked()
                        refreshStateLocked()
                        return
                    }
                    val recipient = IBinder.DeathRecipient {
                        synchronized(gate) {
                            if (shellServiceBinder === service) {
                                invalidateIdentityLocked()
                                refreshStateLocked()
                            }
                        }
                    }
                    shellService = bound
                    shellServiceBinder = service
                    shellServiceIdentity = expectedIdentity
                    shellServiceDeathRecipient = recipient
                    try {
                        service.linkToDeath(recipient, 0)
                    } catch (e: RemoteException) {
                        invalidateIdentityLocked()
                        refreshStateLocked()
                        return
                    }
                    pendingBind?.complete(bound)
                    pendingBind = null
                }
            }

            override fun onServiceDisconnected(name: ComponentName?) {
                synchronized(gate) {
                    if (serviceConnection !== this) return
                    invalidateIdentityLocked()
                    refreshStateLocked()
                }
            }

            override fun onBindingDied(name: ComponentName?) {
                onServiceDisconnected(name)
            }

            override fun onNullBinding(name: ComponentName?) {
                onServiceDisconnected(name)
            }
        }

    private suspend fun ensureBound(expectedIdentity: ShizukuIdentity): IShizukuShellService {
        synchronized(gate) {
            requireIdentityLocked(expectedIdentity)
            shellService?.let { return it }
        }
        return bindMutex.withLock {
            val deferred = CompletableDeferred<IShizukuShellService>()
            val connection = synchronized(gate) {
                requireIdentityLocked(expectedIdentity)
                shellService?.let { return@withLock it }
                createServiceConnection(expectedIdentity).also {
                    serviceConnection = it
                    pendingBind = deferred
                }
            }
            try {
                withContext(Dispatchers.Main) {
                    synchronized(gate) {
                        requireIdentityLocked(expectedIdentity)
                        if (serviceConnection !== connection) throw identityChanged()
                        Shizuku.bindUserService(userServiceArgs, connection)
                    }
                }
                val bound = withTimeoutOrNull(BIND_TIMEOUT_MS) { deferred.await() }
                    ?: throw IllegalStateException(context.getString(R.string.settings_shizuku_hint_not_running))
                synchronized(gate) {
                    requireIdentityLocked(expectedIdentity)
                    if (shellService !== bound || shellServiceIdentity != expectedIdentity) throw identityChanged()
                }
                bound
            } finally {
                synchronized(gate) {
                    if (pendingBind === deferred) {
                        invalidateIdentityLocked()
                        refreshStateLocked()
                    }
                }
            }
        }
    }
}
