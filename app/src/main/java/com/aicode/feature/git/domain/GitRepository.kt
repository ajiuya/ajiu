package com.aicode.feature.git.domain

import com.aicode.core.util.FileLogger
import com.aicode.feature.agent.domain.container.CommandEngine
import com.aicode.feature.agent.domain.container.CommandResult
import com.aicode.feature.agent.domain.container.runtime.LocalRuntimeAuthorizationRequired
import com.aicode.feature.agent.domain.container.runtime.RuntimeOwner
import com.aicode.feature.agent.domain.container.runtime.RuntimeOwnerKind
import com.aicode.feature.settings.data.repository.ContainerSettingsRepository
import com.aicode.feature.workspace.domain.WorkspaceExecutionContext
import com.aicode.feature.workspace.domain.WorkspaceExecutionContextFactory
import com.aicode.feature.plugin.data.PluginFiles
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import com.aicode.feature.git.domain.model.GitBranch
import com.aicode.feature.git.domain.model.GitCommit
import com.aicode.feature.git.domain.model.GitFileChange
import com.aicode.feature.git.domain.model.GitGraph
import com.aicode.feature.git.domain.model.GitGraphRef
import com.aicode.feature.git.domain.model.GraphCommit
import com.aicode.feature.git.domain.model.GitStash
import com.aicode.feature.git.domain.model.GitStatus
import com.aicode.feature.git.domain.model.GitTag
import com.aicode.feature.settings.data.repository.ExecutionMode
import com.aicode.feature.settings.data.repository.ExecutionModeHolder
import com.aicode.feature.workspace.data.repository.WorkspaceRepository
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import javax.inject.Inject
import javax.inject.Singleton

private const val TAG = "GitRepository"

class GitOperationExpiredException : IllegalStateException("Git operation source changed; refresh the selected repository and retry")
class GitWorkspaceRequiredException : IllegalStateException("Select an available workspace before running Git")
class GitUnavailableException(output: String) : GitCommandFailureException(output)

class FrozenGitOperation internal constructor(
    val workspacePath: String,
    val repoRoot: String,
    val mode: ExecutionMode,
    val configurationRevision: Long,
    val owner: RuntimeOwner,
    private val factory: WorkspaceExecutionContextFactory,
    private val sourceCheck: () -> Unit
) {
    private val mutex = Mutex()
    @Volatile private var captured: WorkspaceExecutionContext? = null
    val runtimeProfile get() = captured?.profileSnapshot?.activeProfile ?: captured?.localMapping?.profile

    fun checkCurrent() {
        try {
            sourceCheck()
            captured?.checkCurrent()
        } catch (_: CancellationException) {
            throw GitOperationExpiredException()
        }
    }

    internal suspend fun execution(): WorkspaceExecutionContext = mutex.withLock {
        checkCurrent()
        captured ?: factory.freeze(runtimeOwner = owner, checkSource = sourceCheck).also {
            if (it.mode != mode || it.runtimeOwner != owner) throw GitOperationExpiredException()
            checkCurrent()
            captured = it
        }
    }
}

private inline fun <T> gitAttempt(block: () -> T): Result<T> = try {
    Result.success(block())
} catch (failure: Exception) {
    if (failure is CancellationException || failure is GitOperationExpiredException || failure is LocalRuntimeAuthorizationRequired) throw failure
    Result.failure(failure)
}

/**
 * 在容器内执行 git 命令并解析输出。
 *
 * 直接复用 [LinuxContainerEngine.runCommandSync]（cwd = 当前工作区，绑定挂载到 ~/workspace），
 * 不经 agent 工具链 / 权限引擎——Git 页是用户主动操作。所有输出解析为纯领域模型。
 *
 * 命令经 [shellQuote] 逐参数转义后拼成单条 `git ...` 字符串交给 `/bin/sh -c`，故格式串里的
 * `|`、`%(...)`、含空格的路径都能安全传递。
 *
 * 所有命令带 `-c core.quotepath=false`：git 默认把路径里的非 ASCII 字节转成八进制转义
 * （`中文` → `\344\270\255\346\226\207`），关掉后直接输出 UTF-8 原文，中文目录/文件名不再乱码。
 */
@Singleton
class GitRepository @Inject constructor(
    private val engine: CommandEngine,
    private val workspaceRepository: WorkspaceRepository,
    private val executionModeHolder: ExecutionModeHolder,
    private val executionFactory: WorkspaceExecutionContextFactory,
    private val containerSettings: ContainerSettingsRepository
) {
    /** 当前选中的 git 仓库路径；null 表示未指定（用工作区根）。 */
    @Volatile
    var currentRepoPath: String? = null
        private set

    private companion object {
        /** 提交拓扑图每页加载条数。首批与每次「加载更多」都取这么多条，超过的需滚到底再拉。 */
        const val GRAPH_PAGE_SIZE = 100

        /** diff 允许读取的单侧文件字节上限：超过则不拉内容，直接降级为「文件过大」。 */
        const val MAX_DIFF_FILE_BYTES = 2L * 1024 * 1024

        val GIT_NOT_FOUND = Regex(
            """^(?:[^:\r\n]+:\s*)*git(?::|\s)\s*(?:command\s+)?not found\.?\s*$""",
            setOf(RegexOption.IGNORE_CASE, RegexOption.MULTILINE)
        )

        val INSTALL_CHROOT_GIT = """
            set -e;
            . /root/.aicode/lib/env-common.sh;
            detect_pmgr;
            case "${'$'}PMGR" in
                apk|apt) pkg_add git ca-certificates openssh-client ;;
                dnf|yum) pkg_add git ca-certificates openssh-clients ;;
                pacman) pkg_add git ca-certificates openssh ;;
                *) pkg_add git ca-certificates ;;
            esac
        """.trimIndent().replace('\n', ' ')
    }
    /**
     * 执行一条 `git` 子命令，返回合并后的 stdout+stderr 文本。仅用于只读命令（status/branches/log/remote 等）：
     * 这些靠输出解析、容错，git 非零退出码不会让 UI 误判（解析得空罢了）。凭据由 `credential.helper=store`
     * 经凭据文件自动注入（见 [com.aicode.feature.credentials.data.repository.FileCredentialRepository]），
     * 不在此按命令塞 `http.extraHeader`。
     * 每条参数经 [shellQuote] 单引号转义，含空格/特殊字符的值（提交消息、含空格路径等）安全传递。
     */
    private suspend fun git(
        operation: FrozenGitOperation,
        vararg args: String
    ): String = gitRaw(operation, args)

    /** 当前 git 命令的执行目录：选中了子目录仓库用其路径，否则工作区根。 */
    @Volatile private var repoSelectionRevision = 0L
    private var repoWorkspaceSelection: WorkspaceRepository.Selection? = null
    private var ownerWorkspaceSelection: WorkspaceRepository.Selection? = null
    private var ownerWorkspaceEpoch = 0L

    @Synchronized
    fun captureOperation(): FrozenGitOperation {
        val selection = workspaceRepository.selection.value
        val workspace = selection.workspace?.takeIf { it.available }?.path
            ?: throw GitWorkspaceRequiredException()
        if (ownerWorkspaceSelection !== selection) {
            ownerWorkspaceSelection = selection
            ownerWorkspaceEpoch++
        }
        val root = currentRepoPath?.takeIf { repoWorkspaceSelection === selection } ?: workspace
        val revision = repoSelectionRevision
        val mode = executionModeHolder.currentMode()
        val modeRevision = executionModeHolder.modeSelectionRevision
        val profileRevision = containerSettings.activeProfileSelectionRevision
        val configRevision = containerSettings.profileConfigurationRevision
        val fingerprint = listOf(workspace, root, mode.name, modeRevision, profileRevision, configRevision,
            ownerWorkspaceEpoch, revision).joinToString("\u0000")
        val owner = RuntimeOwner(RuntimeOwnerKind.GIT, PluginFiles.sha256(fingerprint.toByteArray(Charsets.UTF_8)))
        return FrozenGitOperation(workspace, root, mode, configRevision, owner, executionFactory) {
            if (workspaceRepository.selection.value !== selection || repoSelectionRevision != revision ||
                executionModeHolder.currentMode() != mode || executionModeHolder.modeSelectionRevision != modeRevision ||
                containerSettings.activeProfileSelectionRevision != profileRevision ||
                containerSettings.profileConfigurationRevision != configRevision) throw GitOperationExpiredException()
        }.also { it.checkCurrent() }
    }

    @Synchronized
    fun setRepoPath(path: String?, operation: FrozenGitOperation? = null) {
        operation?.checkCurrent()
        val selection = workspaceRepository.selection.value
        val oldRoot = currentRepoPath?.takeIf { repoWorkspaceSelection === selection } ?: selection.workspace?.path
        val newRoot = path ?: selection.workspace?.path
        if (oldRoot != newRoot) repoSelectionRevision++
        currentRepoPath = path
        repoWorkspaceSelection = selection
    }

    private suspend fun execute(
        operation: FrozenGitOperation,
        command: String,
        cwd: String = operation.repoRoot,
        timeoutMs: Long = CommandEngine.DEFAULT_TIMEOUT_MS
    ): CommandResult = execute(operation, { command }, cwd, timeoutMs)

    private suspend fun execute(
        operation: FrozenGitOperation,
        command: (WorkspaceExecutionContext) -> String,
        cwd: String,
        timeoutMs: Long
    ): CommandResult {
        operation.checkCurrent()
        val execution = operation.execution()
        return withContext(execution) {
            operation.checkCurrent()
            engine.runCommandSyncUnbounded(command(execution), cwd, timeoutMs).also { operation.checkCurrent() }
        }
    }

    private suspend fun executeGit(
        operation: FrozenGitOperation,
        args: Array<out String>,
        cwd: String = operation.repoRoot,
        timeoutMs: Long = CommandEngine.DEFAULT_TIMEOUT_MS,
        probe: Boolean = false,
        legacyProbe: Boolean = false
    ): CommandResult = execute(operation, { buildGitCommand(it, args, probe, legacyProbe) }, cwd, timeoutMs)

    /**
     * 发现工作区里的 git 仓库：工作区根 + 一层直接子目录。
     * 本地模式用 File 列子目录；远程模式只检查工作区根，避免扫到手机上的同名目录。
     * 返回按「根优先、子目录按名称排序」的路径列表。
     */
    suspend fun discoverRepos(operation: FrozenGitOperation = captureOperation()): List<String> {
        operation.checkCurrent()
        val root = operation.workspacePath
        val candidates = mutableListOf(root)
        if (operation.mode != ExecutionMode.REMOTE_SSH) {
            gitAttempt {
                java.io.File(root).listFiles { f -> f.isDirectory }
                    ?.sortedBy { it.name }
                    ?.forEach { candidates.add(it.absolutePath) }
            }
        }
        return candidates.filter { isGitRepo(it, operation) }.also { operation.checkCurrent() }
    }

    /** 指定路径是否处于 git 工作树内（用于仓库发现与手动指定校验）。 */
    suspend fun isGitRepo(path: String, operation: FrozenGitOperation = captureOperation()): Boolean =
        probeRepository(operation, path, legacyProbe = true)

    /**
     * 列出工作区根的一层直接子目录（供仓库选择弹窗展示扫描范围）。
     * 本地模式用 File 列；远程模式返回空，避免扫到手机上的同名目录。
     */
    fun listSubdirectories(operation: FrozenGitOperation = captureOperation()): List<String> {
        operation.checkCurrent()
        if (operation.mode == ExecutionMode.REMOTE_SSH) return emptyList()
        operation.checkCurrent()
        val root = operation.workspacePath
        return gitAttempt {
            java.io.File(root).listFiles { f -> f.isDirectory }
                ?.sortedBy { it.name }
                ?.map { it.absolutePath }
                .orEmpty()
        }.getOrDefault(emptyList())
    }

    /** 当前工作区根路径（git 命令的默认执行目录）。 */
    fun workspaceRoot(operation: FrozenGitOperation = captureOperation()): String = operation.workspacePath.also { operation.checkCurrent() }

    /**
     * 执行一条 `git` **写**子命令，据退出码判成败：非零（真实失败）抛 [GitCommandFailureException]
     * 携带 git 输出文本，上层 [com.aicode.feature.git.presentation.GitViewModel.runAction] 据此如实显示
     * 「失败 + 原因」而非误报成功。代表场景：未配置署名提交、未授权推送、合并冲突。空退出码（超时/异常）
     * 同样按失败抛，避免静默成功。
     */
    private suspend fun gitChecked(
        operation: FrozenGitOperation,
        vararg args: String
    ): String = gitCheckedWithTimeout(operation, CommandEngine.DEFAULT_TIMEOUT_MS, *args)

    private suspend fun gitCheckedWithTimeout(
        operation: FrozenGitOperation,
        timeoutMs: Long,
        vararg args: String
    ): String {
        // 用不限幅执行：diff 内容/文件内容可能远超 AI 工具链路的 4 万字符限幅，
        // 截断占位符会混入 diff 数据流被 UI 渲染成伪 diff 行。
        val result = executeGit(operation, args, timeoutMs = timeoutMs)
        if (result.outputTruncated) throw GitOutputTooLargeException()
        if (result.exitCode == 0) return result.output
        throw GitCommandFailureException(result.output.ifBlank { "git 退出码 ${result.exitCode}" })
    }

    /** 拼命令并跑（不判退出码），[git] 与 [gitChecked] 复用。 */
    private suspend fun gitRaw(operation: FrozenGitOperation, args: Array<out String>): String {
        val result = executeGit(operation, args)
        if (result.outputTruncated) throw GitOutputTooLargeException()
        return result.output
    }

    /** 拼成交给 `/bin/sh -c` 的单条命令字符串，逐参数 [shellQuote] 转义。 */
    private fun buildGitCommand(
        execution: WorkspaceExecutionContext,
        args: Array<out String>,
        probe: Boolean,
        legacyProbe: Boolean
    ): String {
        val profile = execution.profileSnapshot?.activeProfile ?: execution.localMapping?.profile
        val chroot = execution.mode == ExecutionMode.LOCAL_PROOT && profile?.isChroot == true
        val command = buildString {
            if (chroot) append("git_worktree=$(pwd -P) && test -n \"\$git_worktree\" && ")
            if (probe) append("LC_ALL=C ")
            append("git")
            if (chroot) append(" -c \"safe.directory=\$git_worktree\"")
            if (chroot || !legacyProbe) append(" -c core.quotepath=false")
            args.forEach { append(' '); append(shellQuote(it)) }
        }
        if (!chroot && !probe) return command
        val quotedScript = if (execution.mode == ExecutionMode.LOCAL_PROOT && profile?.shellPath?.substringAfterLast('/') == "fish") {
            "'" + command.replace("\\", "\\\\").replace("'", "\\'") + "'"
        } else {
            shellQuote(command)
        }
        return "/bin/sh -c $quotedScript"
    }

    /** 当前工作区是否处于一个 git 工作树内，探测失败时保留原始错误。 */
    suspend fun isRepo(operation: FrozenGitOperation = captureOperation()): Boolean =
        probeRepository(operation, operation.repoRoot)

    private suspend fun probeRepository(
        operation: FrozenGitOperation,
        cwd: String,
        legacyProbe: Boolean = false
    ): Boolean {
        val result = executeGit(operation, arrayOf("rev-parse", "--is-inside-work-tree"), cwd, probe = true, legacyProbe = legacyProbe)
        if (result.outputTruncated) throw GitOutputTooLargeException()
        val output = result.output.trim()
        if (result.exitCode == 0) {
            when (output) {
                "true" -> return true
                "false" -> return false
            }
        }
        if (result.exitCode == 128 &&
            (output.startsWith("fatal: not a git repository", ignoreCase = true) ||
                output.startsWith("fatal: not a git worktree", ignoreCase = true))
        ) return false
        if (result.exitCode == 127 && GIT_NOT_FOUND.containsMatchIn(output)) {
            throw GitUnavailableException(result.output)
        }
        throw GitCommandFailureException(result.output.ifBlank { "git 退出码 ${result.exitCode}" })
    }

    /** 容器是否就绪可执行 git 命令；未就绪时返回引导文案（供 Git 页在刷新前提示用户去终端页初始化）。 */
    suspend fun notReadyHint(operation: FrozenGitOperation = captureOperation()): String? =
        withContext(operation.execution()) {
            operation.checkCurrent()
            engine.notReadyHint().also { operation.checkCurrent() }
        }

    private suspend fun ensureChrootGit(operation: FrozenGitOperation) {
        if (operation.mode != ExecutionMode.LOCAL_PROOT) return
        operation.execution()
        if (operation.runtimeProfile?.isChroot != true) return
        val available = execute(operation, "/bin/sh -c ${shellQuote("command -v git")}")
        if (available.outputTruncated) throw GitOutputTooLargeException()
        when (available.exitCode) {
            0 -> Unit
            1, 127 -> requireGitSetupSuccess(execute(operation, "/bin/sh -c ${shellQuote(INSTALL_CHROOT_GIT)}", timeoutMs = 600_000L))
            else -> requireGitSetupSuccess(available)
        }
        requireGitSetupSuccess(execute(operation, "/bin/sh -c ${shellQuote("git --version")}"))
    }

    private fun requireGitSetupSuccess(result: CommandResult) {
        if (result.outputTruncated) throw GitOutputTooLargeException()
        if (result.exitCode != 0) {
            throw GitCommandFailureException(result.output.ifBlank { "Git setup exited with code ${result.exitCode}" })
        }
    }

    /**
     * 在当前工作区初始化 git 仓库。
     *
     * 默认分支优先使用现代标准的 `main`（Git 2.28+ 支持 `git init -b main`），
     * 若环境 Git 版本较低不支持 `-b` 参数，降级为先 `git init` 再将未初始化的 HEAD 指向 `refs/heads/main`。
     */
    suspend fun initRepo(operation: FrozenGitOperation = captureOperation()): String {
        ensureChrootGit(operation)
        return gitAttempt { gitChecked(operation, "init", "-b", "main") }
            .getOrElse {
                val out = gitChecked(operation, "init")
                gitAttempt { git(operation, "symbolic-ref", "HEAD", "refs/heads/main") }
                out
            }
    }

    /** 克隆远程仓库到当前工作区根目录（`git clone <url> .`）。据退出码判成败，失败抛 [GitCommandFailureException]。 */
    suspend fun cloneRepo(url: String, operation: FrozenGitOperation = captureOperation()): String {
        ensureChrootGit(operation)
        return gitCheckedWithTimeout(operation, 600_000L, "clone", url.trim(), ".")
    }

    /** 是否已配置至少一个远程仓库（`git remote` 输出非空）。拉取/推送前据此门控。 */
    suspend fun hasRemote(operation: FrozenGitOperation = captureOperation()): Boolean = git(operation, "remote").trim().isNotEmpty()

    /** `git status` 聚合视图。 */
    suspend fun status(operation: FrozenGitOperation = captureOperation()): GitStatus {
        val raw = git(operation, "status", "--porcelain=v1", "-b")
        val lines = raw.split('\n').map { it.removeSuffix("\r") }

        var branch = "(unknown)"
        var upstream: String? = null
        var isDetached = false
        var ahead = 0
        var behind = 0
        val staged = mutableListOf<GitFileChange>()
        val unstaged = mutableListOf<GitFileChange>()
        val untracked = mutableListOf<String>()
        val conflicted = mutableListOf<GitFileChange>()

        for (line in lines) {
            if (line.isBlank()) continue
            if (line.startsWith("## ")) {
                val header = line.removePrefix("## ")
                isDetached = header.startsWith("HEAD (no branch)")
                val tracking = header.substringBefore(" [")
                if (isDetached) {
                    branch = "HEAD"
                } else {
                    branch = tracking.substringBefore("...").ifBlank { tracking }
                    upstream = tracking.substringAfter("...", "").ifBlank { null }
                }
                val bracket = header.substringAfter(" [", "")
                if (bracket.isNotBlank()) {
                    bracket.removeSuffix("]").split(",").forEach { tok ->
                        val t = tok.trim()
                        val n = t.filter { it.isDigit() }.toIntOrNull() ?: 0
                        if (t.startsWith("ahead")) ahead = n
                        else if (t.startsWith("behind")) behind = n
                    }
                }
                continue
            }
            if (line.length < 3) continue
            val x = line[0]
            val y = line[1]
            val rawPath = line.substring(3)
            // 重命名形如 "old -> new"，展示新路径。
            val path = unquoteGitPath(rawPath.substringAfter(" -> ").trim())

            if (x == '?' && y == '?') {
                untracked.add(path)
                continue
            }
            // 未合并冲突（Unmerged）：UU, AA, DD, AU, UD, UA, DU 等
            val isConflict = x == 'U' || y == 'U' || (x == 'A' && y == 'A') || (x == 'D' && y == 'D')
            if (isConflict) {
                conflicted.add(GitFileChange(path, "$x$y", staged = false))
                continue
            }
            if (x != ' ' && x != '?') {
                staged.add(GitFileChange(path, x.toString(), staged = true))
            }
            if (y != ' ' && y != '?') {
                unstaged.add(GitFileChange(path, y.toString(), staged = false))
            }
        }
        val isMerging = hasMergeHead(operation) || conflicted.isNotEmpty()
        return GitStatus(branch, ahead, behind, staged, unstaged, untracked, conflicted, isMerging, upstream, isDetached)
    }

    /** 本地 + 远程分支列表，当前分支高亮。 */
    suspend fun branches(operation: FrozenGitOperation = captureOperation()): List<GitBranch> = loadAllRefs(operation).branches

    /**
     * 仅拉本地分支的 refs-by-commit 映射（`for-each-ref refs/heads`，大仓库亚秒级），
     * 供首屏轻量快照的 graph 标注当前分支位置。远程分支与标签标注延迟到 BRANCHES tab 全量加载。
     */
    suspend fun localRefsOnly(operation: FrozenGitOperation = captureOperation()): Map<String, List<GitGraphRef>> = withContext(Dispatchers.Default) {
        val raw = gitAttempt {
            git(operation, "for-each-ref", "--format=%(refname:short)\u001f%(objectname)\u001f%(HEAD)\u001f%(refname)", "refs/heads")
        }.getOrDefault("")
        if (raw.isBlank() || raw.startsWith("fatal:")) return@withContext emptyMap()
        val result = mutableMapOf<String, MutableList<GitGraphRef>>()
        for (line in raw.split('\n')) {
            val l = line.removeSuffix("\r").trim()
            if (l.isBlank()) continue
            val parts = l.split('\u001f')
            if (parts.size < 4) continue
            val isHead = parts[2].trim() == "*"
            result.getOrPut(parts[1]) { mutableListOf() }
                .add(GitGraphRef(parts[0], isBranch = true, isCurrent = isHead, isRemote = false))
        }
        result
    }

    /** 最近 [limit] 条提交。 */
    suspend fun log(limit: Int = 50, operation: FrozenGitOperation = captureOperation()): List<GitCommit> {
        val raw = git(operation, "log", "--pretty=format:%H%x1f%h%x1f%an%x1f%ar%x1f%s", "-n", limit.toString())
        if (raw.isBlank() || raw.startsWith("fatal:")) return emptyList()
        return raw.split('\n').mapNotNull { line ->
            val parts = line.removeSuffix("\r").split('\u001f')
            if (parts.size < 5) null
            else GitCommit(parts[0], parts[1], parts[2], parts[3], parts[4])
        }
    }

    /**
     * 拓扑图视图：提交（含父哈希）+ 引用（分支/标签）+ 泳道布局。
     *
     * `git log --pretty=format:...%P` 在常规字段后追加父哈希列表（空格分隔，多个即合并提交）；
     * `git for-each-ref` 一次拿全部分支/标签及其指向的提交哈希与是否 HEAD。提交与引用解析后，
     * 在 [Dispatchers.Default] 上跑纯 Kotlin 泳道分配算法（见 [GitGraphBuilder]），产出 [GitGraph]。
     * 失败（非仓库/无提交）返回 [GitGraph.EMPTY]，不抛——UI 据空图显示空态。
     *
     * 首批加载取 [GRAPH_PAGE_SIZE] 条；[hasMore] 按本批返回条数是否达到页大小判定，UI 滚到底据此
     * 触发 [graphAppend] 追加下一页。提交数不足页大小时 hasMore=false，UI 不再显示加载更多。
     *
     * [refs] 为外部传入的 refs-by-commit 映射（来自 [loadAllRefs]），避免 graph 内部重复拉全量 refs。
     * 为空时 graph 不标注任何 ref（首屏轻量快照场景：只拉本地分支，远程/标签标注延迟到 BRANCHES tab）。
     */
    suspend fun graph(
        limit: Int = GRAPH_PAGE_SIZE,
        refs: Map<String, List<GitGraphRef>> = emptyMap(),
        operation: FrozenGitOperation = captureOperation()
    ): GitGraph =
        graphAppend(emptyList(), refs, limit, operation)

    /**
     * 分页加载下一批提交并整体重算泳道布局。
     *
     * 从已加载数量处 `git log --skip=<existingCommits.size> -n <limit>` 取下一批提交，与 [existingCommits]
     * 合并为完整列表后整体调 [GitGraphBuilder] 重算布局——泳道分配依赖全局子父顺序，父提交可能跨批次
     * 指向已加载提交，单算新批次会让列号与旧布局冲突导致连线断裂，故必须整图重算。重算开销 O(已加载数)，
     * 移动端纯内存 Kotlin 计算毫秒级，可接受。
     *
     * 返回含全部已加载提交（旧+新）的完整 [GitGraph]，ViewModel 直接整体替换 state.graph；UI 的 LazyColumn
     * 因 `item(key=...)` 机制只增量重组新行不闪烁。[hasMore] = 新批次返回条数达到页大小（未到则历史末尾）。
     */
    suspend fun graphAppend(
        existingCommits: List<GraphCommit>,
        refs: Map<String, List<GitGraphRef>> = emptyMap(),
        limit: Int = GRAPH_PAGE_SIZE,
        operation: FrozenGitOperation = captureOperation()
    ): GitGraph = withContext(Dispatchers.Default) {
        val skip = existingCommits.size
        val logRaw = gitAttempt { git(operation, "log", "--pretty=format:%H%x1f%h%x1f%an%x1f%ar%x1f%s%x1f%P%x1f%b", "--skip", skip.toString(), "-n", limit.toString()) }
            .getOrDefault("")
        if (logRaw.isBlank() || logRaw.startsWith("fatal:")) {
            return@withContext if (existingCommits.isEmpty()) GitGraph.EMPTY
            else GitGraphBuilder.buildGraph(existingCommits, refs, hasMore = false)
        }

        val newCommits = GitGraphBuilder.parseGraphCommits(logRaw)
        if (newCommits.isEmpty() && existingCommits.isEmpty()) return@withContext GitGraph.EMPTY

        // 合并去重：新批次理论上不会与已加载重复（--skip 跳过已加载数量），但防御性按 hash 去重保顺序。
        val existingHashes = existingCommits.mapTo(HashSet()) { it.hash }
        val merged = existingCommits + newCommits.filter { it.hash !in existingHashes }
        GitGraphBuilder.buildGraph(merged, refs, hasMore = newCommits.size >= limit)
    }

    /**
     * 一次拉取全部分支/标签及其指向的提交哈希，解析为三份切面：[AllRefs.branches] 列表、[AllRefs.tags] 列表、
     * [AllRefs.refsByCommit] 映射。`branches()`、`listTags()`、graph 的 refs 标注共用此一次调用，
     * 消除原先三条独立 `for-each-ref`/`tag` 命令（大仓库各 8-20s）。
     *
     * `%(refname:short)` 短名、`%(objectname)` 指向的完整哈希、`%(HEAD)` 标记当前分支（输出 `*` 或空）。
     * 本地分支（refs/heads）isRemote=false，远程分支（refs/remotes）isRemote=true，标签（refs/tags）isBranch=false。
     * 标签按 refname 倒序（纯字符串比较，不读对象内容），避免 `--sort=-creatordate` 遍历所有 tag 对象的 8s 开销。
     */
    data class AllRefs(
        val branches: List<GitBranch>,
        val tags: List<GitTag>,
        val refsByCommit: Map<String, List<GitGraphRef>>
    )

    suspend fun loadAllRefs(operation: FrozenGitOperation = captureOperation()): AllRefs = withContext(Dispatchers.Default) {
        val raw = gitAttempt {
            git(
                operation, "for-each-ref",
                "--format=%(refname:short)\u001f%(objectname)\u001f%(HEAD)\u001f%(refname)\u001f%(upstream:short)\u001f%(upstream:track)",
                "refs/heads", "refs/remotes", "refs/tags"
            )
        }.getOrDefault("")
        if (raw.isBlank() || raw.startsWith("fatal:")) return@withContext AllRefs(emptyList(), emptyList(), emptyMap())
        val branches = mutableListOf<GitBranch>()
        val tags = mutableListOf<GitTag>()
        val refsByCommit = mutableMapOf<String, MutableList<GitGraphRef>>()
        for (line in raw.split('\n')) {
            val l = line.removeSuffix("\r").trim()
            if (l.isBlank()) continue
            val parts = l.split('\u001f')
            if (parts.size < 4) continue
            val name = parts[0]
            val hash = parts[1]
            val isHead = parts[2].trim() == "*"
            val refname = parts[3]
            val upstream = parts.getOrNull(4)?.ifBlank { null }
            val track = parts.getOrNull(5).orEmpty()
            val ahead = Regex("ahead (\\d+)").find(track)?.groupValues?.get(1)?.toIntOrNull() ?: 0
            val behind = Regex("behind (\\d+)").find(track)?.groupValues?.get(1)?.toIntOrNull() ?: 0
            val isRemote = refname.startsWith("refs/remotes")
            val isBranch = refname.startsWith("refs/heads") || isRemote
            refsByCommit.getOrPut(hash) { mutableListOf() }
                .add(GitGraphRef(name, isBranch, isHead && !isRemote, isRemote))
            if (isBranch) {
                branches.add(GitBranch(name, current = isHead && !isRemote, remote = isRemote, upstream = upstream, ahead = ahead, behind = behind))
            } else {
                // 标签：objectname 取短哈希（前 7 位）与原 listTags 行为一致。
                tags.add(GitTag(name, hash.take(7)))
            }
        }
        // for-each-ref 默认按 refname 排序（本地分支、远程分支、标签各自有序），标签再倒序与原行为对齐。
        tags.reverse()
        AllRefs(branches, tags, refsByCommit)
    }

    /**
     * 某次提交改动的文件清单。用 `diff-tree --root` 以兼容无父的根提交；`--no-renames` 让重命名
     * 退化为「删除旧 + 新增新」，状态码取首字符即可复用 [com.aicode.feature.git.domain.model.GitFileChange]。
     * 返回空列表表示该提交无文件改动（如空提交）。
     */
    suspend fun commitFiles(hash: String, operation: FrozenGitOperation = captureOperation()): List<GitFileChange> {
        val raw = git(operation, "diff-tree", "--no-commit-id", "-r", "--root", "--name-status", "--no-renames", hash)
        return withContext(Dispatchers.Default) {
            raw.lineSequence().mapNotNull { line ->
                val l = line.removeSuffix("\r").trim()
                val tab = l.indexOf('\t')
                if (l.isBlank() || tab < 0) null
                else {
                    val status = l.substring(0, tab).trim()
                    val path = unquoteGitPath(l.substring(tab + 1).trim())
                    GitFileChange(path, status, staged = false)
                }
            }.toList()
        }
    }

    suspend fun stage(path: String, operation: FrozenGitOperation = captureOperation()) = gitChecked(operation, "add", "--", path)
    suspend fun unstage(path: String, operation: FrozenGitOperation = captureOperation()) = gitChecked(operation, "reset", "HEAD", "--", path)
    suspend fun stageAll(operation: FrozenGitOperation = captureOperation()) = gitChecked(operation, "add", "-A")
    suspend fun unstageAll(operation: FrozenGitOperation = captureOperation()) = gitChecked(operation, "reset")
    suspend fun commit(message: String, amend: Boolean = false, operation: FrozenGitOperation = captureOperation()) =
        if (amend) gitChecked(operation, "commit", "--amend", "-m", message)
        else gitChecked(operation, "commit", "-m", message)

    /**
     * 获取用于 AI 生成提交信息的差异文本。
     * 优先获取暂存区差异（`git diff --cached`）；如果暂存区为空，则获取工作区差异（`git diff`）。
     */
    suspend fun diffForCommitMessage(operation: FrozenGitOperation = captureOperation()): String {
        val staged = gitRaw(operation, arrayOf("diff", "--cached", "--no-color", "--no-ext-diff"))
        if (staged.isNotBlank() && !staged.startsWith("fatal:")) return staged
        val worktree = gitRaw(operation, arrayOf("diff", "--no-color", "--no-ext-diff"))
        return if (!worktree.startsWith("fatal:")) worktree else ""
    }

    /**
     * 回退单个文件的未暂存改动：把工作区还原成暂存区内容（`git checkout -- <path>`），
     * 被删除的文件由此重新出现。用 `checkout` 而非 `restore`——后者需 git 2.23+，
     * 远程 SSH 模式下服务器 git 可能更老。
     */
    suspend fun revertWorktree(path: String, operation: FrozenGitOperation = captureOperation()): String = gitChecked(operation, "checkout", "--", path)

    /**
     * 回退单个文件的全部改动（已暂存 + 未暂存），还原到上次提交：先 `reset` 让索引回到 HEAD，
     * 再从索引还原工作区。文件在 HEAD 中不存在时（新增文件）reset 已让它退回未跟踪，
     * 此时跳过 checkout——否则 git 会以 `pathspec did not match` 失败。
     * 仓库尚无任何提交时无 HEAD 可比，只能 `rm --cached` 退回未跟踪。
     */
    suspend fun revertToHead(path: String, operation: FrozenGitOperation = captureOperation()): String {
        if (!hasHead(operation)) return gitChecked(operation, "rm", "-q", "--cached", "--", path)
        gitChecked(operation, "reset", "-q", "HEAD", "--", path)
        return if (existsInHead(path, operation)) gitChecked(operation, "checkout", "--", path) else ""
    }

    /**
     * 回退全部未暂存改动。pathspec `:/` 指仓库根，工作区是仓库子目录时也能覆盖全仓库改动
     * （`--  .` 只作用于当前目录）。已暂存的内容不受影响。
     */
    suspend fun revertAllWorktree(operation: FrozenGitOperation = captureOperation()): String = gitChecked(operation, "checkout", "--", ":/")

    /**
     * 删除未跟踪的文件或目录（`git clean -f -d`）。`-d` 覆盖 status 折叠成一行的新目录；
     * 不带 `-x`，被 .gitignore 忽略的文件保持不动。这些文件从未进入版本库，删除后 git 无法恢复。
     */
    suspend fun deleteUntracked(path: String, operation: FrozenGitOperation = captureOperation()): String = gitChecked(operation, "clean", "-f", "-d", "--", path)

    /**
     * 某个未跟踪目录下的未跟踪文件清单。`git status` 默认把新目录折叠成 `dir/` 一行，
     * 里面的文件不单独列出；UI 展开该目录时按需调用此方法，避免 `-uall` 在大目录下一次列出上千条。
     */
    suspend fun untrackedFilesIn(dir: String, operation: FrozenGitOperation = captureOperation()): List<String> {
        val raw = git(operation, "ls-files", "--others", "--exclude-standard", "--", dir)
        if (raw.isBlank() || raw.startsWith("fatal:")) return emptyList()
        return raw.split('\n').mapNotNull { it.removeSuffix("\r").trim().ifBlank { null } }
            .map { unquoteGitPath(it) }
    }

    /** 仓库是否已有提交（HEAD 可解析）。空仓库里 `reset HEAD` / `ls-tree HEAD` 都会失败。 */
    private suspend fun hasHead(operation: FrozenGitOperation): Boolean =
        gitAttempt { git(operation, "rev-parse", "--verify", "HEAD").trim() }
            .getOrDefault("")
            .let { it.isNotBlank() && !it.startsWith("fatal") }

    /** 指定路径在 HEAD 提交中是否存在。 */
    private suspend fun existsInHead(path: String, operation: FrozenGitOperation): Boolean =
        gitAttempt { git(operation, "ls-tree", "HEAD", "--", path).trim() }
            .getOrDefault("")
            .let { it.isNotBlank() && !it.startsWith("fatal") }

    /**
     * 拉取：直接 `git pull`，凭据由容器 `credential.helper` 链自动注入——`store` 命中已有凭据秒过，
     * 未命中时自定义 helper 经文件 IPC 触发 app 弹窗回填，git 自动续跑（见 [CredentialRequestBridge]）。
     * 故不再在此预查 host 凭据：三端（UI/终端/AI）共用同一 helper 兜底，逻辑单一来源。remote 不存在
     * 或真实失败由 [gitChecked] 据退出码抛 [GitCommandFailureException]，上层 toast。
     */
    suspend fun pull(operation: FrozenGitOperation = captureOperation()): String = gitChecked(operation, "pull")

    /**
     * 推送：有上游则 `git push`；当前分支无上游时自动 `git push --set-upstream <remote> <branch>` 首推建关联，
     * 仿 Win/Mac git 客户端「首次推送自动建上游」体验，避免用户撞到 `fatal: has no upstream branch` 原始报错。
     * remote 取 `git remote` 首个（多 remote 默认第一；无 remote 已被上层 hasRemote 门控挡掉）；
     * 分支取 `git rev-parse --abbrev-ref HEAD`。凭据仍由容器 credential.helper 链兜底注入。
     */
    suspend fun push(operation: FrozenGitOperation = captureOperation()): String {
        val hasUpstream = gitAttempt { git(operation, "rev-parse", "--abbrev-ref", "@{upstream}").trim() }
            .getOrDefault("")
            .takeIf { it.isNotBlank() && it != "HEAD" && !it.startsWith("fatal") } != null
        if (hasUpstream) return gitChecked(operation, "push")
        val remote = git(operation, "remote").split('\n').firstOrNull { it.removeSuffix("\r").isNotBlank() }?.removeSuffix("\r")?.trim()
            ?: throw GitCommandFailureException("未配置远程仓库")
        val branch = git(operation, "rev-parse", "--abbrev-ref", "HEAD").removeSuffix("\r").trim()
            .takeIf { it.isNotBlank() && it != "HEAD" }
            ?: throw GitCommandFailureException("无法确定当前分支（处于 detached HEAD）")
        return gitChecked(operation, "push", "--set-upstream", remote, branch)
    }

    /**
     * 当前是否为 master 分支的首次推送（即当前分支为 master 且尚未建立 upstream 跟踪关系）。
     * 供 UI 弹窗询问用户是否重命名为现代通用的 main 分支。
     */
    suspend fun isFirstPushOfMaster(operation: FrozenGitOperation = captureOperation()): Boolean {
        val hasUpstream = gitAttempt { git(operation, "rev-parse", "--abbrev-ref", "@{upstream}").trim() }
            .getOrDefault("")
            .takeIf { it.isNotBlank() && it != "HEAD" && !it.startsWith("fatal") } != null
        if (hasUpstream) return false
        val branch = git(operation, "rev-parse", "--abbrev-ref", "HEAD").removeSuffix("\r").trim()
        return branch == "master"
    }

    /** 将当前 master 分支重命名为 main 并执行首次推送。 */
    suspend fun renameMasterToMainAndPush(operation: FrozenGitOperation = captureOperation()): String {
        renameBranch("master", "main", operation)
        return push(operation)
    }

    /** 本地标签列表，按创建时间倒序（最新在前）。 */
    suspend fun listTags(operation: FrozenGitOperation = captureOperation()): List<GitTag> = loadAllRefs(operation).tags

    /**
     * 创建新分支。name 为新分支名；startPoint 为基准分支名（null/空 → 从当前 HEAD）；
     * checkout=true 则创建并切换（`git checkout -b`），否则仅创建不切换（`git branch`）。
     * 起点不存在或分支名非法时由 [gitChecked] 据退出码抛 [GitCommandFailureException]，上层 toast。
     */
    suspend fun createBranch(name: String, startPoint: String?, checkout: Boolean, operation: FrozenGitOperation = captureOperation()): String {
        return if (checkout) {
            if (startPoint.isNullOrBlank()) gitChecked(operation, "checkout", "-b", name)
            else gitChecked(operation, "checkout", "-b", name, startPoint)
        } else {
            if (startPoint.isNullOrBlank()) gitChecked(operation, "branch", name)
            else gitChecked(operation, "branch", name, startPoint)
        }
    }

    /**
     * 安全删除本地分支（`git branch -d`）：仅删除已合并到上游的分支，未合并时 git 报错
     * 由 [gitChecked] 据退出码抛 [GitCommandFailureException]，上层 toast。当前分支不可删（git 自身拦截）。
     */
    suspend fun deleteBranch(name: String, operation: FrozenGitOperation = captureOperation()): String = gitChecked(operation, "branch", "-d", name)

    /**
     * 重命名本地分支（`git branch -m <old> <new>`）。当前分支也可重命名：传单参数 `git branch -m <new>`
     * 重命名当前分支；这里统一用双参数形式，由上层保证 oldName 非空。名字非法或已存在时由 [gitChecked]
     * 据退出码抛 [GitCommandFailureException]，上层 toast。
     */
    suspend fun renameBranch(oldName: String, newName: String, operation: FrozenGitOperation = captureOperation()): String =
        gitChecked(operation, "branch", "-m", oldName, newName)

    /**
     * 创建轻量标签（`git tag <name>`），指向当前 HEAD 或指定提交。
     */
    suspend fun createTag(name: String, commitHash: String? = null, operation: FrozenGitOperation = captureOperation()): String =
        if (commitHash.isNullOrBlank()) gitChecked(operation, "tag", name)
        else gitChecked(operation, "tag", name, commitHash)

    /**
     * 重置当前分支到指定提交。
     * [mode] 可以是 "soft" 或 "mixed"。
     */
    suspend fun resetToCommit(commitHash: String, mode: String = "mixed", operation: FrozenGitOperation = captureOperation()): String =
        gitChecked(operation, "reset", "--$mode", commitHash)

    /**
     * 将忽略规则追加到工作区的 .gitignore 文件中。
     * 无论 .gitignore 是否已存在均可自动创建与追加；通过 engine 执行以同时兼容本地与远程模式。
     */
    suspend fun addToGitignore(pattern: String, operation: FrozenGitOperation = captureOperation()): String {
        val normalized = pattern.trim()
        if (normalized.isBlank()) return ""
        // 先检查 .gitignore 是否已有该规则
        val checkCmd = "grep -F -x -- ${shellQuote(normalized)} .gitignore"
        val checkResult = execute(operation, checkCmd)
        if (checkResult.exitCode == 0) return normalized // 已存在，无需重复追加

        // 不存在则通过 printf 安全追加（如果文件不存在则自动创建）
        val appendCmd = "printf '%s\\n' ${shellQuote(normalized)} >> .gitignore"
        val appendResult = execute(operation, appendCmd)
        if (appendResult.exitCode != 0) {
            throw GitCommandFailureException(appendResult.output.ifBlank { "写入 .gitignore 失败" })
        }
        return normalized
    }

    /**
     * 删除本地标签（`git tag -d <name>`）。不存在时由 [gitChecked] 据退出码抛 [GitCommandFailureException]，上层 toast。
     */
    suspend fun deleteTag(name: String, operation: FrozenGitOperation = captureOperation()): String = gitChecked(operation, "tag", "-d", name)

    /**
     * 删除远程分支（`git push <remote> --delete <branch>`）。ref 形如 `origin/feature`，拆出 remote 与分支名；
     * 无 remote 前缀时按 `origin` 兜底。会改远端，失败由 [gitChecked] 抛 [GitCommandFailureException]。
     */
    suspend fun deleteRemoteBranch(ref: String, operation: FrozenGitOperation = captureOperation()): String {
        val remote = ref.substringBefore('/', "origin")
        val branch = ref.substringAfter('/', ref)
        return gitChecked(operation, "push", remote, "--delete", branch)
    }

    /** 是否正在处于未完成的合并状态（MERGE_HEAD 存在）。 */
    private suspend fun hasMergeHead(operation: FrozenGitOperation): Boolean =
        gitAttempt { git(operation, "rev-parse", "-q", "--verify", "MERGE_HEAD").trim() }
            .getOrDefault("")
            .let { it.isNotBlank() && !it.startsWith("fatal") }

    /**
     * 切换到指定分支或标签。branch 可以是本地分支名、远程分支名或 tag 名。
     * 远程分支用 `git checkout -b <local> <remote>` 创建本地跟踪分支，去掉远程前缀（如 origin/）。
     */
    suspend fun checkout(branch: String, isRemote: Boolean, operation: FrozenGitOperation = captureOperation()): String {
        return if (isRemote) {
            val localName = branch.substringAfter('/', branch)
            gitChecked(operation, "checkout", "-b", localName, "--track", branch)
        } else {
            gitChecked(operation, "checkout", branch)
        }
    }

    /**
     * 合并指定分支到当前分支。
     * [branch] 为目标分支名。
     * [noFf] 为 true 时即使可快进也强制生成合并提交（`--no-ff`）。
     * 遇到冲突或脏工作区时由 [gitChecked] 抛 [GitCommandFailureException]。
     */
    suspend fun mergeBranch(branch: String, noFf: Boolean = false, operation: FrozenGitOperation = captureOperation()): String {
        return if (noFf) gitChecked(operation, "merge", "--no-ff", branch)
        else gitChecked(operation, "merge", branch)
    }

    /**
     * 中止当前进行中的合并（`git merge --abort`）。
     */
    suspend fun abortMerge(operation: FrozenGitOperation = captureOperation()): String = gitChecked(operation, "merge", "--abort")

    /**
     * 列出所有储藏记录。
     */
    suspend fun stashList(operation: FrozenGitOperation = captureOperation()): List<GitStash> = withContext(Dispatchers.Default) {
        val raw = gitAttempt { git(operation, "stash", "list", "--pretty=format:%gd%x1f%s%x1f%ar") }
            .getOrDefault("")
        if (raw.isBlank() || raw.startsWith("fatal:")) return@withContext emptyList()
        raw.split('\n').mapNotNull { line ->
            val l = line.removeSuffix("\r").trim()
            if (l.isBlank()) return@mapNotNull null
            val parts = l.split('\u001f')
            if (parts.size < 3) null
            else GitStash(index = parts[0], message = parts[1], date = parts[2])
        }
    }

    /**
     * 储藏工作区改动。
     * [message] 可选说明；[includeUntracked] 为 true 时包含未跟踪文件（`-u`）。
     */
    suspend fun stashSave(message: String? = null, includeUntracked: Boolean = false, operation: FrozenGitOperation = captureOperation()): String {
        val args = mutableListOf("stash", "push")
        if (includeUntracked) args.add("-u")
        if (!message.isNullOrBlank()) {
            args.add("-m")
            args.add(message.trim())
        }
        return gitChecked(operation, *args.toTypedArray())
    }

    /**
     * 弹出指定储藏（应用并删除），默认为最近的一条 `stash@{0}`。
     */
    suspend fun stashPop(index: String = "stash@{0}", operation: FrozenGitOperation = captureOperation()): String =
        gitChecked(operation, "stash", "pop", index)

    /**
     * 应用指定储藏（保留记录），默认为最近的一条 `stash@{0}`。
     */
    suspend fun stashApply(index: String = "stash@{0}", operation: FrozenGitOperation = captureOperation()): String =
        gitChecked(operation, "stash", "apply", index)

    /**
     * 丢弃指定储藏，默认为 `stash@{0}`。
     */
    suspend fun stashDrop(index: String = "stash@{0}", operation: FrozenGitOperation = captureOperation()): String =
        gitChecked(operation, "stash", "drop", index)

    /**
     * 清空所有储藏记录。
     */
    suspend fun stashClear(operation: FrozenGitOperation = captureOperation()): String = gitChecked(operation, "stash", "clear")

    /**
     * 写入提交署名，**优先项目级**：当前工作区（~/workspace/.git/config）已有项目级署名时写 local，
     * 否则写 global（容器 `GIT_CONFIG_GLOBAL=/root/.aicode/.gitconfig`，持久挂载，跨 rootfs 升级不丢）作默认。
     * 这样 UI 与终端 `git config user.name` 读到的同一份——优先项目级、无则退全局，对齐 git 自身解析顺序。
     * 空值跳过对应项不动现有配置。由 UI 在用户保存身份时显式调用。
     */
    suspend fun setUserIdentity(name: String, email: String, operation: FrozenGitOperation = captureOperation()) {
        if (name.isNotBlank()) gitChecked(operation, "config", if (hasLocalConfig("user.name", operation)) "--local" else "--global", "user.name", name)
        if (email.isNotBlank()) gitChecked(operation, "config", if (hasLocalConfig("user.email", operation)) "--local" else "--global", "user.email", email)
    }

    /** 当前工作区是否有项目级（`--local`）配置值。非 git 仓库或无值时返回 false。 */
    private suspend fun hasLocalConfig(key: String, operation: FrozenGitOperation): Boolean =
        gitAttempt { git(operation, "config", "--local", "--get", key).trim() }.getOrDefault("").isNotBlank()

    /** 读取 git 当前实际生效的 user.name（按 git 解析顺序：local→global→system），UI 回显与提交按钮判空用。失败返回空串。 */
    suspend fun getUserName(operation: FrozenGitOperation = captureOperation()): String =
        gitAttempt { git(operation, "config", "--get", "user.name").trim() }.getOrDefault("").removeSuffix("\r")

    /** 读取 git 当前实际生效的 user.email（local→global→system），UI 回显与编辑框初值。失败返回空串。 */
    suspend fun getUserEmail(operation: FrozenGitOperation = captureOperation()): String =
        gitAttempt { git(operation, "config", "--get", "user.email").trim() }.getOrDefault("").removeSuffix("\r")

    /**
     * 写入仓库地址 remote.origin.url，**仅写项目级**：remote.origin.url 是单个仓库的远端地址，
     * 与 user.name/email 这种「身份默认值」不同——它绝不该写 global。一旦写进 global，后续 `git clone`
     * 任何新仓库时，git 会同时解析出全局与 clone 写入的 local 两个 origin.url，导致 fetch 走全局旧值、
     * push 出现多条指向不同仓库的条目（实测复现），clone 下来的仓库 fetch 到的是错误仓库。
     *
     * 故此处始终写 `--local`（当前工作区须是 git 仓库），非仓库时抛 [GitCommandFailureException] 提示用户，
     * 不再退到 global 兑底。同时顺手清掉全局可能残留的 remote.origin.url（历史版本误写下的），一次性消除污染。
     */
    suspend fun setRepoUrl(url: String, operation: FrozenGitOperation = captureOperation()) {
        if (url.isNotBlank()) {
            gitChecked(operation, "config", "--local", "remote.origin.url", url)
        } else {
            // 空值删除 local 的 remote.origin.url，git config --unset 对不存在的 key 返回非零但不影响其它配置
            gitAttempt { gitChecked(operation, "config", "--local", "--unset", "remote.origin.url") }
        }
        // 清除全局残留的 remote.origin.url（历史误写），避免污染后续 git clone 等命令。
        gitAttempt { git(operation, "config", "--global", "--unset", "remote.origin.url") }
    }

    /** 读取 git 当前实际生效的 remote.origin.url（local→global→system），UI 回显与编辑框初值。失败返回空串。 */
    suspend fun getRepoUrl(operation: FrozenGitOperation = captureOperation()): String =
        gitAttempt { git(operation, "config", "--get", "remote.origin.url").trim() }.getOrDefault("").removeSuffix("\r")

    /**
     * 读取指定 ref（提交/分支/标签）下某文件的完整内容（`git show <ref>:<path>`）。
     * 用于提交文件 diff：取 `<hash>^:<path>`（改动前）与 `<hash>:<path>`（改动后）对比。
     * 文件在指定 ref 不存在时（如新增文件的首个提交）git 报错输出 `fatal:`，此处检测到即返回空串，
     * 上层据空串判定为「新增/删除」，整个文件按全增或全删呈现。
     */
    suspend fun showFileContent(ref: String, path: String, operation: FrozenGitOperation = captureOperation()): String {
        if (refBlobSize("$ref:$path", operation) > MAX_DIFF_FILE_BYTES) throw GitOutputTooLargeException()
        val out = git(operation, "show", "$ref:$path")
        // git show 对不存在的路径输出 fatal 到 stderr，runCommandSync 合并了 stdout+stderr。
        // 检测到 fatal 前缀视为该版本无此文件，返回空串让 diff 按全增/全删处理。
        return if (out.startsWith("fatal:") || out.startsWith("error:")) "" else out
    }

    /** 指定 ref 下文件（`<ref>:<path>` / `:<path>`）的字节数；取不到（不存在/非法 ref）时返回 0。 */
    private suspend fun refBlobSize(spec: String, operation: FrozenGitOperation): Long =
        gitAttempt { git(operation, "cat-file", "-s", spec).trim().toLong() }.getOrDefault(0L)

    /**
     * 读取工作区当前文件内容。用于工作区改动 diff：与 `HEAD:<path>` 对比看出未暂存的改动。
     * 文件不存在或读取失败返回空串。
     *
     * 本地模式工作区就在 app 私有目录，直接用 [java.io.File] 读最快；远程 SSH 模式下
     * [WorkspaceRepository.currentPath] 是远端绝对路径，本地 File 读不到（会静默变空串，
     * diff 显示成整文件删除），故退回在执行后端里 `cat` 取内容。
     */
    suspend fun worktreeFileContent(path: String, operation: FrozenGitOperation = captureOperation()): String {
        operation.execution()
        operation.checkCurrent()
        val local = if (operation.mode == ExecutionMode.REMOTE_SSH) null else withContext(Dispatchers.IO) {
            operation.checkCurrent()
            gitAttempt {
                java.io.File(operation.repoRoot, path)
                    .takeIf { it.isFile }
                    ?.also { if (it.length() > MAX_DIFF_FILE_BYTES) throw GitOutputTooLargeException() }
                    ?.readText()
            }.getOrElse { e ->
                if (e is GitOutputTooLargeException) throw e
                null
            }
        }
        operation.checkCurrent()
        if (local != null) return local
        val result = execute(operation, "cat -- ${shellQuote(path)}")
        if (result.outputTruncated) throw GitOutputTooLargeException()
        return if (result.exitCode == 0) result.output else ""
    }

    /** 读取暂存区当前文件内容（index）。文件尚未暂存时返回空串。 */
    suspend fun indexFileContent(path: String, operation: FrozenGitOperation = captureOperation()): String {
        if (refBlobSize(":$path", operation) > MAX_DIFF_FILE_BYTES) throw GitOutputTooLargeException()
        val out = git(operation, "show", ":$path")
        return if (out.startsWith("fatal:") || out.startsWith("error:")) "" else out
    }

    /**
     * 对单个 shell 参数做单引号转义。含「安全字符」之外的字符（空格、`|`、`$`、反引号、`*` 等）时
     * 整体包单引号，内嵌单引号用 `'\''` 关闭-转义-重开。格式串（带 `|`、`%(...)`）、含空格路径、
     * 提交消息均由此安全传递。注意 `|` 是 shell 管道符，**不可**列入安全集。
     */
    private fun shellQuote(arg: String): String {
        if (arg.isEmpty()) return "''"
        if (arg.all { it.isLetterOrDigit() || it in "_.@/:=+,%-" }) return arg
        return "'" + arg.replace("'", "'\\''") + "'"
    }
}

/**
 * porcelain v1 / ls-files / diff-tree 对含引号、反斜杠、空格或控制字符的路径会整体加引号并做
 * C 风格转义（如 `"a\"b.txt"`、`"a\tb.txt"`、八进制 `\NNN`），这里做反向解析还原真实路径。
 * 未加引号的普通路径原样返回。
 *
 * 八进制转义是**逐字节**的：一个汉字三个字节会转成三段 `\NNN`，逐段 `toChar()` 只能得到
 * 三个 Latin-1 字符（即乱码），故连续的 `\NNN` 先攒成字节序列再整体按 UTF-8 解码。
 */
internal fun unquoteGitPath(raw: String): String {
    if (!raw.startsWith("\"")) return raw
    val inner = raw.removeSurrounding("\"")
    val sb = StringBuilder(inner.length)
    var i = 0
    while (i < inner.length) {
        val c = inner[i]
        if (c != '\\' || i + 1 >= inner.length) {
            sb.append(c)
            i += 1
            continue
        }
        if (inner[i + 1] in '0'..'7') {
            val bytes = mutableListOf<Byte>()
            while (i + 1 < inner.length && inner[i] == '\\' && inner[i + 1] in '0'..'7') {
                val octal = inner.substring(i + 1, minOf(i + 4, inner.length)).takeWhile { it in '0'..'7' }
                if (octal.length != 3) break
                bytes.add(octal.toInt(8).toByte())
                i += 4
            }
            if (bytes.isEmpty()) {
                sb.append(c)
                i += 1
            } else {
                sb.append(String(bytes.toByteArray(), Charsets.UTF_8))
            }
            continue
        }
        val decoded = when (inner[i + 1]) {
            'a' -> '\u0007'
            'b' -> '\b'
            'f' -> '\u000C'
            'n' -> '\n'
            'r' -> '\r'
            't' -> '\t'
            'v' -> '\u000B'
            '"' -> '"'
            '\\' -> '\\'
            else -> null
        }
        if (decoded == null) {
            sb.append(c)
            i += 1
        } else {
            sb.append(decoded)
            i += 2
        }
    }
    return sb.toString()
}
