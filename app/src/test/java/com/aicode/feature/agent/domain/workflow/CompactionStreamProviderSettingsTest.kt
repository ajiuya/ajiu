package com.aicode.feature.agent.domain.workflow

import com.aicode.feature.agent.data.local.dao.AgentMessageDao
import com.aicode.feature.agent.data.local.dao.LlmCallRecordDao
import com.aicode.feature.agent.data.remote.openai.OpenAIApi
import com.aicode.feature.agent.domain.model.AgentMessage
import com.aicode.feature.agent.domain.prompt.SystemPromptProvider
import com.aicode.feature.agent.domain.provider.AIProvider
import com.aicode.feature.agent.domain.provider.AnthropicAdapter
import com.aicode.feature.agent.domain.provider.GeminiAdapter
import com.aicode.feature.agent.domain.provider.KeySwitchOutcome
import com.aicode.feature.agent.domain.provider.OpenAIAdapter
import com.aicode.feature.agent.domain.session.MessagePersistenceUseCase
import com.aicode.feature.settings.data.remote.ModelMetadataService
import com.aicode.feature.settings.data.repository.GeneralSettingsRepository
import com.aicode.feature.settings.domain.model.ModelMetadata
import io.mockk.coEvery
import io.mockk.every
import io.mockk.mockk
import java.util.concurrent.atomic.AtomicInteger
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.async
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.test.runTest
import okhttp3.Request
import okhttp3.ResponseBody
import okhttp3.ResponseBody.Companion.toResponseBody
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test
import retrofit2.Call
import retrofit2.Callback
import retrofit2.Response

class CompactionStreamProviderSettingsTest {
    private val switcher: suspend (Throwable, String, Set<String>) -> KeySwitchOutcome? = { _, _, _ -> null }

    private fun provider(api: OpenAIApi, initialCap: Int?) = OpenAIAdapter(api).apply {
        model = "deepseek-v4-pro"
        maxOutputTokens = 32_768
        requestOutputTokenLimit = initialCap
        maxNetworkRetries = 6
        keySwitcher = switcher
        firstByteTimeoutMs = 300_000
        streamIdleTimeoutMs = 0
    }

    private fun assertApplied(provider: AIProvider, output: Int, policy: CompactionStreamPolicy) {
        assertEquals(output, provider.maxOutputTokens)
        assertEquals(output, provider.requestOutputTokenLimit)
        assertEquals(0, provider.maxNetworkRetries)
        assertNull(provider.keySwitcher)
        assertEquals(policy.firstChunkTimeoutMs, provider.firstByteTimeoutMs)
        assertEquals(policy.idleTimeoutMs, provider.streamIdleTimeoutMs)
    }

    private fun assertRestored(provider: AIProvider, initialCap: Int?) {
        assertEquals(32_768, provider.maxOutputTokens)
        assertEquals(initialCap, provider.requestOutputTokenLimit)
        assertEquals(6, provider.maxNetworkRetries)
        assertSame(switcher, provider.keySwitcher)
        assertEquals(300_000L, provider.firstByteTimeoutMs)
        assertEquals(0L, provider.streamIdleTimeoutMs)
    }

    private fun compactor(): ContextCompactor {
        val metadata = mockk<ModelMetadataService>()
        val prompts = mockk<SystemPromptProvider>()
        val settings = mockk<GeneralSettingsRepository>()
        coEvery { metadata.resolve(any(), any(), any()) } returns ModelMetadata("deepseek-v4-pro", contextTokens = 200_000, outputTokens = 32_768)
        every { prompts.resolvePrompt(any()) } returns "Summarize {{INSTRUCTION}}"
        coEvery { settings.compactionThresholdPercent() } returns 90
        return ContextCompactor(
            mockk<AgentMessageDao>(relaxed = true), metadata, prompts,
            mockk<LlmCallRecordDao>(relaxed = true), settings,
            mockk<MessagePersistenceUseCase>(relaxed = true)
        ).apply { streamPolicy = CompactionStreamPolicy(retryDelayMs = 0) }
    }

    private fun history() = listOf(
        AgentMessage.UserMessage(id = "history", content = "汉".repeat(20_000)),
        AgentMessage.AssistantMessage(id = "answer", content = "done"),
        AgentMessage.UserMessage(id = "goal", content = "continue")
    )

    private fun streamCall(onCancel: () -> Unit = {}, onEnqueue: ((Call<ResponseBody>, Callback<ResponseBody>) -> Unit)? = null): Call<ResponseBody> {
        val call = mockk<Call<ResponseBody>>()
        every { call.enqueue(any()) } answers {
            val callback = firstArg<Callback<ResponseBody>>()
            if (onEnqueue != null) {
                onEnqueue(call, callback)
            } else {
                callback.onResponse(call, Response.success("data: {\"choices\":[{\"delta\":{\"content\":\"summary\"},\"finish_reason\":\"stop\"}]}\n\ndata: [DONE]\n\n".toResponseBody()))
            }
        }
        every { call.cancel() } answers { onCancel() }
        every { call.isCanceled } returns false
        every { call.request() } returns Request.Builder().url("https://fixture.invalid/").build()
        return call
    }

    @Test
    fun repeatedApplyRestoresCapturedCapRatherThanMetadataOrLastAppliedLimit() {
        for (initialCap in listOf(null, 24_576)) {
            val provider = provider(mockk(), initialCap)
            val settings = CompactionStreamProviderSettings(provider)
            val policy = CompactionStreamPolicy(firstChunkTimeoutMs = 50_000, idleTimeoutMs = 60_000)
            assertEquals(32_768, settings.outputLimit)
            settings.apply(8_192, policy)
            assertApplied(provider, 8_192, policy)
            settings.apply(2_048, policy)
            assertApplied(provider, 2_048, policy)
            settings.restore()
            assertRestored(provider, initialCap)
            settings.restore()
            assertRestored(provider, initialCap)
        }
    }

    @Test
    fun otherProvidersKeepExistingMetadataOutputLimitBehaviorAndDefaultNoOpCap() {
        for (provider in listOf(AnthropicAdapter(mockk()), GeminiAdapter(mockk()))) {
            provider.maxOutputTokens = 32_768
            provider.requestOutputTokenLimit = 24_576
            assertNull(provider.requestOutputTokenLimit)
            val settings = CompactionStreamProviderSettings(provider)
            settings.apply(8_192, CompactionStreamPolicy())
            assertEquals(8_192, provider.maxOutputTokens)
            assertNull(provider.requestOutputTokenLimit)
            settings.restore()
            assertEquals(32_768, provider.maxOutputTokens)
            assertNull(provider.requestOutputTokenLimit)
        }
    }

    @Test
    fun successfulCompactionRestoresNullAndExistingExplicitCap() = runTest {
        for (initialCap in listOf(null, 24_576)) {
            val api = mockk<OpenAIApi>()
            val provider = provider(api, initialCap)
            val compactor = compactor()
            val calls = AtomicInteger()
            every { api.streamChatCompletion(any(), any(), any(), any()) } answers {
                assertApplied(provider, 8_192, compactor.streamPolicy)
                calls.incrementAndGet()
                streamCall()
            }
            assertTrue(compactor.compactIfNeeded(history(), provider, force = true).compacted)
            assertTrue(calls.get() > 0)
            assertRestored(provider, initialCap)
        }
    }

    @Test
    fun failedCompactionRestoresNullAndExistingExplicitCap() = runTest {
        for (initialCap in listOf(null, 24_576)) {
            val api = mockk<OpenAIApi>()
            val provider = provider(api, initialCap)
            val compactor = compactor()
            val calls = AtomicInteger()
            every { api.streamChatCompletion(any(), any(), any(), any()) } answers {
                assertApplied(provider, 8_192, compactor.streamPolicy)
                calls.incrementAndGet()
                throw IllegalStateException("fixture failure")
            }
            assertFalse(compactor.compactIfNeeded(history(), provider, force = true).compacted)
            assertEquals(1, calls.get())
            assertRestored(provider, initialCap)
        }
    }

    @Test
    fun cancelledCompactionCancelsProviderCallAndRestoresNullAndExistingExplicitCap() = runTest {
        for (initialCap in listOf(null, 24_576)) {
            val api = mockk<OpenAIApi>()
            val provider = provider(api, initialCap)
            val compactor = compactor()
            val entered = CompletableDeferred<Unit>()
            val cancelled = CompletableDeferred<Unit>()
            every { api.streamChatCompletion(any(), any(), any(), any()) } answers {
                assertApplied(provider, 8_192, compactor.streamPolicy)
                streamCall(onCancel = { cancelled.complete(Unit) }) { _, _ -> entered.complete(Unit) }
            }
            val job = async { compactor.compactIfNeeded(history(), provider, force = true) }
            try {
                entered.await()
                job.cancelAndJoin()
                assertTrue(job.isCancelled)
                assertTrue(cancelled.isCompleted)
                assertRestored(provider, initialCap)
            } finally {
                job.cancelAndJoin()
            }
        }
    }
}
