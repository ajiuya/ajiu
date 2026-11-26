package com.aicode.feature.workspace.domain

import com.aicode.feature.agent.domain.container.ChrootInstance
import com.aicode.feature.agent.domain.container.ContainerProfile
import com.aicode.feature.agent.domain.container.LocalContainerRuntime
import com.aicode.feature.agent.domain.container.runtime.LocalRuntimeAuthorizationRequired
import com.aicode.feature.agent.domain.container.runtime.LocalRuntimeCapture
import com.aicode.feature.agent.domain.container.runtime.LocalRuntimeCommands
import com.aicode.feature.agent.domain.container.runtime.RuntimeOwner
import com.aicode.feature.agent.domain.container.runtime.RuntimeOwnerKind
import com.aicode.feature.settings.data.repository.ContainerProfileSnapshot
import com.aicode.feature.settings.data.repository.ContainerSettingsRepository
import com.aicode.feature.settings.data.repository.ExecutionMode
import com.aicode.feature.settings.data.repository.ExecutionModeHolder
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.ByteArrayInputStream
import java.io.File
import java.io.InputStream
import kotlin.coroutines.AbstractCoroutineContextElement
import kotlin.coroutines.CoroutineContext

class WorkspaceRuntimeContextTest {
    private val agent = RuntimeOwner(RuntimeOwnerKind.AGENT, "chat-a")
    private val files = RuntimeOwner(RuntimeOwnerKind.FILE, "browser-a")

    private class Marker : AbstractCoroutineContextElement(Key) {
        companion object Key : CoroutineContext.Key<Marker>
    }

    private class Fixture {
        val mode = ExecutionModeHolder()
        val settings = mockk<ContainerSettingsRepository>()
        val local = mockk<LocalFileAccess>()
        val remote = mockk<RemoteSftpFileAccess>()
        val runtime = mockk<LocalRuntimeCommands>()
        val backend = mockk<FileAccessProvider>()
        var selection = 1L
        var configuration = 1L
        var generation = 1L
        var profile = ContainerProfile.BUILTIN_ALPINE.copy(
            id = "root-profile", isBuiltin = false, localRuntime = LocalContainerRuntime.CHROOT,
            chrootInstance = ChrootInstance("0123456789abcdef01234567", 1, 2, 3)
        )
        val snapshotIdentity = Any()
        val grants = mockk<LocalFolderGrantRepository>(relaxed = true) {
            every { snapshot() } returns LocalFolderSnapshot.EMPTY
        }
        val factory = WorkspaceExecutionContextFactory(backend, mode, local, remote, settings, grants, runtime)
        var lastFingerprint: String? = null

        init {
            coEvery { settings.captureProfileSnapshot() } answers {
                ContainerProfileSnapshot(profile.id, profile.id, listOf(profile), selection, configuration, snapshotIdentity)
            }
            every { settings.checkProfileSnapshot(any()) } answers {
                val captured = firstArg<ContainerProfileSnapshot>()
                if (captured.selectionRevision != selection || captured.configurationRevision != configuration) {
                    throw CancellationException("Profile changed")
                }
            }
            every { local.captureWorkspaceSource() } returns WorkspacePathMapper.WorkspaceSource(File("/app/workspace"), "/app/workspace")
            every { local.checkWorkspaceSource(any()) } returns Unit
            every { backend.cacheIdentity() } answers { "runtime:$generation" }
            coEvery { runtime.capture(any(), any(), any(), any(), any(), any()) } answers {
                lastFingerprint = arg(4)
                val source = arg<() -> Unit>(5)
                val capturedGeneration = generation
                LocalRuntimeCapture(
                    arg(3), profile.id, configuration, profile.chrootInstance!!,
                    "/app/workspace", "/root", "runtime:$generation", Any()
                ) {
                    source()
                    if (capturedGeneration != generation) throw CancellationException("Runtime generation changed")
                }
            }
            every { runtime.fileAccess(any()) } returns backend
        }
    }

    @Test fun missingOwnerRejectsBeforeRuntimeCaptureOrWorkerBackend() = runBlocking {
        val fixture = Fixture()
        assertTrue(runCatching { fixture.factory.freeze() }.exceptionOrNull() is LocalRuntimeAuthorizationRequired)
        coVerify(exactly = 0) { fixture.runtime.capture(any(), any(), any(), any(), any(), any()) }
        verify(exactly = 0) { fixture.runtime.fileAccess(any()) }
        verify(exactly = 0) { fixture.local.captureWorkspaceSource() }
        verify(exactly = 0) { fixture.backend.cacheIdentity() }
    }

    @Test fun missingSelectedProfileCannotSilentlyFallBackToProotOrStartRuntime() = runBlocking {
        val fixture = Fixture()
        coEvery { fixture.settings.captureProfileSnapshot() } returns ContainerProfileSnapshot(
            "removed-chroot", "removed-chroot", listOf(ContainerProfile.BUILTIN_ALPINE), 1L, 1L, fixture.snapshotIdentity
        )
        assertTrue(runCatching { fixture.factory.freeze(runtimeOwner = agent) }.exceptionOrNull() is CancellationException)
        coVerify(exactly = 0) { fixture.runtime.capture(any(), any(), any(), any(), any(), any()) }
        verify(exactly = 0) { fixture.local.captureExecutionMapping(any()) }
        verify(exactly = 0) { fixture.runtime.fileAccess(any()) }
    }

    @Test fun immutableOwnerSurvivesIoAndExplicitOtherOwnerGetsAnIndependentCapture() = runBlocking {
        val fixture = Fixture()
        val execution = fixture.factory.freeze(runtimeOwner = agent)
        assertEquals(agent, execution.runtimeOwner)
        assertEquals(agent, execution.localRuntimeCapture!!.owner)
        withContext(execution) {
            withContext(Dispatchers.IO) {
                assertSame(execution, WorkspaceExecutionContext.current())
                assertEquals(agent, WorkspaceExecutionContext.current()!!.runtimeOwner)
                assertSame(execution, fixture.factory.freeze())
                assertSame(execution, fixture.factory.freeze(runtimeOwner = agent))
                val separate = fixture.factory.freeze(runtimeOwner = files)
                assertEquals(files, separate.runtimeOwner)
                assertEquals(files, separate.localRuntimeCapture!!.owner)
                assertNotEquals(execution.localRuntimeCapture, separate.localRuntimeCapture)
                assertSame(execution.profileSnapshot, separate.profileSnapshot)
                assertSame(execution.runtimeWorkspaceSource, separate.runtimeWorkspaceSource)
            }
        }
        assertNull(WorkspaceExecutionContext.current())
        coVerify(exactly = 1) { fixture.runtime.capture(any(), any(), any(), agent, any(), any()) }
        coVerify(exactly = 1) { fixture.runtime.capture(any(), any(), any(), files, any(), any()) }
        coVerify(exactly = 1) { fixture.settings.captureProfileSnapshot() }
        verify(exactly = 1) { fixture.local.captureWorkspaceSource() }
    }

    @Test fun anotherOwnerDoesNotDependOnTheParentOwnersBusinessLease() = runBlocking {
        val fixture = Fixture()
        val parent = fixture.factory.freeze(runtimeOwner = agent)
        fixture.generation++
        assertThrows(CancellationException::class.java) { parent.checkCurrent() }
        withContext(parent) {
            val separate = fixture.factory.freeze(runtimeOwner = files)
            assertEquals(files, separate.runtimeOwner)
            assertEquals("runtime:2", separate.localRuntimeCapture!!.digest)
            separate.checkCurrent()
        }
    }

    @Test fun externalStableSourceRemainsUsableAfterRequestJobEndsButRejectsOriginChanges() = runBlocking {
        val fixture = Fixture()
        var originCurrent = true
        var source: (() -> Unit)? = null
        coEvery { fixture.runtime.capture(any(), any(), any(), any(), any(), any()) } answers {
            source = arg(5)
            throw LocalRuntimeAuthorizationRequired()
        }
        val request = Job()
        withContext(request) {
            assertTrue(runCatching {
                fixture.factory.freeze(runtimeOwner = files, checkSource = {
                    if (!originCurrent) throw CancellationException("Origin changed")
                })
            }.exceptionOrNull() is LocalRuntimeAuthorizationRequired)
        }
        request.cancel()
        source!!.invoke()
        originCurrent = false
        assertThrows(CancellationException::class.java) { source!!.invoke() }
        Unit
    }

    @Test fun sourceChangesRejectOtherOwnerRecaptureWithoutLookingUpNewWorkspace() = runBlocking {
        val fixture = Fixture()
        val execution = fixture.factory.freeze(runtimeOwner = agent)
        fixture.selection += 2
        withContext(execution) {
            assertTrue(runCatching { fixture.factory.freeze(runtimeOwner = files) }.exceptionOrNull() is CancellationException)
        }
        coVerify(exactly = 0) { fixture.runtime.capture(any(), any(), any(), files, any(), any()) }
        verify(exactly = 1) { fixture.local.captureWorkspaceSource() }
    }

    @Test fun profileEditsSelectionAbaAndRuntimeGenerationCannotRestoreAnOldCapture() = runBlocking {
        val fixture = Fixture()
        val first = fixture.factory.freeze(runtimeOwner = agent)
        val firstFingerprint = fixture.lastFingerprint
        fixture.selection += 2
        assertThrows(CancellationException::class.java) { first.checkCurrent() }
        val afterSelection = fixture.factory.freeze(runtimeOwner = agent)
        assertNotEquals(firstFingerprint, fixture.lastFingerprint)
        fixture.profile = fixture.profile.copy(env = mapOf("EDITOR" to "vim"))
        fixture.configuration++
        assertThrows(CancellationException::class.java) { afterSelection.checkCurrent() }
        val afterEdit = fixture.factory.freeze(runtimeOwner = agent)
        fixture.profile = fixture.profile.copy(chrootInstance = fixture.profile.chrootInstance!!.copy(installGeneration = 4))
        fixture.configuration++
        assertThrows(CancellationException::class.java) { afterEdit.checkCurrent() }
        val afterInstall = fixture.factory.freeze(runtimeOwner = agent)
        fixture.generation++
        assertThrows(CancellationException::class.java) { afterInstall.checkCurrent() }
        Unit
    }

    @Test fun blockingBridgeRetainsOwnerOriginalJobAndOtherContextElementsForReturnedStreams() {
        val operation = Job()
        val marker = Marker()
        var closed = 0
        val execution = WorkspaceExecutionContext(
            ExecutionMode.LOCAL_PROOT, "fixture", mockk(), null, null,
            runtimeOwner = agent
        ) {}
        val input = runBlocking(execution.ownedBlockingContext(operation) + marker) {
            assertEquals(agent, WorkspaceExecutionContext.current()!!.runtimeOwner)
            assertSame(operation, WorkspaceExecutionContext.blockingContext()[Job])
            assertSame(marker, WorkspaceExecutionContext.blockingContext()[Marker])
            val child = coroutineContext[Job]!!
            assertNotEquals(operation, child)
            runBlocking(WorkspaceExecutionContext.blockingContext() + Dispatchers.IO) {
                assertSame(operation, WorkspaceExecutionContext.blockingContext()[Job])
                assertSame(marker, WorkspaceExecutionContext.blockingContext()[Marker])
                assertSame(operation, execution.ownedBlockingContext(coroutineContext[Job]!!)[Job])
                execution.useCaptured {
                    execution.protectRead(object : ByteArrayInputStream(byteArrayOf(1, 2)) {
                        override fun close() { closed++ }
                    })
                }
            }
        }
        assertFalse(operation.isCompleted)
        assertEquals(1, input.read())
        operation.cancel()
        assertEquals(1, closed)
        assertThrows(CancellationException::class.java) { input.read() }
        assertNull(WorkspaceExecutionContext.current())
    }

    @Test fun retainedUseCapturedKeepsFreezeJobAndRejectsAnotherInstalledOwner() = runBlocking {
        val operation = Job()
        val execution = WorkspaceExecutionContext(
            ExecutionMode.LOCAL_PROOT, "fixture", mockk(), null, null,
            runtimeOwner = agent, cancellationJob = operation
        ) {}
        var closed = 0
        val input: InputStream = execution.useCaptured {
            assertEquals(agent, WorkspaceExecutionContext.current()!!.runtimeOwner)
            assertSame(operation, WorkspaceExecutionContext.blockingContext()[Job])
            execution.protectRead(object : ByteArrayInputStream(byteArrayOf(1, 2)) {
                override fun close() { closed++ }
            })
        }
        val other = WorkspaceExecutionContext(ExecutionMode.LOCAL_PROOT, "other", mockk(), null, null, runtimeOwner = files) {}
        withContext(other) {
            assertThrows(CancellationException::class.java) { execution.useCaptured {} }
        }
        operation.cancel()
        assertEquals(1, closed)
        assertThrows(CancellationException::class.java) { input.read() }
        assertNull(WorkspaceExecutionContext.current())
    }
}
