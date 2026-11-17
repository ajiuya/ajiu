package com.aicode.feature.agent.presentation.component

import androidx.compose.runtime.staticCompositionLocalOf
import com.aicode.core.ui.WeightedLruCache
import com.aicode.feature.agent.presentation.AgentUIMessage
import com.aicode.feature.agent.presentation.BACKGROUND_NOTIFICATION_PREFIX
import com.aicode.feature.agent.presentation.MessageRole
import java.util.Locale
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull

internal val LocalRepresentedSubAgentIds = staticCompositionLocalOf<Set<String>> { emptySet() }

internal const val SUB_AGENT_CAPSULE_CONTENT_TYPE = "subagent-interaction"
internal const val SUB_AGENT_METADATA_MAX_CHARS = 2_097_152
internal const val SUB_AGENT_METADATA_MAX_ITEMS = 256
private const val SUB_AGENT_METADATA_MAX_DEPTH = 256

internal data class SubAgentTimelineSourceRef(
    val sessionId: String?,
    val messageId: String,
    // 通知保留原数组/区块序号；create/send 使用负序号。
    val ordinal: Int,
)

internal enum class SubAgentTimelineReferenceKind { DISPATCH, INSTRUCTION, NOTIFICATION }

internal data class SubAgentTimelineReference(
    val subSessionId: String,
    val sourceRef: SubAgentTimelineSourceRef,
    val kind: SubAgentTimelineReferenceKind,
    val title: String = "",
    val fallbackStatus: String? = null,
    val fallbackAgentName: String? = null,
)

internal data class SubAgentTimelineCapsule(
    val subSessionId: String,
    val anchorMessageId: String?,
    val title: String = "",
    val fallbackStatus: String? = null,
    val fallbackAgentName: String? = null,
) {
    val key: String get() = "subagent:$subSessionId"
}

internal data class SubAgentTimelineProjection(
    val references: List<SubAgentTimelineReference> = emptyList(),
    val capsules: List<SubAgentTimelineCapsule> = emptyList(),
) {
    val representedChildIds: Set<String> = capsules.mapTo(linkedSetOf()) { it.subSessionId }
}

internal fun projectSubAgentTimeline(
    messages: List<AgentUIMessage>,
    knownChildIds: Set<String>,
    sessionId: String? = null,
    activeChildIds: Set<String> = emptySet(),
): SubAgentTimelineProjection = SubAgentTimelineCache().project(messages, knownChildIds, sessionId, activeChildIds)

internal class SubAgentTimelineCache(
    private val notificationParser: (String, Boolean) -> List<SubAgentNotificationMetadata> = ::parseSubAgentNotificationMetadata,
) {
    private var sessionId: String? = null
    private var knownChildIds: Set<String> = emptySet()
    private var activeChildIds: Set<String> = emptySet()
    private val carriers = HashMap<String, CachedSubAgentCarrier>()
    private var projection = SubAgentTimelineProjection()

    @Synchronized
    fun project(
        messages: List<AgentUIMessage>,
        knownChildIds: Set<String>,
        sessionId: String? = null,
        activeChildIds: Set<String> = emptySet(),
        checkpoint: () -> Unit = {},
    ): SubAgentTimelineProjection {
        checkpoint()
        if (this.sessionId != sessionId || this.knownChildIds != knownChildIds) {
            carriers.clear()
            projection = SubAgentTimelineProjection()
            this.sessionId = sessionId
            this.knownChildIds = knownChildIds.toSet()
            this.activeChildIds = emptySet()
        }
        val ownedIds = knownChildIds - setOfNotNull(sessionId)
        val activeOwnedIds = activeChildIds.intersect(ownedIds)
        val retainedIds = HashSet<String>()
        val references = ArrayList<SubAgentTimelineReference>()
        if (ownedIds.isNotEmpty()) for (message in messages) {
            checkpoint()
            if (!message.isBackgroundNotification && message.role != MessageRole.TOOL) continue
            retainedIds += message.id
            val cached = carriers[message.id]?.takeIf { it.reuseFor(message) }
                ?: CachedSubAgentCarrier(message, projectSubAgentCarrierReferences(message, ownedIds, sessionId, notificationParser))
                    .also { carriers[message.id] = it }
            references += cached.references
        }
        checkpoint()
        carriers.keys.retainAll(retainedIds)
        if (references == projection.references && activeOwnedIds == this.activeChildIds) return projection
        this.activeChildIds = activeOwnedIds
        projection = buildSubAgentTimelineProjection(references, activeOwnedIds)
        return projection
    }
}

private class CachedSubAgentCarrier(
    message: AgentUIMessage,
    val references: List<SubAgentTimelineReference>,
) {
    private val content = message.content
    private val toolArgs = message.toolArgs
    private val role = message.role
    private val toolName = message.toolName
    private val isError = message.isError
    private val isBackgroundNotification = message.isBackgroundNotification

    fun reuseFor(message: AgentUIMessage): Boolean =
        content == message.content && toolArgs == message.toolArgs && role == message.role &&
            toolName == message.toolName && isError == message.isError &&
            isBackgroundNotification == message.isBackgroundNotification
}

private fun projectSubAgentCarrierReferences(
    message: AgentUIMessage,
    ownedIds: Set<String>,
    sessionId: String?,
    notificationParser: (String, Boolean) -> List<SubAgentNotificationMetadata>,
): List<SubAgentTimelineReference> = buildList {
    if (message.role == MessageRole.TOOL) addAll(projectSubAgentTaskReferences(message, ownedIds, sessionId))
    for (notice in notificationParser(message.content, !message.isBackgroundNotification)) {
        val childId = notice.subSessionId?.takeIf { it in ownedIds } ?: continue
        add(SubAgentTimelineReference(
            subSessionId = childId,
            sourceRef = SubAgentTimelineSourceRef(sessionId, message.id, notice.ordinal),
            kind = SubAgentTimelineReferenceKind.NOTIFICATION,
            title = notice.title,
            fallbackStatus = notice.status,
        ))
    }
}

private fun buildSubAgentTimelineProjection(
    references: List<SubAgentTimelineReference>,
    activeOwnedIds: Set<String>,
): SubAgentTimelineProjection {
    val latestByChild = linkedMapOf<String, SubAgentTimelineReference>()
    val dispatchNamesByChild = HashMap<String, String>()
    references.forEach { reference ->
        latestByChild[reference.subSessionId] = reference
        if (reference.kind == SubAgentTimelineReferenceKind.DISPATCH) {
            val name = reference.fallbackAgentName ?: return@forEach
            when (subAgentDisplayIdentity(name, reference.subSessionId).kind) {
                SubAgentIdentityKind.NAMED -> dispatchNamesByChild[reference.subSessionId] = name
                SubAgentIdentityKind.GENERIC -> dispatchNamesByChild.putIfAbsent(reference.subSessionId, name)
                SubAgentIdentityKind.UNKNOWN -> Unit
            }
        }
    }
    val capsules = latestByChild.values.mapTo(ArrayList()) { reference ->
        SubAgentTimelineCapsule(
            subSessionId = reference.subSessionId,
            anchorMessageId = reference.sourceRef.messageId,
            title = reference.title,
            fallbackStatus = reference.fallbackStatus,
            fallbackAgentName = dispatchNamesByChild[reference.subSessionId],
        )
    }
    (activeOwnedIds - latestByChild.keys).sorted().forEach { childId ->
        capsules += SubAgentTimelineCapsule(subSessionId = childId, anchorMessageId = null)
    }
    return SubAgentTimelineProjection(references, capsules)
}

internal data class SubAgentTurnCapsule(
    val turnKey: String,
    val capsule: SubAgentTimelineCapsule,
    val carrierMessageIds: Set<String>,
    val sessionId: String?,
) {
    val key: String get() = "subagent-turn:${sessionId.orEmpty().length}:${sessionId.orEmpty()}:${turnKey.length}:$turnKey:${capsule.subSessionId}"
}

internal fun projectSubAgentTurnCapsules(
    timeline: SubAgentTimelineProjection,
    turnKeys: Map<String, String>,
    sessionId: String?,
): List<SubAgentTurnCapsule> {
    val callsByTurnAndChild = linkedMapOf<Pair<String, String>, MutableList<SubAgentTimelineReference>>()
    val firstTurnByChild = HashMap<String, String>()
    val dispatchNamesByChild = HashMap<String, String>()
    for (reference in timeline.references) {
        if (reference.sourceRef.sessionId != sessionId || reference.kind == SubAgentTimelineReferenceKind.NOTIFICATION) continue
        if (reference.kind == SubAgentTimelineReferenceKind.DISPATCH) reference.fallbackAgentName?.let { name ->
            when (subAgentDisplayIdentity(name, reference.subSessionId).kind) {
                SubAgentIdentityKind.NAMED -> dispatchNamesByChild[reference.subSessionId] = name
                SubAgentIdentityKind.GENERIC -> dispatchNamesByChild.putIfAbsent(reference.subSessionId, name)
                SubAgentIdentityKind.UNKNOWN -> Unit
            }
        }
        val turnKey = turnKeys[reference.sourceRef.messageId] ?: continue
        callsByTurnAndChild.getOrPut(turnKey to reference.subSessionId) { ArrayList() } += reference
        firstTurnByChild.putIfAbsent(reference.subSessionId, turnKey)
    }
    val noticeCarriersByTurnAndChild = HashMap<Pair<String, String>, MutableSet<String>>()
    val latestNoticeStatusByChild = HashMap<String, String>()
    for (notice in timeline.references) {
        if (notice.sourceRef.sessionId != sessionId || notice.kind != SubAgentTimelineReferenceKind.NOTIFICATION) continue
        val noticeTurn = turnKeys[notice.sourceRef.messageId] ?: continue
        val childId = notice.subSessionId
        notice.fallbackStatus?.takeIf { it.isNotBlank() }?.let { latestNoticeStatusByChild[childId] = it }
        val sameTurn = noticeTurn to childId
        val target = if (sameTurn in callsByTurnAndChild) sameTurn
            else (firstTurnByChild[childId] ?: continue) to childId
        noticeCarriersByTurnAndChild.getOrPut(target) { linkedSetOf() } += notice.sourceRef.messageId
    }
    return callsByTurnAndChild.map { (identity, references) ->
        val (turnKey, childId) = identity
        val latestCall = references.last()
        SubAgentTurnCapsule(
            turnKey = turnKey,
            capsule = SubAgentTimelineCapsule(
                subSessionId = childId,
                anchorMessageId = latestCall.sourceRef.messageId,
                title = latestCall.title.ifBlank { references.firstNotNullOfOrNull { it.title.takeIf(String::isNotBlank) }.orEmpty() },
                fallbackStatus = latestNoticeStatusByChild[childId] ?: latestCall.fallbackStatus,
                fallbackAgentName = dispatchNamesByChild[childId],
            ),
            carrierMessageIds = references.mapTo(linkedSetOf()) { it.sourceRef.messageId }.apply {
                addAll(noticeCarriersByTurnAndChild[identity].orEmpty())
            },
            sessionId = sessionId,
        )
    }
}

internal fun projectSubAgentTaskReferences(
    message: AgentUIMessage,
    ownedIds: Set<String>,
    sessionId: String?,
): List<SubAgentTimelineReference> {
    if (message.role != MessageRole.TOOL || message.toolName != "task" ||
        message.isError || message.isToolRunning(null)
    ) return emptyList()
    if (message.content.length > SUB_AGENT_METADATA_MAX_CHARS ||
        (message.toolArgs?.length ?: 0) > SUB_AGENT_METADATA_MAX_CHARS
    ) return emptyList()
    val transport = parseMetadataObject(message.content.withoutToolStatusPrefix()) ?: return emptyList()
    if (transport.string("status") !in setOf("success", "partial")) return emptyList()
    // 缺失/损坏的参数不能被误认为省略 action 的真实 create。
    val args = message.toolArgs?.let(::parseMetadataObject)?.takeIf(::validSubAgentTaskArgs) ?: return emptyList()
    val action = if ("action" in args) args.string("action")?.trim()?.lowercase(Locale.ROOT) else "create"
    if (action == "send") {
        val childId = args.string("id")?.takeIf { it.isNotBlank() && it in ownedIds } ?: return emptyList()
        val data = when (val value = transport["data"]) {
            is JsonObject -> value
            is JsonPrimitive -> value.takeIf { it.isString }?.contentOrNull?.let(::parseMetadataObject)
            else -> null
        }
        if (data?.containsKey("id") == true && data.string("id") != childId) return emptyList()
        return listOf(SubAgentTimelineReference(
            subSessionId = childId,
            sourceRef = SubAgentTimelineSourceRef(sessionId, message.id, -1),
            kind = SubAgentTimelineReferenceKind.INSTRUCTION,
        ))
    }
    if (action != "create") return emptyList()
    val data = when (val value = transport["data"]) {
        is JsonObject -> value
        is JsonPrimitive -> value.takeIf { it.isString }?.contentOrNull?.let(::parseMetadataObject)
        else -> null
    } ?: return emptyList()
    val batch = if ("subagents" in data) data["subagents"] as? JsonArray ?: return emptyList() else null
    if ((batch?.size ?: 0) > SUB_AGENT_METADATA_MAX_ITEMS) return emptyList()
    val resultItems = batch?.toList() ?: listOf(data)
    return resultItems.mapIndexedNotNull { index, element ->
        val item = element as? JsonObject ?: return@mapIndexedNotNull null
        if (!claimableSubAgentTaskResult(item)) return@mapIndexedNotNull null
        val childId = item.string("id")?.takeIf { it in ownedIds } ?: return@mapIndexedNotNull null
        val argTasks = args["tasks"] as? JsonArray
        val taskArgs = argTasks?.getOrNull(index) as? JsonObject ?: args
        val nameArgs = when {
            !argTasks.isNullOrEmpty() && (batch != null || argTasks.size == 1) ->
                argTasks.getOrNull(index) as? JsonObject
            argTasks.isNullOrEmpty() && resultItems.size == 1 -> args
            else -> null
        }
        val agentName = (if ("agent" in item) item.string("agent") else nameArgs?.string("agent"))
            ?.trim()?.takeIf { subAgentDisplayIdentity(it, childId).kind != SubAgentIdentityKind.UNKNOWN }
        val title = item.string("description")?.takeIf { it.isNotBlank() }
            ?: taskArgs.string("description")?.takeIf { it.isNotBlank() }
            ?: taskArgs.string("prompt")?.lineSequence()?.firstOrNull().orEmpty()
        SubAgentTimelineReference(
            subSessionId = childId,
            sourceRef = SubAgentTimelineSourceRef(sessionId, message.id, -(index + 1)),
            kind = SubAgentTimelineReferenceKind.DISPATCH,
            title = title,
            fallbackStatus = item.string("state"),
            fallbackAgentName = agentName,
        )
    }
}

private fun validSubAgentTaskArgs(args: JsonObject): Boolean {
    val textFields = listOf("action", "id", "description", "prompt", "agent", "tier", "message")
    if (textFields.any { key ->
            val value = args[key]
            value != null && value != JsonNull && (value !is JsonPrimitive || !value.isString)
        }
    ) return false
    if ("action" in args && args["action"] == JsonNull) return false
    if ("tasks" !in args) return true
    val tasks = args["tasks"] as? JsonArray ?: return false
    return tasks.size <= SUB_AGENT_METADATA_MAX_ITEMS && tasks.all { task ->
        task is JsonObject && textFields.all { key ->
            val value = task[key]
            value == null || value == JsonNull || value is JsonPrimitive && value.isString
        }
    }
}

private fun claimableSubAgentTaskResult(item: JsonObject): Boolean {
    if (item.string("id").isNullOrBlank()) return false
    if (item["error"] != null && item["error"] != JsonNull) return false
    fun recognized(key: String, allowed: Set<String>): Boolean {
        if (key !in item) return true
        return item.string(key)?.trim()?.lowercase(Locale.ROOT) in allowed
    }
    return recognized("status", setOf("success", "completed", "running", "created")) &&
        recognized("state", setOf("running", "completed", "queued", "created", "awaiting_confirmation"))
}

internal data class SubAgentNotificationMetadata(
    val ordinal: Int,
    val kind: String? = null,
    val subSessionId: String? = null,
    val title: String = "",
    val summary: String = "",
    val status: String? = null,
    val body: String = "",
    val raw: String,
) {
    val preview: String get() = summary.ifBlank { body.ifBlank { raw } }
}

internal fun unrepresentedSubAgentNotifications(
    notices: List<SubAgentNotificationMetadata>,
    representedIds: Set<String>,
): List<SubAgentNotificationMetadata> = notices.filterNot { it.subSessionId in representedIds }

private data class NotificationMetadataKey(val raw: String, val toolResult: Boolean)
private val notificationMetadataCache = WeightedLruCache<NotificationMetadataKey, List<SubAgentNotificationMetadata>>(
    128, 16L * 1024 * 1024,
) { key, notices ->
    256L + key.raw.length * 2L + notices.sumOf {
        256L + 2L * (it.raw.length.toLong() + it.title.length + it.summary.length + it.body.length +
            (it.kind?.length ?: 0) + (it.subSessionId?.length ?: 0) + (it.status?.length ?: 0))
    }
}

internal fun parseSubAgentNotificationMetadata(
    raw: String,
    toolResult: Boolean = false,
): List<SubAgentNotificationMetadata> {
    val key = NotificationMetadataKey(raw, toolResult)
    return notificationMetadataCache[key] ?: parseSubAgentNotificationMetadataUncached(raw, toolResult)
        .also { notificationMetadataCache.put(key, it) }
}

private fun parseSubAgentNotificationMetadataUncached(raw: String, toolResult: Boolean): List<SubAgentNotificationMetadata> {
    fun fallback(value: String = raw) = listOf(SubAgentNotificationMetadata(ordinal = 0, raw = value))
    if (raw.length > SUB_AGENT_METADATA_MAX_CHARS) {
        return if (toolResult && !raw.contains("\"notifications\"") &&
            !raw.contains(BACKGROUND_NOTIFICATION_PREFIX)
        ) emptyList() else fallback()
    }
    val content = if (toolResult) raw.withoutToolStatusPrefix() else raw.trim()
    if (content.startsWith("{")) {
        val obj = parseMetadataObject(content)
        if (obj != null && obj.containsKey("notifications")) {
            val entries = obj["notifications"] as? JsonArray ?: return fallback(obj["notifications"].toString())
            if (entries.size > SUB_AGENT_METADATA_MAX_ITEMS) return fallback(entries.toString())
            return entries.mapIndexed { index, element ->
                val notice = element as? JsonObject
                    ?: return@mapIndexed SubAgentNotificationMetadata(ordinal = index, raw = element.toString())
                val kind = notice.string("kind")
                val childId = when (kind) {
                    "subagent" -> notice.string("subagent_id")
                    "agent_message" -> notice.string("from_id")
                    else -> null
                }?.takeIf { it.isNotBlank() }
                SubAgentNotificationMetadata(
                    ordinal = index,
                    kind = kind,
                    subSessionId = childId,
                    title = notice.string("subagent_title") ?: notice.string("from_title").orEmpty(),
                    summary = notice.string("summary").orEmpty(),
                    status = notice.string("status"),
                    body = notice.string("message") ?: notice.string("result_excerpt").orEmpty(),
                    raw = element.toString(),
                )
            }
        }
        if (!content.contains(BACKGROUND_NOTIFICATION_PREFIX)) {
            return if (toolResult && !content.contains("\"notifications\"")) emptyList() else fallback()
        }
    }
    // 普通工具输出中的 XML 不是系统事件，只有注入器的固定前缀允许文本搭载通知。
    val marker = content.indexOf(BACKGROUND_NOTIFICATION_PREFIX)
    if (toolResult && marker < 0) return emptyList()
    val xml = if (marker >= 0) content.substring(marker + BACKGROUND_NOTIFICATION_PREFIX.length) else content
    val blocks = notificationBlockRegex.findAll(xml).take(SUB_AGENT_METADATA_MAX_ITEMS + 1).toList()
    if (blocks.size > SUB_AGENT_METADATA_MAX_ITEMS) return fallback(xml)
    if (blocks.isEmpty()) return fallback(xml)
    val notices = ArrayList<SubAgentNotificationMetadata>()
    var cursor = 0
    fun retainMalformedSegment(segment: String) {
        if (segment.isNotBlank() && (marker < 0 || '<' in segment || '>' in segment)) {
            notices += SubAgentNotificationMetadata(ordinal = notices.size, raw = segment.trim())
        }
    }
    for (block in blocks) {
        retainMalformedSegment(xml.substring(cursor, block.range.first))
        val tag = block.groupValues[1]
        val body = block.groupValues[2]
        val fieldMatches = notificationBlockRegex.findAll(body).take(SUB_AGENT_METADATA_MAX_ITEMS + 1).toList()
        val fields = fieldMatches.associate { field ->
            field.groupValues[1] to unescapeNotificationXml(field.groupValues[2]).trim()
        }
        var fieldEnd = 0
        val completeFields = fieldMatches.size <= SUB_AGENT_METADATA_MAX_ITEMS && fieldMatches.all { field ->
            val gapIsBlank = body.substring(fieldEnd, field.range.first).isBlank()
            fieldEnd = field.range.last + 1
            gapIsBlank && '<' !in field.groupValues[2] && '>' !in field.groupValues[2]
        } && body.substring(fieldEnd).isBlank() && fields.size == fieldMatches.size
        val kind = when (tag) {
            "subagent-notification" -> "subagent"
            "agent-message" -> "agent_message"
            "task-notification" -> "background_task"
            "user-message" -> "user_message"
            else -> null
        }
        val childId = when (kind) {
            "subagent" -> fields["subagent-id"]
            "agent_message" -> fields["from-id"]
            else -> null
        }?.takeIf { completeFields && it.isNotBlank() }
        notices += SubAgentNotificationMetadata(
            ordinal = notices.size,
            kind = kind,
            subSessionId = childId,
            title = fields["subagent-title"] ?: fields["from-title"].orEmpty(),
            summary = fields["summary"].orEmpty(),
            status = fields["status"],
            body = fields["message"] ?: fields["result-excerpt"].orEmpty(),
            raw = block.value,
        )
        cursor = block.range.last + 1
    }
    retainMalformedSegment(xml.substring(cursor))
    return if (notices.size > SUB_AGENT_METADATA_MAX_ITEMS) fallback(xml) else notices
}

private val notificationBlockRegex = Regex(
    """<([A-Za-z][A-Za-z0-9_-]*)\s*>(.*?)</\1\s*>""",
    RegexOption.DOT_MATCHES_ALL,
)
private val xmlEntityRegex = Regex("&(?:amp|lt|gt|quot|apos|#\\d{1,7}|#x[0-9a-fA-F]{1,6});")

private fun unescapeNotificationXml(text: String): String = xmlEntityRegex.replace(text) { match ->
    when (match.value) {
        "&amp;" -> "&"
        "&lt;" -> "<"
        "&gt;" -> ">"
        "&quot;" -> "\""
        "&apos;" -> "'"
        else -> {
            val value = match.value.removePrefix("&#").removeSuffix(";")
            val code = if (value.startsWith('x')) value.drop(1).toIntOrNull(16) else value.toIntOrNull()
            code?.takeIf { Character.isValidCodePoint(it) && it !in 0xD800..0xDFFF }
                ?.let { String(Character.toChars(it)) } ?: match.value
        }
    }
}

private fun parseMetadataObject(raw: String): JsonObject? {
    if (raw.length > SUB_AGENT_METADATA_MAX_CHARS) return null
    var depth = 0
    var inString = false
    var escaped = false
    for (char in raw) {
        if (inString) {
            when {
                escaped -> escaped = false
                char == '\\' -> escaped = true
                char == '"' -> inString = false
            }
        } else when (char) {
            '"' -> inString = true
            '{', '[' -> if (++depth > SUB_AGENT_METADATA_MAX_DEPTH) return null
            '}', ']' -> depth--
        }
    }
    return runCatching { Json.parseToJsonElement(raw) as? JsonObject }.getOrNull()
}

private fun JsonObject.string(key: String): String? =
    (this[key] as? JsonPrimitive)?.takeIf { it.isString }?.contentOrNull
