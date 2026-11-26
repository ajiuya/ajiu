package com.aicode.feature.agent.domain.command

import android.content.Context
import com.aicode.R
import com.aicode.feature.agent.domain.container.runtime.LocalRuntimeAuthorizationRequired
import com.aicode.feature.agent.domain.skill.Skill
import com.aicode.feature.agent.domain.skill.SkillDefinitionSourceChanged
import com.aicode.feature.agent.domain.skill.SkillEntry
import com.aicode.feature.agent.domain.skill.SkillRepository
import com.aicode.feature.agent.domain.skill.SkillScope
import com.aicode.feature.workspace.domain.WorkspaceExecutionContext
import com.aicode.feature.workspace.domain.WorkspaceExecutionContextFactory
import com.aicode.feature.settings.data.repository.ExecutionMode
import io.mockk.Called
import io.mockk.coEvery
import io.mockk.coVerify
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.isActive
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.withContext
import java.io.IOException
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 斜杠命令注册表：菜单合并（内置命令在前 + 技能在后，去重排序）、发送时解析（含参数）与技能提示词构造。
 */
class SlashCommandRegistryTest {

    private val context = mockk<Context>()
    private val skillRepository = mockk<SkillRepository>()
    private val definitionExecution = WorkspaceExecutionContext(
        ExecutionMode.LOCAL_PROOT, "fixture", mockk(), null, null
    ) {}

    private fun action(cmdName: String, descRes: Int, takesArgs: Boolean = false) =
        object : ActionCommandHandler {
            override val name = cmdName
            override val descriptionRes = descRes
            override val acceptsArgs = takesArgs
            override fun execute(context: SlashCommandContext, args: String) = Unit
        }

    private fun skill(skillName: String, description: String, instructions: String = "body") =
        Skill(name = skillName, description = description, instructions = instructions)

    private val usage = action("usage", R.string.slash_command_usage_desc)
    private val compress = action("compress", R.string.slash_command_compress_desc)
    private val init = action("init", R.string.slash_command_init_desc)

    private fun registry(vararg handlers: ActionCommandHandler): SlashCommandRegistry {
        every { context.getString(R.string.slash_command_usage_desc) } returns "用量"
        every { context.getString(R.string.slash_command_compress_desc) } returns "压缩上下文"
        every { context.getString(R.string.slash_command_init_desc) } returns "初始化项目"
        val factory = mockk<WorkspaceExecutionContextFactory>()
        return SlashCommandRegistry(handlers.toSet(), skillRepository, context, factory)
    }

    private fun snapshot(source: String, skills: List<Skill>, disabled: Set<String> = emptySet()) =
        SkillRepository.DefinitionSnapshot(source, skills.map { SkillEntry(it, SkillScope.GLOBAL) }, disabled)

    private fun prepare(registry: SlashCommandRegistry, skills: List<Skill>) {
        every { skillRepository.sourceIdentity() } returns "fixture"
        every { skillRepository.checkSourceIdentity(any()) } returns Unit
        coEvery { skillRepository.readDefinitionSnapshot(any(), any()) } returns snapshot("fixture", skills)
        runBlocking { registry.refresh() }
    }

    @Test
    fun pendingChrootFactoryWithoutOwnerStillRequiresAuthorization() = runBlocking {
        val fixture = PendingChrootSkillMenuFixture()
        fixture.assertPendingWithoutExecutionOwner()

        val failure = runCatching { fixture.factory.freeze() }.exceptionOrNull()

        fixture.assertNoRuntimeOrFileAccessCalls()
        assertEquals(LocalRuntimeAuthorizationRequired::class.java, failure?.javaClass)
        assertNull(WorkspaceExecutionContext.current())
    }

    @Test
    fun pendingChrootRefreshPublishesBuiltinAndHostSkillWithoutRuntimeAuthorization() = runBlocking {
        val fixture = PendingChrootSkillMenuFixture()
        fixture.assertPendingWithoutExecutionOwner()

        val result = runCatching { fixture.registry.refresh() }

        fixture.assertNoRuntimeOrFileAccessCalls()
        result.exceptionOrNull()?.let { failure ->
            throw AssertionError("Refreshing the skill menu must succeed without a CHROOT execution owner", failure)
        }
        assertEquals(SlashCommandRegistry.RefreshState.Ready, fixture.registry.refreshState.value)
        assertEquals(listOf("usage", "host-skill"), fixture.registry.commands.value.map { it.name })
        assertEquals(listOf(SlashCommandKind.ACTION, SlashCommandKind.SKILL), fixture.registry.commands.value.map { it.kind })
        assertTrue(fixture.registry.resolve("/usage") is SlashCommandRegistry.ResolvedCommand.Action)
        val skill = fixture.registry.resolve("/host-skill args") as SlashCommandRegistry.ResolvedCommand.SkillCommand
        assertEquals("Fixture instructions for host-skill", skill.skill.instructions)
        assertEquals("args", skill.args)
        assertNull(fixture.registry.resolve("/disabled-fixture"))
        assertNull(WorkspaceExecutionContext.current())
        fixture.assertNoRuntimeOrFileAccessCalls()
    }

    // ---- 菜单合并 ----

    @Test
    fun commands_publishBuiltinsBeforeTheFirstRefreshWithoutReadingSkills() {
        val registry = registry(usage, compress, init)

        assertEquals(listOf("compress", "init", "usage"), registry.commands.value.map { it.name })
        assertTrue(registry.commands.value.all { it.kind == SlashCommandKind.ACTION })
        assertTrue(registry.resolve("/usage") is SlashCommandRegistry.ResolvedCommand.Action)
        assertEquals(SlashCommandRegistry.RefreshState.Idle, registry.refreshState.value)
        verify { skillRepository wasNot Called }
    }

    @Test
    fun commands_builtinsFirstThenSkills() {
        val registry = registry(usage, compress, init)
        prepare(registry, listOf(skill("pdf-report", "生成 PDF 报告"), skill("aaa", "最前")))

        // 内置命令一组（组内字母序）在前，技能一组（组内字母序）在后，两组不交叉。
        assertEquals(
            listOf("compress", "init", "usage", "aaa", "pdf-report"),
            registry.commands.value.map { it.name }
        )
    }

    @Test
    fun commands_builtinWinsOverSameNameSkill() {
        val registry = registry(usage)
        prepare(registry, listOf(skill("usage", "同名技能")))

        val matched = registry.commands.value.filter { it.name == "usage" }
        assertEquals(1, matched.size)
        assertEquals(SlashCommandKind.ACTION, matched.single().kind)
    }

    @Test
    fun commands_marksKindsAndArgs() {
        val registry = registry(usage)
        prepare(registry, listOf(skill("pdf-report", "生成 PDF")))

        val builtin = registry.commands.value.first { it.name == "usage" }
        assertEquals(SlashCommandKind.ACTION, builtin.kind)
        assertEquals(false, builtin.acceptsArgs)

        val skillCmd = registry.commands.value.first { it.name == "pdf-report" }
        assertEquals(SlashCommandKind.SKILL, skillCmd.kind)
        assertTrue(skillCmd.acceptsArgs)
    }

    // ---- 解析 ----

    @Test
    fun resolve_builtinExact() {
        val registry = registry(usage)
        prepare(registry, emptyList())

        val resolved = registry.resolve("/usage")
        assertTrue(resolved is SlashCommandRegistry.ResolvedCommand.Action)
        assertEquals("usage", resolved!!.name)
        assertEquals("", resolved.args)
    }

    @Test
    fun resolve_builtinWithArgsRejectedWhenArgsNotAccepted() {
        val registry = registry(usage)
        prepare(registry, emptyList())

        assertNull(registry.resolve("/usage now"))
    }

    @Test
    fun resolve_argAcceptingBuiltinKeepsArgs() {
        val registry = registry(action("echo", R.string.slash_command_usage_desc, takesArgs = true))
        prepare(registry, emptyList())

        val resolved = registry.resolve("/echo  hello world ")
        assertTrue(resolved is SlashCommandRegistry.ResolvedCommand.Action)
        assertEquals("hello world", resolved!!.args)
    }

    @Test
    fun resolve_skillCaseInsensitiveWithArgs() {
        val registry = registry()
        prepare(registry, listOf(skill("pdf-report", "生成 PDF", instructions = "make \$ARGUMENTS")))

        val resolved = registry.resolve("/PDF-Report a4")
        assertTrue(resolved is SlashCommandRegistry.ResolvedCommand.SkillCommand)
        assertEquals("pdf-report", resolved!!.name)
        assertEquals("a4", resolved.args)
    }

    @Test
    fun resolve_unknownOrNonSlash_returnsNull() {
        val registry = registry(usage)
        prepare(registry, listOf(skill("pdf-report", "生成 PDF")))

        assertNull(registry.resolve("/nope"))
        assertNull(registry.resolve("hello"))
        assertNull(registry.resolve("/"))
    }

    @Test
    fun changedEndpointClearsSkillCommandsButKeepsBuiltinActions() {
        val registry = registry(usage)
        prepare(registry, listOf(skill("remote", "old", "server-a instructions")))
        every { skillRepository.sourceIdentity() } returns "server-b:profile-b:uuid-b"
        assertNull(registry.resolve("/remote"))
        assertTrue(registry.resolve("/usage") is SlashCommandRegistry.ResolvedCommand.Action)
        assertEquals(listOf("usage"), registry.commands.value.map { it.name })
    }

    @Test
    fun oldRefreshCannotOverwriteNewEnvironmentOrItsInstructionBody() = runBlocking {
        val registry = registry(usage)
        prepare(registry, emptyList())
        val entered = CountDownLatch(1)
        val release = CountDownLatch(1)
        var source = "server-a"
        every { skillRepository.sourceIdentity() } answers { source }
        every { skillRepository.checkSourceIdentity(any()) } answers {
            if (firstArg<String>() != source) throw kotlinx.coroutines.CancellationException("changed")
        }
        coEvery { skillRepository.readDefinitionSnapshot(any(), any()) } coAnswers {
            withContext(Dispatchers.IO + definitionExecution) {
                assertSame(definitionExecution, WorkspaceExecutionContext.current())
                val capturedSource = source
                val skills = if (capturedSource == "server-a") {
                    entered.countDown()
                    check(release.await(10, TimeUnit.SECONDS))
                    listOf(skill("old", "server a", "old server body"))
                } else listOf(skill("new", "server b", "new server body"))
                snapshot(capturedSource, skills)
            }
        }
        val old = async(Dispatchers.Default) { registry.refresh() }
        try {
            assertTrue(withContext(Dispatchers.IO) { entered.await(10, TimeUnit.SECONDS) })
            source = "server-b"
            registry.refresh()
            release.countDown()
            old.await()
            assertNull(registry.resolve("/old"))
            val command = registry.resolve("/new") as SlashCommandRegistry.ResolvedCommand.SkillCommand
            assertEquals("new server body", command.skill.instructions)
            assertEquals(listOf("usage", "new"), registry.commands.value.map { it.name })
        } finally { release.countDown() }
    }

    @Test
    fun cancelledRefreshNeverPublishesAnEmptySkillList() = runBlocking {
        val registry = registry(usage)
        prepare(registry, listOf(skill("kept", "same source")))
        val cancelled = kotlinx.coroutines.CancellationException("cancelled")
        coEvery { skillRepository.readDefinitionSnapshot(any(), any()) } throws cancelled
        assertSame(cancelled, runCatching { registry.refresh() }.exceptionOrNull())
        assertTrue(registry.resolve("/kept") is SlashCommandRegistry.ResolvedCommand.SkillCommand)
        assertEquals(SlashCommandRegistry.RefreshState.Ready, registry.refreshState.value)
    }

    @Test
    fun cancellingActualRefreshCancelsFrozenBlockingDefinitionReader() = runBlocking {
        val registry = registry(usage)
        prepare(registry, emptyList())
        val entered = CompletableDeferred<Unit>()
        val closed = CompletableDeferred<Unit>()
        coEvery { skillRepository.readDefinitionSnapshot(any(), any()) } coAnswers {
            withContext(Dispatchers.IO + definitionExecution) {
                assertSame(definitionExecution, WorkspaceExecutionContext.current())
                runBlocking(WorkspaceExecutionContext.blockingContext()) {
                    entered.complete(Unit)
                    try { awaitCancellation() } finally { closed.complete(Unit) }
                }
            }
        }
        val refresh = async(Dispatchers.Default) { registry.refresh() }
        entered.await()
        refresh.cancelAndJoin()
        closed.await()
        assertEquals(listOf("usage"), registry.commands.value.map { it.name })
    }

    @Test
    fun ioFailureClearsUnreliableSkillsKeepsBuiltinsAndCanRetry() = runBlocking {
        val registry = registry(usage)
        prepare(registry, listOf(skill("stale", "old definition")))
        coEvery { skillRepository.readDefinitionSnapshot(any(), any()) } coAnswers {
            assertEquals(SlashCommandRegistry.RefreshState.Loading, registry.refreshState.value)
            throw IOException("Fixture definition read failed")
        }

        registry.refresh()

        assertEquals(SlashCommandRegistry.RefreshState.Failed, registry.refreshState.value)
        assertEquals(listOf("usage"), registry.commands.value.map { it.name })
        assertNull(registry.resolve("/stale"))
        assertTrue(registry.resolve("/usage") is SlashCommandRegistry.ResolvedCommand.Action)

        coEvery { skillRepository.readDefinitionSnapshot(any(), any()) } returns
            snapshot("fixture", listOf(skill("recovered", "fresh definition")))
        registry.refresh()

        assertEquals(SlashCommandRegistry.RefreshState.Ready, registry.refreshState.value)
        assertEquals(listOf("usage", "recovered"), registry.commands.value.map { it.name })
        assertTrue(registry.resolve("/recovered") is SlashCommandRegistry.ResolvedCommand.SkillCommand)
    }

    @Test
    fun sourceMetadataFailuresDropSkillsWithoutRereadingOrBreakingBuiltinResolution() {
        val registry = registry(usage)
        prepare(registry, listOf(skill("cached", "previous source")))
        every { skillRepository.sourceIdentity() } throws IOException("Fixture metadata unavailable")

        assertTrue(registry.resolve("/usage") is SlashCommandRegistry.ResolvedCommand.Action)
        assertEquals(listOf("usage"), registry.commands.value.map { it.name })
        assertEquals(SlashCommandRegistry.RefreshState.Unavailable, registry.refreshState.value)
        assertNull(registry.resolve("/cached"))

        prepare(registry, listOf(skill("cached", "new source")))
        every { skillRepository.checkSourceIdentity(any()) } throws SkillDefinitionSourceChanged()

        assertTrue(registry.resolve("/usage") is SlashCommandRegistry.ResolvedCommand.Action)
        assertNull(registry.resolve("/cached"))
        assertEquals(listOf("usage"), registry.commands.value.map { it.name })
        assertEquals(SlashCommandRegistry.RefreshState.Unavailable, registry.refreshState.value)
        coVerify(exactly = 2) { skillRepository.readDefinitionSnapshot(any(), any()) }
    }

    @Test
    fun activeSourceChangeDiscardsSkillsWithoutCancellingTheCaller() = runBlocking {
        val registry = registry(usage)
        prepare(registry, listOf(skill("stale", "previous source")))
        coEvery { skillRepository.readDefinitionSnapshot(any(), any()) } throws SkillDefinitionSourceChanged()

        registry.refresh()

        assertTrue(currentCoroutineContext().isActive)
        assertEquals(SlashCommandRegistry.RefreshState.Unavailable, registry.refreshState.value)
        assertEquals(listOf("usage"), registry.commands.value.map { it.name })
        assertNull(registry.resolve("/stale"))
    }

    @Test
    fun downstreamErrorsAreNotConvertedIntoRecoverableMenuFailures() = runBlocking {
        val registry = registry(usage)
        prepare(registry, emptyList())
        val error = AssertionError("Fixture downstream error")
        coEvery { skillRepository.readDefinitionSnapshot(any(), any()) } throws error

        assertSame(error, runCatching { registry.refresh() }.exceptionOrNull())
    }

    // ---- 技能提示词 ----

    @Test
    fun buildSkillPrompt_replacesPlaceholder() {
        val s = skill("x", "d", instructions = "run with \$ARGUMENTS now")
        assertEquals("run with a4 now", SlashCommandRegistry.buildSkillPrompt(s, "a4"))
    }

    @Test
    fun buildSkillPrompt_appendsArgsWhenNoPlaceholder() {
        val s = skill("x", "d", instructions = "do it")
        assertEquals("do it\n\n用户附加输入：a4", SlashCommandRegistry.buildSkillPrompt(s, "a4"))
    }

    @Test
    fun buildSkillPrompt_noArgsKeepsBody() {
        val s = skill("x", "d", instructions = "do it")
        assertEquals("do it", SlashCommandRegistry.buildSkillPrompt(s, ""))
    }
}
