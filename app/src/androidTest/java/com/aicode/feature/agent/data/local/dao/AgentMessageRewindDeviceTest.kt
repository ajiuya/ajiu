package com.aicode.feature.agent.data.local.dao

import android.content.Context
import com.aicode.testing.RoomVerificationInstrumentation

class AgentMessageRewindDeviceTest : AgentMessageRewindCases() {
    override val testContext: Context
        get() = RoomVerificationInstrumentation.requireTestContext()
}
