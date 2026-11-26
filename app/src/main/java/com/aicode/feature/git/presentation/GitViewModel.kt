package com.aicode.feature.git.presentation

import android.content.Context
import android.content.res.Configuration
import androidx.annotation.StringRes
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.aicode.R
import com.aicode.core.util.FileLogger
import com.aicode.core.util.LineDiff
import com.aicode.feature.agent.domain.workflow.AgentWorkflow
import com.aicode.feature.git.domain.GitCommandFailureException
import com.aicode.feature.git.domain.GitOutputTooLargeException
import com.aicode.feature.git.domain.GitErrorMessage
import com.aicode.feature.git.domain.GitRepository
import com.aicode.feature.git.domain.FrozenGitOperation
import com.aicode.feature.git.domain.GitOperationExpiredException
import com.aicode.feature.git.domain.GitWorkspaceRequiredException
import com.aicode.feature.agent.domain.container.runtime.LocalRuntimeAuthorizationRequired
import com.aicode.feature.git.domain.model.GitBranch
import com.aicode.feature.git.domain.model.GitCommit
import com.aicode.feature.git.domain.model.GitFileChange
import com.aicode.feature.git.domain.model.GitGraph
import com.aicode.feature.git.domain.model.GitStash
import com.aicode.feature.git.domain.model.GitStatus
import com.aicode.feature.git.domain.model.GitTab
import com.aicode.feature.git.domain.model.GitTag
import com.aicode.feature.git.presentation.component.DiffData
import com.aicode.feature.git.presentation.component.DiffRow
import com.aicode.feature.git.presentation.component.highlightCode
import com.aicode.feature.git.presentation.component.inferSyntaxLanguage
import com.aicode.feature.settings.data.repository.AppThemeMode
import com.aicode.feature.settings.data.repository.ThemeSettingsRepository
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.withContext
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import javax.inject.Inject

/**
 * Git 页的 UI 状态与操作。
 *
 * 进入页面即 [refresh] 并发拉取 status/graph/remote，随后异步拉取全量分支/标签；每个写操作
 * （暂存/提交/拉取/推送）执行后自动刷新并通过 [GitUiState.toast] 反馈。[GitUiState.busy]
 * 守卫防止并发写操作相互踩踏。
 */
private fun GitGraph.toGitCommits() =
    commits.map { GitCommit(it.hash, it.shortHash, it.author, it.date, it.message) }

@HiltViewModel
class GitViewModel @Inject constructor(
    private val repository: GitRepository,
    @param:ApplicationContext private val context: Context,
    private val themeSettings: ThemeSettingsRepository,
    private val agentWorkflow: AgentWorkflow
) : ViewModel() {

    private companion object {
        const val TAG = "GitViewModel"
        /** diff 行数上限：超过则跳过 LCS，避免移动端 O(n·m) 内存压力。与 FileTools 对齐。 */
        const val MAX_DIFF_LINES = 2000
        /** 单行长度上限：超长单行（如压缩的 JSON）渲染会撑爆 Compose Constraints，直接降级提示。 */
        const val MAX_DIFF_LINE_LENGTH = 2000
        /** diff 两侧内容的来源标记，只进 [DiffData.oldRef]/[DiffData.newRef] 供排查，不展示给用户。 */
        const val REF_WORKTREE = "worktree"
        const val REF_INDEX = "index"
        const val REF_EMPTY = "empty"
    }

    data class GitUiState(
        val loading: Boolean = true,
        val notARepo: Boolean = false,
        val status: GitStatus? = null,
        val branches: List<GitBranch> = emptyList(),
        val commits: List<GitCommit> = emptyList(),
        /** 拓扑图视图：提交（含父哈希）+ 引用 + 泳道布局，供 LogTab 绘制彩色分支连线。 */
        val graph: GitGraph = GitGraph.EMPTY,
        val tags: List<GitTag> = emptyList(),
        val tab: GitTab = GitTab.STATUS,
        val busy: Boolean = false,
        val toast: String? = null,
        /** 是否已配置远程仓库，控制拉取/推送按钮可用性。 */
        val hasRemote: Boolean = false,
        /** 是否已配置全局署名 user.name（git config --global），控制提交按钮可用性；无署名提交会成为失败提交。 */
        val hasIdentity: Boolean = false,
        /** 工作区里发现的 git 仓库路径列表（根 + 一层子目录）。 */
        val repos: List<String> = emptyList(),
        /** 当前选中的 git 仓库路径；null 表示未选中（工作区根 + 子目录都非仓库）。 */
        val currentRepo: String? = null,
        /** 工作区根的一层直接子目录（仓库选择弹窗展示扫描范围）。 */
        val subdirs: List<String> = emptyList(),
        /** 当前工作区根路径（仓库选择弹窗的基准目录）。 */
        val workspaceRoot: String = "",
        /** 是否显示仓库选择弹窗。 */
        val repoPickerVisible: Boolean = false,
        /** 当前在详情弹层中查看的提交 hash；null 表示未打开。 */
        val commitDetailHash: String? = null,
        /** 已懒加载的提交文件清单，按 hash 缓存。 */
        val commitFiles: Map<String, List<GitFileChange>> = emptyMap(),
        /** 正在加载文件清单的提交 hash。 */
        val loadingCommit: String? = null,
        /** 正在分页加载更旧的提交（滚动到底触发）。不置 busy，避免阻塞写操作。 */
        val graphLoadingMore: Boolean = false,
        /** 全量分支/标签是否已加载（进入页面即后台拉取，不阻塞首屏；切 BRANCHES tab 时未完成则继续等）。 */
        val branchesLoaded: Boolean = false,
        /** 正在加载全量分支/标签。 */
        val branchesLoading: Boolean = false,
        /** 储藏列表。 */
        val stashes: List<GitStash> = emptyList(),
        /** 正在加载储藏列表。 */
        val stashesLoading: Boolean = false,
        /** 正在切换分支。 */
        val checkoutLoading: String? = null,
        /** 已展开的未跟踪目录 → 其下未跟踪文件清单。git status 把新目录折叠成一行，展开时按需查询。 */
        val untrackedDirFiles: Map<String, List<String>> = emptyMap(),
        /** 正在展开的未跟踪目录路径。 */
        val untrackedDirLoading: String? = null,
        /** 正在生成提交信息。 */
        val generatingCommitMessage: Boolean = false,
        /** 是否显示 diff 全屏页；进入即置 true，diffData 为 null 时页内显示加载中。 */
        val diffVisible: Boolean = false,
        /** 正在查看 diff 的文件路径；加载中（diffData 为 null）时供顶栏显示文件名。 */
        val diffPath: String? = null,
        /** diff 视图数据；非 null 时 diff 页渲染内容。 */
        val diffData: DiffData? = null
    )

    private val _state = MutableStateFlow(GitUiState())
    val state: StateFlow<GitUiState> = _state.asStateFlow()

    private var displayedOperation: FrozenGitOperation? = null
    private var pendingPushOperation: FrozenGitOperation? = null

    private fun captureUiOperation(): FrozenGitOperation? = try {
        (displayedOperation ?: repository.captureOperation()).also { it.checkCurrent() }
    } catch (failure: Exception) {
        showOperationFailure(failure)
        null
    }

    private fun showOperationFailure(failure: Exception) {
        val message = when (failure) {
            is GitOperationExpiredException -> context.getString(R.string.git_execution_source_changed)
            is GitWorkspaceRequiredException -> context.getString(R.string.git_execution_workspace_required)
            is LocalRuntimeAuthorizationRequired -> context.getString(R.string.local_runtime_authorization_required)
            else -> context.getString(R.string.git_toast_refresh_failed, failure.message)
        }
        _state.update { it.copy(loading = false, busy = false, branchesLoading = false, graphLoadingMore = false,
            stashesLoading = false, checkoutLoading = null, untrackedDirLoading = null, loadingCommit = null,
            generatingCommitMessage = false, diffVisible = false,
            toast = message) }
    }

    private fun launchOperation(operation: FrozenGitOperation, block: suspend () -> Unit) {
        viewModelScope.launch {
            try {
                operation.checkCurrent()
                block()
            } catch (failure: Exception) {
                if (failure is CancellationException) throw failure
                val displayed = displayedOperation
                if (displayed == null || displayed === operation || displayed.owner == operation.owner ||
                    runCatching { displayed.checkCurrent() }.isFailure) showOperationFailure(failure)
            }
        }
    }

    private fun publish(operation: FrozenGitOperation, transform: (GitUiState) -> GitUiState) {
        operation.checkCurrent()
        _state.update(transform)
    }

    init { refresh() }

    /** 仓库快照：并发拉取 status/graph/remote（+可选 identity）的结果。不含全量分支/标签——
     * 那些在 [refresh] 成功后异步拉取（[loadBranches]），避免阻塞首屏。
     * graph 的 refs 标注只用本地分支（[repository.localRefsOnly]，亚秒级），远程/标签标注由 [loadBranches] 补全。 */
    private data class RepoSnapshot(
        val status: GitStatus,
        val graph: GitGraph,
        val hasRemote: Boolean,
        val hasIdentity: Boolean = false,
        val untrackedDirFiles: Map<String, List<String>> = emptyMap()
    )

    /** 并发拉取轻量快照：status + graph(本地 refs) + remote + 可选 identity。 */
    private suspend fun loadSnapshot(operation: FrozenGitOperation, includeIdentity: Boolean): RepoSnapshot = coroutineScope {
        val s = async { repository.status(operation) }
        val localRefs = async { repository.localRefsOnly(operation) }
        val r = async { repository.hasRemote(operation) }
        val id = async { if (includeIdentity) repository.getUserName(operation).isNotBlank() else false }
        val g = async { repository.graph(refs = localRefs.await(), operation = operation) }
        val status = s.await()
        RepoSnapshot(status, g.await(), r.await(), id.await(), reloadExpandedUntrackedDirs(operation, status))
    }

    /**
     * 刷新后重查已展开的未跟踪目录，保住展开态。只保留仍出现在 [GitStatus.untracked] 里的目录——
     * 目录被暂存或删除后就不该再留着陈旧的文件列表。
     */
    private suspend fun reloadExpandedUntrackedDirs(operation: FrozenGitOperation, status: GitStatus): Map<String, List<String>> {
        val dirs = _state.value.untrackedDirFiles.keys.filter { it in status.untracked }
        if (dirs.isEmpty()) return emptyMap()
        return coroutineScope {
            dirs.map { dir -> dir to async { repository.untrackedFilesIn(dir, operation) } }
                .associate { (dir, deferred) -> dir to deferred.await() }
        }
    }

    fun setTab(tab: GitTab) {
        _state.update { it.copy(tab = tab) }
        // 兜底：进入页面即已异步加载；若尚未开始/完成（如加载失败后重进），切 tab 时再补一次。
        if (tab == GitTab.BRANCHES && !_state.value.branchesLoaded && !_state.value.branchesLoading) {
            loadBranches()
        }
    }

    /**
     * 加载全量分支/标签（进入页面 [refresh] 成功后即触发，无需等切 BRANCHES tab）。
     * 一次 [repository.loadAllRefs] 拉取，填充 branches/tags 并用全量 refs 更新 graph 的标注。
     * 不置 busy，只读不阻塞写操作。已加载过则跳过；写操作后需手动调 [refreshBranchesIfLoaded] 同步。
     */
    fun loadBranches() {
        val operation = captureUiOperation() ?: return
        loadBranches(operation)
    }

    private fun loadBranches(operation: FrozenGitOperation) {
        if (_state.value.branchesLoading || _state.value.branchesLoaded) return
        publish(operation) { it.copy(branchesLoading = true) }
        launchOperation(operation) {
            try {
                val allRefs = repository.loadAllRefs(operation)
                // 用全量 refs 更新 graph 标注（远程分支+标签补全）。
                val graph = repository.graphAppend(_state.value.graph.commits, allRefs.refsByCommit, operation = operation)
                val commits = graph.toGitCommits()
                publish(operation) {
                    it.copy(branches = allRefs.branches, tags = allRefs.tags, graph = graph, commits = commits, branchesLoading = false, branchesLoaded = true)
                }
            } catch (e: Exception) {
                if (e is CancellationException || e is GitOperationExpiredException || e is LocalRuntimeAuthorizationRequired) throw e
                FileLogger.e(TAG, "加载分支列表失败", e)
                publish(operation) { it.copy(branchesLoading = false, toast = context.getString(R.string.git_toast_load_branches_failed, e.message)) }
            }
        }
    }

    /**
     * 写操作后同步刷新已加载的分支/标签（若已加载过）。未加载过则不主动拉，保持延迟加载语义。
     */
    private fun refreshBranchesIfLoaded(operation: FrozenGitOperation) {
        if (!_state.value.branchesLoaded) return
        launchOperation(operation) {
            try {
                val allRefs = repository.loadAllRefs(operation)
                publish(operation) { it.copy(branches = allRefs.branches, tags = allRefs.tags) }
            } catch (e: Exception) {
                if (e is CancellationException || e is GitOperationExpiredException || e is LocalRuntimeAuthorizationRequired) throw e
                FileLogger.e(TAG, "刷新分支列表失败", e)
            }
        }
    }

    fun refresh() {
        if (_state.value.busy) return
        var operation = try { repository.captureOperation() } catch (failure: Exception) {
            showOperationFailure(failure)
            return
        }
        displayedOperation = operation
        pendingPushOperation = null
        publish(operation) { it.copy(loading = true, toast = null) }
        launchOperation(operation) {
            try {
                repository.notReadyHint(operation)?.let { hint ->
                    publish(operation) { it.copy(loading = false, toast = hint) }
                    return@launchOperation
                }
                // 发现工作区里的 git 仓库（根 + 一层子目录），选中当前或默认仓库。
                val repos = repository.discoverRepos(operation)
                val subdirs = repository.listSubdirectories(operation)
                val workspaceRoot = repository.workspaceRoot(operation)
                val current = repository.currentRepoPath
                // 当前选中仓库已不在发现列表（如切换了工作区）时回退默认：工作区根优先，否则第一个子目录仓库。
                val effectiveRepo = current?.takeIf { it in repos } ?: repos.firstOrNull()
                repository.setRepoPath(effectiveRepo, operation)
                operation = repository.captureOperation()
                displayedOperation = operation
                if (effectiveRepo == null) {
                    publish(operation) { it.copy(loading = false, notARepo = true, repos = repos, subdirs = subdirs, workspaceRoot = workspaceRoot, currentRepo = null) }
                    return@launchOperation
                }
                val snap = loadSnapshot(operation, includeIdentity = true)
                val commits = snap.graph.toGitCommits()
                publish(operation) {
                    it.copy(commitFiles = emptyMap(), commitDetailHash = null, stashes = emptyList(),
                        diffVisible = false, diffPath = null, diffData = null, loading = false, notARepo = false, status = snap.status, commits = commits, graph = snap.graph, hasRemote = snap.hasRemote, hasIdentity = snap.hasIdentity, untrackedDirFiles = snap.untrackedDirFiles, branchesLoaded = false, branchesLoading = false, branches = emptyList(), tags = emptyList(), repos = repos, subdirs = subdirs, workspaceRoot = workspaceRoot, currentRepo = effectiveRepo)
                }
                // 页面已打开：后台拉取全量分支/标签，用户切到 BRANCHES tab 时无需再等。
                loadBranches(operation)
            } catch (e: Exception) {
                if (e is CancellationException || e is GitOperationExpiredException || e is LocalRuntimeAuthorizationRequired) throw e
                FileLogger.e(TAG, "刷新失败", e)
                publish(operation) { it.copy(loading = false, toast = context.getString(R.string.git_toast_refresh_failed, e.message)) }
            }
        }
    }

    /** 执行一个写操作：置 busy → 跑命令 → 刷新 → 反馈。操作间互斥。 */
    private fun runAction(
        @StringRes nameRes: Int,
        action: suspend (FrozenGitOperation) -> String,
        operation: FrozenGitOperation? = captureUiOperation(),
        onComplete: (suspend (FrozenGitOperation) -> Unit)? = null
    ) {
        if (_state.value.busy || operation == null) return
        try { operation.checkCurrent() } catch (failure: Exception) { showOperationFailure(failure); return }
        publish(operation) { it.copy(busy = true, toast = null) }
        launchOperation(operation) {
            val name = context.getString(nameRes)
            val msg = try {
                action(operation)
                operation.checkCurrent()
                context.getString(R.string.git_toast_action_success, name)
            } catch (e: Exception) {
                if (e is CancellationException || e is GitOperationExpiredException || e is LocalRuntimeAuthorizationRequired) throw e
                FileLogger.e(TAG, "${name}失败", e)
                val reason = (e as? GitCommandFailureException)?.output ?: e.message
                context.getString(R.string.git_toast_action_failed, name, GitErrorMessage.friendly(reason ?: ""))
            }
            // 刷新以反映新状态；失败也刷新，让 UI 与仓库一致。
            try {
                if (repository.isRepo(operation)) {
                    val snap = loadSnapshot(operation, includeIdentity = false)
                    val commits = snap.graph.toGitCommits()
                    publish(operation) { it.copy(busy = false, status = snap.status, commits = commits, graph = snap.graph, hasRemote = snap.hasRemote, untrackedDirFiles = snap.untrackedDirFiles, notARepo = false, toast = msg) }
                    refreshBranchesIfLoaded(operation)
                    onComplete?.invoke(operation)
                } else {
                    publish(operation) { it.copy(busy = false, notARepo = true, toast = msg) }
                }
            } catch (e: Exception) {
                if (e is CancellationException || e is GitOperationExpiredException || e is LocalRuntimeAuthorizationRequired) throw e
                publish(operation) { it.copy(busy = false, toast = context.getString(R.string.git_toast_action_refresh_failed, msg)) }
            }
        }
    }

    fun stage(path: String) = runAction(R.string.git_stage, { operation -> repository.stage(path, operation) })
    fun unstage(path: String) = runAction(R.string.git_unstage, { operation -> repository.unstage(path, operation) })
    fun stageAll() = runAction(R.string.git_action_stage_all, { operation -> repository.stageAll(operation) })
    fun unstageAll() = runAction(R.string.git_action_unstage_all, { operation -> repository.unstageAll(operation) })

    /**
     * 回退单个文件的改动。[staged] 为 true 时连已暂存内容一起还原到上次提交，否则只回退未暂存改动
     * （被删除的文件由此取回）。改动未提交过，回退后无法再找回，故 UI 侧须先二次确认。
     */
    fun revertFile(path: String, staged: Boolean) =
        runAction(R.string.git_action_revert, { operation ->
            if (staged) repository.revertToHead(path, operation) else repository.revertWorktree(path, operation)
        })

    /** 回退全部未暂存改动，已暂存内容保持不动。 */
    fun revertAllUnstaged() = runAction(R.string.git_action_revert_all, { operation -> repository.revertAllWorktree(operation) })

    /** 删除未跟踪的文件或目录。这些文件从未提交过，删除后 git 无法恢复。 */
    fun deleteUntracked(path: String) =
        runAction(R.string.git_action_delete_file, { operation -> repository.deleteUntracked(path, operation) })

    /**
     * 展开/收起一个折叠的未跟踪目录（`git status` 把新目录显示成 `dir/` 一行）。展开时按需查询目录下
     * 未跟踪文件，使其可单独查看差异或删除。不置 busy——只读查询不该阻塞写操作。
     */
    fun toggleUntrackedDir(dir: String) {
        val operation = captureUiOperation() ?: return
        if (dir in _state.value.untrackedDirFiles) {
            publish(operation) { it.copy(untrackedDirFiles = it.untrackedDirFiles - dir) }
            return
        }
        if (_state.value.untrackedDirLoading != null) return
        publish(operation) { it.copy(untrackedDirLoading = dir) }
        launchOperation(operation) {
            val files = try {
                repository.untrackedFilesIn(dir, operation)
            } catch (e: Exception) {
                if (e is CancellationException || e is GitOperationExpiredException || e is LocalRuntimeAuthorizationRequired) throw e
                FileLogger.e(TAG, "加载未跟踪目录失败: $dir", e)
                emptyList()
            }
            publish(operation) {
                it.copy(untrackedDirFiles = it.untrackedDirFiles + (dir to files), untrackedDirLoading = null)
            }
        }
    }
    fun commit(message: String, amend: Boolean = false) =
        runAction(R.string.git_action_commit, { operation -> repository.commit(message, amend, operation) })

    /**
     * 调用 AI 根据暂存差异生成提交信息。
     */
    fun generateCommitMessage(onResult: (String) -> Unit) {
        val operation = captureUiOperation() ?: return
        if (_state.value.generatingCommitMessage) return
        publish(operation) { it.copy(generatingCommitMessage = true) }
        launchOperation(operation) {
            try {
                val diff = repository.diffForCommitMessage(operation)
                if (diff.isBlank()) {
                    publish(operation) { it.copy(generatingCommitMessage = false, toast = context.getString(R.string.git_toast_no_diff_for_commit)) }
                    return@launchOperation
                }
                val message = agentWorkflow.generateCommitMessage(diff)
                publish(operation) { it.copy(generatingCommitMessage = false) }
                if (!message.isNullOrBlank()) {
                    onResult(message)
                } else {
                    publish(operation) { it.copy(toast = context.getString(R.string.git_toast_generate_commit_failed)) }
                }
            } catch (e: Exception) {
                if (e is CancellationException || e is GitOperationExpiredException || e is LocalRuntimeAuthorizationRequired) throw e
                FileLogger.e(TAG, "生成提交信息异常", e)
                publish(operation) { it.copy(generatingCommitMessage = false, toast = context.getString(R.string.git_toast_generate_commit_failed)) }
            }
        }
    }
    /** 在当前工作区执行 `git init` 初始化仓库；成功后 runAction 末尾自动刷新（notARepo 翻 false）。 */
    fun initRepo() = runAction(R.string.git_action_init, { operation -> repository.initRepo(operation) })

    /** 显示仓库选择弹窗（列出工作区发现的 git 仓库）。 */
    fun showRepoPicker() = _state.update { it.copy(repoPickerVisible = true) }

    /** 关闭仓库选择弹窗。 */
    fun hideRepoPicker() = _state.update { it.copy(repoPickerVisible = false) }

    /**
     * 切换到指定路径的 git 仓库（手动指定或从发现列表选择）。
     * 先校验目标路径是 git 仓库，通过后设置当前仓库路径并刷新。
     */
    fun switchRepo(path: String) {
        val operation = captureUiOperation() ?: return
        if (_state.value.busy) return
        publish(operation) { it.copy(repoPickerVisible = false) }
        launchOperation(operation) {
            try {
                if (!repository.isGitRepo(path, operation)) {
                    publish(operation) { it.copy(toast = context.getString(R.string.git_toast_not_repo)) }
                    return@launchOperation
                }
                repository.setRepoPath(path, operation)
                refresh()
            } catch (e: Exception) {
                if (e is CancellationException || e is GitOperationExpiredException || e is LocalRuntimeAuthorizationRequired) throw e
                FileLogger.e(TAG, "切换仓库失败", e)
                publish(operation) { it.copy(toast = context.getString(R.string.git_toast_switch_repo_failed, e.message)) }
            }
        }
    }

    /** 在当前工作区克隆远程仓库；成功后 runAction 末尾自动刷新进仓库态。 */
    fun cloneRepo(url: String) = runAction(R.string.git_action_clone, { operation -> repository.cloneRepo(url, operation) })
    fun pull() {
        val operation = captureUiOperation() ?: return
        if (!_state.value.hasRemote) {
            publish(operation) { it.copy(toast = context.getString(R.string.git_toast_no_remote_pull)) }
            return
        }
        runAction(R.string.git_pull, { captured -> repository.pull(captured) }, operation)
    }
    fun push(onPromptRenameMaster: () -> Unit = {}) {
        val operation = captureUiOperation() ?: return
        if (!_state.value.hasRemote) {
            publish(operation) { it.copy(toast = context.getString(R.string.git_toast_no_remote_push)) }
            return
        }
        launchOperation(operation) {
            if (repository.isFirstPushOfMaster(operation)) {
                operation.checkCurrent()
                pendingPushOperation = operation
                onPromptRenameMaster()
            } else {
                runAction(R.string.git_push, { captured -> repository.push(captured) }, operation)
            }
        }
    }

    /** 用户在首次推送 master 时选择重命名为 main 并推送。 */
    fun pushRenameMasterToMain() {
        val operation = pendingPushOperation ?: return
        pendingPushOperation = null
        runAction(R.string.git_push, { captured -> repository.renameMasterToMainAndPush(captured) }, operation)
    }

    /** 用户在首次推送 master 时选择保留 master 直接推送。 */
    fun pushDirectly() {
        val operation = pendingPushOperation ?: captureUiOperation() ?: return
        pendingPushOperation = null
        runAction(R.string.git_push, { captured -> repository.push(captured) }, operation)
    }

    /**
     * 打开某条提交的详情弹层。若文件清单尚未加载则懒加载（不置 [GitUiState.busy]，
     * 因为这是只读查看，不应阻塞 status/branches 的写操作）。
     */
    fun openCommitDetail(hash: String) {
        val operation = captureUiOperation() ?: return
        val current = _state.value
        if (current.commitDetailHash == hash) return
        publish(operation) { it.copy(commitDetailHash = hash) }
        if (hash in current.commitFiles) return
        publish(operation) { it.copy(loadingCommit = hash) }
        launchOperation(operation) {
            val files = try {
                repository.commitFiles(hash, operation)
            } catch (e: Exception) {
                if (e is CancellationException || e is GitOperationExpiredException || e is LocalRuntimeAuthorizationRequired) throw e
                FileLogger.e(TAG, "加载提交文件失败: $hash", e)
                emptyList()
            }
            publish(operation) {
                it.copy(commitFiles = it.commitFiles + (hash to files), loadingCommit = null)
            }
        }
    }

    fun closeCommitDetail() = _state.update { it.copy(commitDetailHash = null) }

    fun consumeToast() = _state.update { it.copy(toast = null) }

    /**
     * 分页加载更旧的提交。UI 滚到底时调用：取已加载提交作为锚点，[repository.graphAppend] 用
     * `--skip` 取下一页并整体重算泳道布局，返回完整图直接替换 state.graph。不置 busy，避免阻塞写操作。
     * 守卫：正在加载或无更多时直接返回。失败 toast 提示且保留 hasMore 允许重试。
     */
    fun loadMoreCommits() {
        val operation = captureUiOperation() ?: return
        val current = _state.value
        if (current.graphLoadingMore || !current.graph.hasMore) return
        publish(operation) { it.copy(graphLoadingMore = true) }
        launchOperation(operation) {
            try {
                val graph = repository.graphAppend(current.graph.commits, current.graph.refs, operation = operation)
                val commits = graph.toGitCommits()
                publish(operation) { it.copy(graph = graph, commits = commits, graphLoadingMore = false) }
            } catch (e: Exception) {
                if (e is CancellationException || e is GitOperationExpiredException || e is LocalRuntimeAuthorizationRequired) throw e
                FileLogger.e(TAG, "加载更多提交失败", e)
                publish(operation) { it.copy(graphLoadingMore = false, toast = context.getString(R.string.git_toast_load_more_failed, e.message)) }
            }
        }
    }

    /**
     * 切换到指定分支或标签。成功后刷新全量状态。
     */
    fun checkoutBranch(ref: String, isRemote: Boolean = false) {
        val operation = captureUiOperation() ?: return
        if (_state.value.busy || _state.value.checkoutLoading != null) return
        publish(operation) { it.copy(checkoutLoading = ref, toast = null) }
        launchOperation(operation) {
            val msg = try {
                repository.checkout(ref, isRemote, operation)
                context.getString(R.string.git_toast_checkout_success, ref)
            } catch (e: Exception) {
                if (e is CancellationException || e is GitOperationExpiredException || e is LocalRuntimeAuthorizationRequired) throw e
                FileLogger.e(TAG, "切换分支失败", e)
                val reason = (e as? GitCommandFailureException)?.output ?: e.message
                context.getString(R.string.git_toast_checkout_failed, GitErrorMessage.friendly(reason ?: ""))
            }
            try {
                if (repository.isRepo(operation)) {
                    val snap = loadSnapshot(operation, includeIdentity = false)
                    val commits = snap.graph.toGitCommits()
                    publish(operation) { it.copy(checkoutLoading = null, status = snap.status, commits = commits, graph = snap.graph, hasRemote = snap.hasRemote, notARepo = false, toast = msg) }
                    // 切换分支后分支列表可能变化，若已加载过则刷新。
                    refreshBranchesIfLoaded(operation)
                } else {
                    publish(operation) { it.copy(checkoutLoading = null, notARepo = true, toast = msg) }
                }
            } catch (e: Exception) {
                if (e is CancellationException || e is GitOperationExpiredException || e is LocalRuntimeAuthorizationRequired) throw e
                publish(operation) { it.copy(checkoutLoading = null, toast = context.getString(R.string.git_toast_action_refresh_failed, msg)) }
            }
        }
    }

    /**
     * 创建新分支。name 为新分支名；startPoint 为基准分支（null/空 → 从当前 HEAD）；
     * checkout=true 则创建并切换。复用 runAction：置 busy → 跑命令 → 刷新 → toast。
     */
    fun createBranch(name: String, startPoint: String?, checkout: Boolean) {
        if (name.isBlank()) return
        runAction(R.string.git_action_create_branch, { operation -> repository.createBranch(name, startPoint, checkout, operation) })
    }

    /**
     * 安全删除本地分支（git branch -d）。未合并或为当前分支时 git 报错，经 runAction toast 透出。
     */
    fun deleteBranch(name: String) {
        if (name.isBlank()) return
        runAction(R.string.git_action_delete_branch, { operation -> repository.deleteBranch(name, operation) })
    }

    /**
     * 删除远程分支（git push --delete）。会改远端，失败经 runAction toast 透出。
     */
    fun deleteRemoteBranch(ref: String) {
        if (ref.isBlank()) return
        runAction(R.string.git_action_delete_remote_branch, { operation -> repository.deleteRemoteBranch(ref, operation) })
    }

    /**
     * 重命名本地分支（git branch -m）。当前分支也可重命名。失败经 runAction toast 透出。
     */
    fun renameBranch(oldName: String, newName: String) {
        if (oldName.isBlank() || newName.isBlank()) return
        runAction(R.string.git_action_rename_branch, { operation -> repository.renameBranch(oldName, newName, operation) })
    }

    /**
     * 创建轻量标签（git tag <name>），指向当前 HEAD 或指定提交。失败经 runAction toast 透出。
     */
    fun createTag(name: String, commitHash: String? = null) {
        if (name.isBlank()) return
        runAction(R.string.git_action_create_tag, { operation -> repository.createTag(name, commitHash, operation) })
    }

    /**
     * 重置当前分支到指定提交。
     */
    fun resetToCommit(commitHash: String, mode: String = "mixed") {
        if (commitHash.isBlank()) return
        runAction(R.string.git_reset_to_commit, { operation -> repository.resetToCommit(commitHash, mode, operation) })
    }

    /**
     * 添加规则到 .gitignore。
     */
    fun addToGitignore(pattern: String) {
        if (pattern.isBlank()) return
        runAction(R.string.git_add_to_gitignore, { operation -> repository.addToGitignore(pattern, operation) })
    }

    /**
     * 删除本地标签（git tag -d）。失败经 runAction toast 透出。
     */
    fun deleteTag(name: String) {
        if (name.isBlank()) return
        runAction(R.string.git_action_delete_tag, { operation -> repository.deleteTag(name, operation) })
    }

    /**
     * 合并分支到当前分支。
     */
    fun mergeBranch(branch: String, noFf: Boolean = false) {
        if (branch.isBlank()) return
        runAction(R.string.git_action_merge, { operation -> repository.mergeBranch(branch, noFf, operation) })
    }

    /**
     * 中止当前进行中的合并。
     */
    fun abortMerge() {
        runAction(R.string.git_action_abort_merge, { operation -> repository.abortMerge(operation) })
    }

    /**
     * 加载储藏列表。
     */
    fun loadStashes() {
        val operation = captureUiOperation() ?: return
        loadStashes(operation)
    }

    private fun loadStashes(operation: FrozenGitOperation) {
        launchOperation(operation) {
            publish(operation) { it.copy(stashesLoading = true) }
            val list = repository.stashList(operation)
            publish(operation) { it.copy(stashes = list, stashesLoading = false) }
        }
    }

    /**
     * 储藏工作区改动。
     */
    fun stashSave(message: String? = null, includeUntracked: Boolean = false) {
        runAction(R.string.git_action_stash, { operation -> repository.stashSave(message, includeUntracked, operation) }) { operation ->
            loadStashes(operation)
        }
    }

    /**
     * 弹出指定储藏（应用并删除）。
     */
    fun stashPop(index: String = "stash@{0}") {
        runAction(R.string.git_stash_pop, { operation -> repository.stashPop(index, operation) }) { operation ->
            loadStashes(operation)
        }
    }

    /**
     * 应用指定储藏（保留记录）。
     */
    fun stashApply(index: String = "stash@{0}") {
        runAction(R.string.git_stash_apply, { operation -> repository.stashApply(index, operation) }) { operation ->
            loadStashes(operation)
        }
    }

    /**
     * 丢弃指定储藏。
     */
    fun stashDrop(index: String) {
        runAction(R.string.git_stash_drop, { operation -> repository.stashDrop(index, operation) }) { operation ->
            loadStashes(operation)
        }
    }

    /**
     * 清空全部储藏记录。
     */
    fun stashClear() {
        runAction(R.string.git_stash_clear, { operation -> repository.stashClear(operation) }) { operation ->
            loadStashes(operation)
        }
    }

    /**
     * 退出 diff 视图，清空 diff 状态。
     */
    fun clearDiff() = _state.update { it.copy(diffVisible = false, diffPath = null, diffData = null) }

    /**
     * 加载某次提交中某文件的差异（改动前 vs 改动后）。
 * hash 为提交 hash，path 为文件路径。后台线程算 diff + 高亮，完成后填 diffData。
 */
    fun loadCommitFileDiff(hash: String, path: String) {
        loadDiff(path, "${hash}^", hash) { operation, ref, file -> repository.showFileContent(ref, file, operation) }
    }

    /**
     * 加载工作区某文件的未暂存差异（HEAD vs 工作区当前内容）。
 * path 为文件路径。用 showFileContent("HEAD", path) 取版本库快照，用 worktreeFileContent(path) 取工作区当前内容。
 */
    fun loadWorktreeDiff(path: String) {
        loadDiff(path, "HEAD", REF_WORKTREE) { operation, ref, p ->
            if (ref == REF_WORKTREE) repository.worktreeFileContent(p, operation)
            else repository.showFileContent(ref, p, operation)
        }
    }

    /** 加载某文件的暂存区差异（HEAD vs index）。 */
    fun loadStagedDiff(path: String) {
        loadDiff(path, "HEAD", REF_INDEX) { operation, ref, p ->
            if (ref == REF_INDEX) repository.indexFileContent(p, operation)
            else repository.showFileContent(ref, p, operation)
        }
    }

    /**
     * 加载未跟踪文件的差异：版本库里没有任何版本，旧侧恒为空，整个文件按全新增呈现。
     */
    fun loadUntrackedDiff(path: String) {
        loadDiff(path, REF_EMPTY, REF_WORKTREE) { operation, ref, p ->
            if (ref == REF_WORKTREE) repository.worktreeFileContent(p, operation) else ""
        }
    }

    private fun loadDiff(
        path: String,
        oldRef: String,
        newRef: String,
        contentProvider: suspend (FrozenGitOperation, String, String) -> String
    ) {
        val operation = captureUiOperation() ?: return
        openDiff(path)
        launchOperation(operation) {
            val data = runCatching {
                computeDiff(path, oldRef, newRef) { ref, file -> contentProvider(operation, ref, file) }
            }.getOrElse { e ->
                if (e is CancellationException || e is GitOperationExpiredException || e is LocalRuntimeAuthorizationRequired) throw e
                FileLogger.e(TAG, "加载 diff 失败: $path", e)
                null
            }
            operation.checkCurrent()
            finishDiff(data)
        }
    }

    /** 进入 diff 全屏页（页内加载中），已有 diff 页打开时忽略。 */
    private fun openDiff(path: String) {
        if (_state.value.diffVisible) return
        _state.update { it.copy(diffVisible = true, diffPath = path, diffData = null) }
    }

    /** diff 计算完成：成功填数据，失败关闭 diff 页并 toast。 */
    private fun finishDiff(data: DiffData?) {
        if (data == null) {
            _state.update { it.copy(diffVisible = false, diffData = null, toast = context.getString(R.string.git_toast_diff_failed)) }
        } else {
            _state.update { it.copy(diffData = data) }
        }
    }

    /**
     * 取旧/新两份文件内容，用 [LineDiff] 算行级差异，对每行做语法高亮，组装成 [DiffData]。
 * 二进制文件（含 NUL 字节）或超大文件（任一侧超 [MAX_DIFF_LINES]）降级处理。
 * [contentProvider] 负责按 ref 取文件内容，供提交 diff 和工作区 diff 复用。
 */
    private suspend fun computeDiff(
        path: String,
        oldRef: String,
        newRef: String,
        contentProvider: suspend (String, String) -> String
    ): DiffData = withContext(Dispatchers.Default) {
        val oldContent: String
        val newContent: String
        try {
            oldContent = contentProvider(oldRef, path)
            newContent = contentProvider(newRef, path)
        } catch (e: GitOutputTooLargeException) {
            FileLogger.i(TAG, "文件过大，跳过 diff 内容: $path")
            return@withContext DiffData(path, oldRef, newRef, emptyList(), 0, 0, isLarge = true)
        }

        // 二进制检测：git show 对二进制文件返回乱码，直接看是否含 NUL。
        if (oldContent.contains('\u0000') || newContent.contains('\u0000')) {
            return@withContext DiffData(path, oldRef, newRef, emptyList(), 0, 0, isBinary = true)
        }

        val oldLines = oldContent.split('\n')
        val newLines = newContent.split('\n')
        if (maxOf(oldLines.size, newLines.size) > MAX_DIFF_LINES ||
            oldLines.any { it.length > MAX_DIFF_LINE_LENGTH } ||
            newLines.any { it.length > MAX_DIFF_LINE_LENGTH }
        ) {
            return@withContext DiffData(path, oldRef, newRef, emptyList(), 0, 0, isLarge = true)
        }

        val diffLines = LineDiff.diff(oldContent, newContent)
        val language = inferSyntaxLanguage(path)

        // 对旧/新文件整体各跑一次高亮，拿到全文的 token 区间；渲染时按行偏移截取对应 SpanStyle。
        // 语法主题跟随当前 UI 主题，避免深色模式下 light 主题的 token 色（如 = 和 {}）看不清。
        // 两侧并行算（高亮是 diff 耗时主体），整体已在 Default 线程池，不碰主线程。
        val darkMode = currentDarkMode()
        val (oldHighlighted, newHighlighted) = coroutineScope {
            val old = async { highlightCode(oldContent, language, darkMode) }
            val new = async { highlightCode(newContent, language, darkMode) }
            old.await() to new.await()
        }

        // 构建行号 + 高亮截取。oldLineOffsets/newLineOffsets 为每行在全文中的起始偏移。
        val oldOffsets = lineOffsets(oldContent)
        val newOffsets = lineOffsets(newContent)

        var oldNum = 0
        var newNum = 0
        val rows = diffLines.map { line ->
            val (oldN, newN) = when (line.type) {
                LineDiff.LineType.CONTEXT -> { oldNum++; newNum++; oldNum to newNum }
                LineDiff.LineType.REMOVE -> { oldNum++; oldNum to null }
                LineDiff.LineType.ADD -> { newNum++; null to newNum }
            }
            // 截取该行在高亮全文中的 SpanStyle。行索引 = 行号 - 1（0-based）。
            val highlighted = when (line.type) {
                LineDiff.LineType.REMOVE -> sliceLineHighlight(oldHighlighted, oldOffsets, oldN?.let { it - 1 })
                LineDiff.LineType.ADD -> sliceLineHighlight(newHighlighted, newOffsets, newN?.let { it - 1 })
                LineDiff.LineType.CONTEXT -> sliceLineHighlight(oldHighlighted, oldOffsets, oldN?.let { it - 1 })
            }
            DiffRow(line.type, line.text, highlighted, oldN, newN)
        }

        val added = rows.count { it.type == LineDiff.LineType.ADD }
        val removed = rows.count { it.type == LineDiff.LineType.REMOVE }
        return@withContext DiffData(path, oldRef, newRef, rows, added, removed)
    }

    /**
     * 当前实际深色模式：AUTO 跟随系统，与 MainActivity 里 AIEditorTheme 的 darkTheme 判定保持一致。
     */
    private suspend fun currentDarkMode(): Boolean {
        val themeMode = themeSettings.themeModeFlow.first()
        val systemDark = (context.resources.configuration.uiMode and Configuration.UI_MODE_NIGHT_MASK) ==
            Configuration.UI_MODE_NIGHT_YES
        return when (themeMode) {
            AppThemeMode.AUTO -> systemDark
            AppThemeMode.DARK -> true
            AppThemeMode.LIGHT -> false
        }
    }

    /**
     * 从全文高亮 AnnotatedString 中截取某行的 SpanStyle，返回只含该行文本+样式的新 AnnotatedString。
 * lineIndex 超出范围或高亮为 null 时返回 null（降级纯文本）。
 */
    private fun sliceLineHighlight(
        highlighted: androidx.compose.ui.text.AnnotatedString?,
        offsets: IntArray,
        lineIndex: Int?
    ): androidx.compose.ui.text.AnnotatedString? {
        if (highlighted == null || lineIndex == null || lineIndex < 0 || lineIndex >= offsets.size) return null
        val start = offsets[lineIndex]
        val end = if (lineIndex + 1 < offsets.size) offsets[lineIndex + 1] - 1 else highlighted.length
        if (start >= highlighted.length || end <= start) return null
        return highlighted.subSequence(start, end)
    }

    /** 计算每行在全文中的起始偏移（含末行后的虚拟偏移 = 全文长度）。 */
    private fun lineOffsets(text: String): IntArray {
        val lines = text.split('\n')
        val offsets = IntArray(lines.size + 1)
        var pos = 0
        for (i in lines.indices) {
            offsets[i] = pos
            pos += lines[i].length + 1 // +1 为换行符
        }
        offsets[lines.size] = pos
        return offsets
    }
}
