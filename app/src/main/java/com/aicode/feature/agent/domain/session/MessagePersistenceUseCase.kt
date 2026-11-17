package com.aicode.feature.agent.domain.session

import com.aicode.feature.agent.data.local.dao.AgentMessageDao
import com.aicode.feature.agent.data.local.database.AgentDatabase
import com.aicode.feature.agent.data.local.entity.AgentMessageEntity
import com.aicode.feature.agent.domain.model.AgentMessage
import com.aicode.feature.agent.domain.model.CONTEXT_COMPACTION_MARKER
import com.aicode.feature.agent.domain.model.CONTEXT_SUMMARY_LEGACY_PREFIX
import com.aicode.feature.agent.domain.model.ContextUsageBreakdown
import com.aicode.feature.agent.domain.tool.ToolCall
import com.aicode.feature.agent.presentation.AgentAttachment
import com.aicode.feature.agent.presentation.MessageRole
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import java.util.UUID
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class MessagePersistenceUseCase @Inject constructor(
    private val agentMessageDao: AgentMessageDao,
    private val agentDatabase: AgentDatabase,
    private val recoveryRepository: com.aicode.feature.agent.domain.recovery.RecoveryRepository? = null
) {
    private val json = Json { ignoreUnknownKeys = true }

    /** 消息表变更版本号，作为 [buildHistory] 缓存的失效信号。 */
    private val dbVersion = java.util.concurrent.atomic.AtomicLong(0)
    private val historyCache = WeightedLruCache<String, HistoryEntry>(8, 32L * 1024 * 1024)
    internal val cachedHistoryEntries: Int get() = synchronized(historyCache) { historyCache.size }
    internal val cachedHistoryBytes: Long get() = synchronized(historyCache) { historyCache.bytes }

    private class HistoryEntry(
        val version: Long,
        val pendingToolMarker: String,
        val messages: List<AgentMessage>
    )

    init {
        agentDatabase.invalidationTracker.addObserver(
            object : androidx.room.InvalidationTracker.Observer(arrayOf("agent_messages")) {
                override fun onInvalidated(tables: Set<String>) {
                    synchronized(historyCache) {
                        dbVersion.incrementAndGet()
                    }
                }
            }
        )
    }

    /** 内嵌图片 base64 的 LRU 缓存：key = path:size:lastModified，避免工具循环中
     *  每轮 LLM 调用都重读文件 + base64 编码。带条目与总字节双重上限。 */
    private class CachedImage(val mimeType: String, val base64: String)

    private val imageBase64Cache = WeightedLruCache<String, CachedImage>(MAX_IMAGE_CACHE_ENTRIES, MAX_IMAGE_CACHE_BYTES)
    internal val cachedImageEntries: Int get() = synchronized(imageBase64Cache) { imageBase64Cache.size }
    internal val cachedImageBytes: Long get() = synchronized(imageBase64Cache) { imageBase64Cache.bytes }

    private fun cachedImageBase64(key: String): CachedImage? = synchronized(imageBase64Cache) { imageBase64Cache[key] }

    private fun cacheImageBase64(key: String, value: CachedImage, version: Long) {
        synchronized(imageBase64Cache) {
            if (dbVersion.get() != version) return
            imageBase64Cache.put(key, value, 128L + (key.length.toLong() + value.mimeType.length + value.base64.length) * 2)
        }
    }

    // 单调递增时间戳：保证同毫秒内多次落库的顺序稳定（assistant 永远在其 tool 结果之前）。
    @Volatile
    private var lastTimestamp = 0L

    @Synchronized
    fun nextTimestamp(): Long {
        val now = System.currentTimeMillis()
        val ts = if (now > lastTimestamp) now else lastTimestamp + 1
        lastTimestamp = ts
        return ts
    }

    suspend fun persist(
        sessionId: String,
        role: MessageRole,
        content: String,
        id: String = UUID.randomUUID().toString(),
        toolCalls: List<ToolCall> = emptyList(),
        toolCallId: String? = null,
        toolName: String? = null,
        toolArgs: String? = null,
        isError: Boolean = false,
        reasoning: String? = null,
        signature: String? = null,
        thinkingBlocksJson: String? = null,
        attachments: List<AgentAttachment> = emptyList(),
        inputTokens: Int = 0,
        outputTokens: Int = 0,
        cachedInputTokens: Int = 0,
        isCompacted: Boolean = false,
        isContextExcluded: Boolean = false,
        contextInputTokens: Int? = null,
        contextInputBudget: Int? = null,
        contextEstimated: Boolean = true,
        contextBreakdown: ContextUsageBreakdown? = null,
        toolActionTitle: String? = null,
        toolStartedAtMs: Long? = null,
        toolDurationMs: Long? = null,
        turnDurationMs: Long? = null,
        requireSession: Boolean = false,
        recoveryRunId: String? = null,
        recoveryStep: com.aicode.feature.agent.domain.recovery.RecoveryStep? = null,
        recoveryPlaceholder: Boolean = false,
        recoverySupplemental: Boolean = false,
        continuationOfMessageId: String? = null,
        notificationSeqs: List<Long> = emptyList()
    ) {
        val message = AgentMessageEntity(
            id = id,
            sessionId = sessionId,
            role = role.name,
            content = sanitizeContent(content),
            timestamp = nextTimestamp(),
            toolCallsJson = if (toolCalls.isNotEmpty()) capBytes(json.encodeToString(toolCalls), MAX_SNAPSHOT_BYTES) else null,
            toolCallId = toolCallId,
            toolName = toolName,
            toolArgs = toolArgs?.let { capBytes(it, MAX_TOOL_ARGS_BYTES) },
            isError = isError,
            reasoning = reasoning?.let { sanitizeContent(it) },
            signature = signature?.let { capBytes(it, MAX_SNAPSHOT_BYTES) },
            thinkingBlocksJson = thinkingBlocksJson?.let { capBytes(it, MAX_SNAPSHOT_BYTES) },
            attachmentsJson = if (attachments.isNotEmpty()) capBytes(json.encodeToString(attachments), MAX_ATTACHMENTS_BYTES) else null,
            inputTokens = inputTokens,
            outputTokens = outputTokens,
            cachedInputTokens = cachedInputTokens,
            isCompacted = isCompacted,
            isContextExcluded = isContextExcluded,
            contextInputTokens = contextInputTokens,
            contextInputBudget = contextInputBudget,
            contextEstimated = contextEstimated,
            contextBreakdownJson = contextBreakdown?.let { json.encodeToString(it) },
            toolActionTitle = toolActionTitle.takeIf { role == MessageRole.TOOL },
            toolStartedAtMs = toolStartedAtMs.takeIf { role == MessageRole.TOOL },
            toolDurationMs = toolDurationMs.takeIf { role == MessageRole.TOOL },
            turnDurationMs = turnDurationMs.takeIf { role == MessageRole.ASSISTANT },
            continuationOfMessageId = continuationOfMessageId
        )
        if (recoveryRunId != null) {
            val repository = checkNotNull(recoveryRepository)
            val committed = when (role) {
                MessageRole.USER -> if (recoverySupplemental) repository.commitSupplementalInput(recoveryRunId, message)
                    else repository.commitInput(recoveryRunId, message, notificationSeqs)
                MessageRole.ASSISTANT -> repository.commitAssistant(recoveryRunId, requireNotNull(recoveryStep), message)
                MessageRole.TOOL -> if (recoveryPlaceholder) repository.commitToolPlaceholder(recoveryRunId, message)
                    else repository.commitToolResult(recoveryRunId, message, notificationSeqs)
            }
            if (!committed) throw CancellationException("Recovery message owner is no longer current")
            invalidateHistory(sessionId)
            return
        }
        if (requireSession) {
            agentMessageDao.insertMessageIfSessionExists(message)
            return
        }
        if (role != MessageRole.TOOL) {
            val existing = if (role == MessageRole.USER) agentMessageDao.getMessageById(message.id) else null
            agentMessageDao.insert(if (existing?.sessionId == message.sessionId && existing.role == message.role)
                message.copy(modelInputSnapshotJson = existing.modelInputSnapshotJson ?: message.modelInputSnapshotJson) else message)
            return
        }
        agentMessageDao.insertToolMessagePreservingMetadata(message)
    }

    suspend fun loadAttachmentImages(attachments: List<AgentAttachment>): List<com.aicode.feature.agent.domain.model.AgentImage> =
        withContext(Dispatchers.IO) { attachments.mapNotNull { it.toAgentImage(mutableMapOf()) } }

    suspend fun prepareModelInput(context: com.aicode.feature.agent.domain.model.AgentContext, content: String): List<AgentMessage> {
        val candidate = com.aicode.feature.agent.domain.model.ModelInputSnapshot.prepare(context, content)
        val encoded = json.encodeToString(candidate)
        require(encoded.toByteArray(Charsets.UTF_8).size <= MAX_SNAPSHOT_BYTES) { "Model input snapshot is too large" }
        val canonical = if (context.recoveryRunId != null) {
            checkNotNull(recoveryRepository).freezeInputSnapshot(context.recoveryRunId, encoded, context.inputMessageId)
        } else agentMessageDao.freezeModelInputSnapshot(requireNotNull(context.sessionId), context.inputMessageId, encoded)
        if (canonical == null) throw CancellationException("Model input owner is no longer available")
        val snapshot = json.decodeFromString<com.aicode.feature.agent.domain.model.ModelInputSnapshot>(canonical)
        check(snapshot.version == 1)
        invalidateHistory(requireNotNull(context.sessionId))
        return snapshot.messages(context.inputMessageId, context.inputImages, persisted = !context.transientInput)
    }

    suspend fun updateToolTiming(sessionId: String, callId: String, startedAtMs: Long?, durationMs: Long?) {
        agentMessageDao.updateToolTiming(sessionId, callId, startedAtMs, durationMs)
    }

    suspend fun updateTurnDuration(sessionId: String, messageId: String, durationMs: Long) {
        agentMessageDao.updateTurnDuration(sessionId, messageId, durationMs)
    }

    fun invalidateHistory(sessionId: String) {
        synchronized(historyCache) {
            dbVersion.incrementAndGet()
            historyCache.remove(sessionId)
        }
    }

    fun releaseSessionCache(sessionId: String) {
        invalidateHistory(sessionId)
        synchronized(imageBase64Cache) { imageBase64Cache.clear() }
    }

    suspend fun rewindConversation(sessionId: String, cutoff: Long) {
        releaseSessionCache(sessionId)
        try {
            agentMessageDao.rewindConversation(sessionId, cutoff)
        } finally {
            releaseSessionCache(sessionId)
        }
    }

    suspend fun updateContent(messageId: String, newContent: String) {
        agentMessageDao.updateMessageContent(messageId, sanitizeContent(newContent))
    }

    companion object {
        /**
         * 单条消息各文本字段的持久化上限（UTF-8 字节数）。远小于 SQLite CursorWindow 单窗口约 2MB
         * 的硬限制，防止超大内容撑爆数据行导致读取消息时崩溃。
         *
         * 按字节而非字符设限：中文等文本单字符最多占 3 字节，字符数上限约束不住真实占用。
         * 也不能只限制单个字段——同一条消息可同时带正文、思考、工具入参等多份大快照，
         * 各字段上限之和（约 720KB）必须整体留在窗口大小之下，否则该行可能因窗口预填充
         * 而无处安放，读取时抛 IllegalStateException「Couldn't read row N, col 0 from CursorWindow」。
         */
        const val MAX_CONTENT_BYTES = 150_000
        const val MAX_SNAPSHOT_BYTES = 100_000
        const val MAX_ATTACHMENTS_BYTES = 20_000
        /** 工具入参上限：与正文同量级，长参数（如大段文件内容）能完整落库、由 UI 限高滚动查看。 */
        const val MAX_TOOL_ARGS_BYTES = 150_000
        const val IMAGE_OMITTED_MARKER = "[图片已省略：内嵌图片数据过大]"
        const val CONTENT_TRUNCATED_MARKER = "…[内容过长，已截断]"
        /** 图片 base64 缓存条目上限。 */
        private const val MAX_IMAGE_CACHE_ENTRIES = 12
        /** 图片 base64 缓存总字节上限（base64 为原始大小的 ~4/3，48MB 约可存 36MB 原始图片）。 */
        private const val MAX_IMAGE_CACHE_BYTES = 48L * 1024 * 1024

        /** 内嵌 base64 图片 data URL（`data:image/...;base64,...`）。 */
        private val INLINE_BASE64_IMAGE = Regex("""data:image/[a-zA-Z0-9.+-]+;base64,[A-Za-z0-9+/=\r\n]+""")

        internal fun orderHistoryEntities(entities: List<AgentMessageEntity>): List<AgentMessageEntity> {
            // 聊天按压缩触发时间展示；模型必须先读摘要，再读保留历史。
            val (compaction, retained) = entities.partition { it.isCompactionMarker || it.isContextSummary }
            return compaction + retained
        }

        /** UTF-8 单字符最多占 3 字节，故 [String.length] 的三倍不超上限时无需实际编码即可放行。 */
        private fun definitelyFits(raw: String, maxBytes: Int): Boolean =
            raw.length.toLong() * 3 <= maxBytes

        /**
         * 落库前的内容净化，为所有 provider/模型提供统一兜底防线：
         * 1. 剥离内嵌的 base64 图片 data URL（替换为占位说明），此类内容本不该进数据库文本；
         * 2. 剥离后仍超长的内容按 UTF-8 字节截断到 [MAX_CONTENT_BYTES]，避免任何超大行触发 CursorWindow 崩溃。
         */
        internal fun sanitizeContent(raw: String): String {
            if (definitelyFits(raw, MAX_CONTENT_BYTES) && !raw.contains("data:image/", ignoreCase = true)) {
                return raw
            }
            val stripped = INLINE_BASE64_IMAGE.replace(raw, IMAGE_OMITTED_MARKER)
            val capped = capBytes(stripped, MAX_CONTENT_BYTES)
            return if (capped.length < stripped.length) capped + CONTENT_TRUNCATED_MARKER else capped
        }

        /**
         * 落库前对 JSON 快照字段（toolCallsJson / thinkingBlocksJson / attachmentsJson）与
         * 思考签名等非展示文本按 UTF-8 字节截断。截断后 JSON 不再可解析，读取方经 runCatching
         * 降级为「无工具调用 / 无思考快照 / 无附件」，而非崩溃；不带截断标记，避免给解析方徒增无意义内容。
         * 截断按码点边界进行，不切出半个代理对。
         */
        internal fun capBytes(raw: String, maxBytes: Int): String {
            if (definitelyFits(raw, maxBytes)) return raw
            var used = 0
            var i = 0
            while (i < raw.length) {
                val c = raw[i]
                val isPair = c.isHighSurrogate() && i + 1 < raw.length && raw[i + 1].isLowSurrogate()
                val charBytes = when {
                    isPair -> 4
                    c.code < 0x80 -> 1
                    c.code < 0x800 -> 2
                    else -> 3
                }
                if (used + charBytes > maxBytes) break
                used += charBytes
                i += if (isPair) 2 else 1
            }
            return if (i >= raw.length) raw else raw.substring(0, i)
        }
    }

    /**
     * 从持久化的消息重建合法的上下文历史。
     * 关键：只保留「assistant 的 tool_call」与「tool 结果」能配对成功的部分，
     * 丢弃任何一方缺失的悬挂项，避免回放出现孤儿 tool_use / tool_result 违反 API 约束。
     * 已被上下文压缩标记的消息（isCompacted=true）不参与回放。
     */
    suspend fun buildHistory(sessionId: String, pendingToolMarker: String): List<AgentMessage> {
        val context = currentCoroutineContext()
        while (true) {
            val version = synchronized(historyCache) {
                context.ensureActive()
                val currentVersion = dbVersion.get()
                historyCache[sessionId]?.let { cached ->
                    if (cached.version == currentVersion && cached.pendingToolMarker == pendingToolMarker) {
                        return cached.messages
                    }
                    historyCache.remove(sessionId)
                }
                currentVersion
            }
            val pendingImages = mutableMapOf<String, CachedImage>()
            val messages = withContext(Dispatchers.IO) {
                buildHistoryUncached(sessionId, pendingToolMarker, pendingImages)
            }
            val weight = 128L + sessionId.length * 2L + pendingToolMarker.length * 2L + messages.sumOf(::messageWeight)
            synchronized(historyCache) {
                context.ensureActive()
                if (version == dbVersion.get()) {
                    // 图片与历史一起发布，失败、取消或失效的重建不会填回旧图片。
                    pendingImages.forEach { (key, image) -> cacheImageBase64(key, image, version) }
                    historyCache.put(sessionId, HistoryEntry(version, pendingToolMarker, messages), weight)
                    return messages
                }
            }
        }
    }

    private suspend fun buildHistoryUncached(
        sessionId: String,
        pendingToolMarker: String,
        pendingImages: MutableMap<String, CachedImage>
    ): List<AgentMessage> {
        val storedEntities = agentMessageDao.getMessagesBySessionOnce(sessionId)
        val positions = storedEntities.withIndex().associate { it.value.id to it.index }
        val entities = storedEntities.filter { !it.isCompacted && !it.isContextExcluded }
            .let { orderHistoryEntities(it) }

        val callsByMessage = storedEntities.filter { it.role == "ASSISTANT" }.associate { entity ->
            entity.id to entity.toolCallsJson?.let {
                runCatching { json.decodeFromString<List<ToolCall>>(it) }.getOrNull()
            }.orEmpty()
        }
        val declarationsById = storedEntities.flatMap { entity ->
            callsByMessage[entity.id].orEmpty().map { it to entity }
        }.groupBy { it.first.id }
        val resultsById = storedEntities.filter {
            it.role == "TOOL" && !it.toolCallId.isNullOrBlank() &&
                !it.content.startsWith(pendingToolMarker) &&
                !it.content.startsWith(SessionUseCase.LEGACY_PENDING_TOOL_MARKER)
        }.groupBy { it.toolCallId }
        val pairedResults = declarationsById.mapNotNull { (id, calls) ->
            val (call, declaring) = calls.singleOrNull() ?: return@mapNotNull null
            val completed = resultsById[id]?.singleOrNull() ?: return@mapNotNull null
            if (id.isBlank() || declaring.isCompacted || declaring.isContextExcluded ||
                completed.isCompacted || completed.isContextExcluded ||
                completed.toolName != null && completed.toolName != call.name ||
                positions.getValue(completed.id) <= positions.getValue(declaring.id)) null else id to completed
        }.toMap()

        // 迟到的工具结果必须紧邻原声明回放，数据库里的审计顺序保持不变。
        val result = mutableListOf<AgentMessage>()
        for (e in entities) {
            when (MessageRole.valueOf(e.role)) {
                MessageRole.USER -> {
                    val rawContent = if (e.isCompactionMarker) CONTEXT_COMPACTION_MARKER else e.content
                    val attachments = if (!e.isCompactionMarker) {
                        e.attachmentsJson?.let {
                            runCatching { json.decodeFromString<List<AgentAttachment>>(it) }.getOrNull()
                        } ?: emptyList()
                    } else emptyList()

                    val finalContent = if (attachments.isNotEmpty()) {
                        val attachmentText = buildString {
                            append("附件：")
                            attachments.forEach { att ->
                                append('\n')
                                append("- ")
                                append(att.fileName)
                                append("：")
                                append(att.containerPath)
                            }
                        }
                        if (rawContent.isBlank()) attachmentText else "${rawContent.trimEnd()}\n\n$attachmentText"
                    } else {
                        rawContent
                    }

                    val images = attachments.mapNotNull { it.toAgentImage(pendingImages) }

                    val snapshot = e.modelInputSnapshotJson?.let {
                        json.decodeFromString<com.aicode.feature.agent.domain.model.ModelInputSnapshot>(it).also { value ->
                            check(value.version == 1) { "Unsupported model input snapshot version" }
                        }
                    }
                    if (snapshot != null && !e.isCompactionMarker) {
                        result.addAll(snapshot.messages(e.id, images, persisted = true))
                    } else result.add(
                        AgentMessage.UserMessage(
                            id = e.id,
                            content = finalContent,
                            images = images,
                            isCompactionMarker = e.isCompactionMarker
                        )
                    )
                }
                MessageRole.ASSISTANT -> {
                    val toolCalls = callsByMessage[e.id].orEmpty().filter { it.id in pairedResults }
                    val imageAttachments = e.attachmentsJson?.let {
                        runCatching { json.decodeFromString<List<AgentAttachment>>(it) }.getOrNull()
                    }.orEmpty()
                    if (e.content.isNotBlank() || toolCalls.isNotEmpty() || imageAttachments.isNotEmpty()) {
                        val previous = result.lastOrNull()
                        if (
                            e.isContextSummary &&
                            !(previous is AgentMessage.UserMessage && previous.isCompactionMarker)
                        ) {
                            result.add(AgentMessage.UserMessage(
                                content = CONTEXT_COMPACTION_MARKER,
                                isCompactionMarker = true,
                                isTransient = true
                            ))
                        }
                        result.add(
                            AgentMessage.AssistantMessage(
                                id = e.id,
                                content = if (e.isContextSummary) e.content.removePrefix(CONTEXT_SUMMARY_LEGACY_PREFIX).trimStart() else e.content,
                                isContextSummary = e.isContextSummary,
                                toolCalls = toolCalls,
                                reasoning = e.reasoning ?: "",
                                signature = e.signature ?: "",
                                thinkingBlocksJson = e.thinkingBlocksJson ?: "",
                                inputTokens = e.inputTokens,
                                // 附件里的图片按路径重建 base64（带缓存），供下一轮上下文回放。
                                images = imageAttachments.mapNotNull { it.toAgentImage(pendingImages) }
                            )
                        )
                        for (call in toolCalls) {
                            val completed = pairedResults.getValue(call.id)
                            result.add(AgentMessage.ToolResultMessage(
                                id = call.id,
                                messageId = completed.id,
                                toolName = completed.toolName ?: "unknown",
                                result = completed.content
                            ))
                        }
                    }
                }
                MessageRole.TOOL -> Unit
            }
        }
        return result
    }

    private fun AgentAttachment.toAgentImage(
        pendingImages: MutableMap<String, CachedImage>
    ): com.aicode.feature.agent.domain.model.AgentImage? {
        if (!isImage || localPath.isBlank()) return null
        val file = java.io.File(localPath)
        if (!file.exists() || !file.isFile || file.length() <= 0) return null
        // 按路径+大小+修改时间缓存 base64：文件未变时直接复用，省去每次 LLM 调用的重读+编码。
        val key = "$localPath:${file.length()}:${file.lastModified()}"
        (pendingImages[key] ?: cachedImageBase64(key))?.let { cached ->
            return com.aicode.feature.agent.domain.model.AgentImage(
                mimeType = cached.mimeType,
                base64Data = cached.base64,
                path = containerPath
            )
        }
        return try {
            val (mime, base64) = com.aicode.core.util.ImageDownscaler.downscaleToBase64(
                file.readBytes(), mimeType.ifBlank { "image/jpeg" }
            )
            pendingImages[key] = CachedImage(mime, base64)
            com.aicode.feature.agent.domain.model.AgentImage(
                mimeType = mime,
                base64Data = base64,
                path = containerPath
            )
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            null
        }
    }
}
