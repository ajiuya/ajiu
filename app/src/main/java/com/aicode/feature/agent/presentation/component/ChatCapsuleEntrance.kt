package com.aicode.feature.agent.presentation.component

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.AnimationVector1D
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.tween
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.layout.Layout
import androidx.compose.ui.unit.dp
import androidx.compose.runtime.remember
import com.aicode.feature.agent.presentation.MessageRole

internal const val CAPSULE_ENTRANCE_DURATION_MS = 420
private const val CAPSULE_ENTRANCE_OFFSET_DP = 9f
private const val CAPSULE_ENTRANCE_BACK_OVERSHOOT = 1.15f

internal fun capsuleEntranceEase(progress: Float): Float {
    val value = progress.coerceIn(0f, 1f)
    return 1f - (1f - value) * (1f - value) * (1f - value)
}

internal fun capsuleEntranceBack(progress: Float): Float {
    val value = progress.coerceIn(0f, 1f)
    val shifted = value - 1f
    val overshoot = CAPSULE_ENTRANCE_BACK_OVERSHOOT
    return 1f + (overshoot + 1f) * shifted * shifted * shifted + overshoot * shifted * shifted
}

@Composable
internal fun CapsuleEntrance(
    progress: Float,
    modifier: Modifier = Modifier,
    content: @Composable () -> Unit,
) {
    Layout(
        modifier = modifier.fillMaxWidth().clipToBounds(),
        content = { Box(Modifier.fillMaxWidth()) { content() } },
    ) { measurables, constraints ->
        val placeable = measurables.single().measure(constraints.copy(minHeight = 0))
        val eased = capsuleEntranceEase(progress)
        val height = (placeable.height * eased).toInt().coerceIn(constraints.minHeight, constraints.maxHeight)
        val back = capsuleEntranceBack(progress)
        val translationY = CAPSULE_ENTRANCE_OFFSET_DP.dp.toPx() * (1f - back)
        val width = placeable.width.coerceIn(constraints.minWidth, constraints.maxWidth)
        layout(width, height) {
            placeable.placeWithLayer(0, 0) {
                alpha = eased
                this.translationY = translationY
            }
        }
    }
}

internal class ChatCapsuleEntranceTracker {
    private var sessionId: String? = null
    private var initialized = false
    private val knownMessageIds = linkedSetOf<String>()
    private val pendingMessageIds = linkedSetOf<String>()
    private val seenCapsuleKeys = linkedSetOf<String>()

    fun update(
        currentSessionId: String?,
        ready: Boolean,
        allowAnimation: Boolean,
        messageIds: List<String>,
        projection: SubAgentGroupProjection,
    ): Set<String> {
        if (!ready) return emptySet()
        if (!initialized || sessionId != currentSessionId) {
            sessionId = currentSessionId
            initialized = true
            knownMessageIds.clear()
            knownMessageIds.addAll(messageIds)
            pendingMessageIds.clear()
            seenCapsuleKeys.clear()
            projection.items.filter(::isCapsule).mapTo(seenCapsuleKeys) { it.key }
            return emptySet()
        }

        val known = knownMessageIds.toList()
        val appended = messageIds.size >= known.size && messageIds.take(known.size) == known
        if (appended && allowAnimation) {
            messageIds.asSequence().filterNot(knownMessageIds::contains).forEach(pendingMessageIds::add)
        } else {
            pendingMessageIds.clear()
        }
        knownMessageIds.clear()
        knownMessageIds.addAll(messageIds)

        val groupsByKey = projection.groups.associateBy { it.key }
        val newlyEligible = if (!allowAnimation) emptySet() else buildSet {
            projection.items.asSequence()
                .filter { it.contentType == MessageRole.TOOL.name }
                .forEach { item ->
                    if (item.message.id in pendingMessageIds && seenCapsuleKeys.add(item.key)) add(item.key)
                }
            projection.items.asSequence()
                .filter { it.contentType == SUB_AGENT_GROUP_CONTENT_TYPE && it.key !in seenCapsuleKeys }
                .forEach { item ->
                    val carrierIds = groupsByKey[item.key]?.carrierMessageIds.orEmpty()
                    if (carrierIds.any(pendingMessageIds::contains) && seenCapsuleKeys.add(item.key)) add(item.key)
                }
        }

        val representedIds = buildSet {
            projection.items.forEach { item ->
                add(item.message.id)
                addAll(item.subAgentCarrierMessageIds)
            }
            projection.groups.forEach { addAll(it.carrierMessageIds) }
        }
        pendingMessageIds.removeAll(representedIds)
        projection.items.filter(::isCapsule).mapTo(seenCapsuleKeys) { it.key }
        return newlyEligible
    }

    private fun isCapsule(item: ChatRenderItem): Boolean =
        item.contentType == MessageRole.TOOL.name || item.contentType == SUB_AGENT_GROUP_CONTENT_TYPE
}
