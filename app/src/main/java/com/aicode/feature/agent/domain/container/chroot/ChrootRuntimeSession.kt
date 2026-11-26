package com.aicode.feature.agent.domain.container.chroot

import com.aicode.feature.agent.domain.root.RootAccessState

internal class ChrootRuntimeSession internal constructor(
    val id: String,
    val rootState: RootAccessState
) {
    override fun toString(): String = "ChrootRuntimeSession(generation=${rootState.generation})"
}
