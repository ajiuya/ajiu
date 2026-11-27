package com.aicode.feature.agent.domain.container.chroot

import java.io.ByteArrayInputStream
import java.io.DataInputStream
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ChrootLaunchTest {
    private val instanceId = "0123456789abcdef01234567"
    private val launchId = "89abcdef0123456789abcdef"
    private val root = ChrootFileIdentity(65098, 8525637)

    private fun freeze(
        token: ByteArray = ByteArray(32) { (it + 1).toByte() },
        owner: String = "chat:session-one:call-one",
        identity: ChrootAppIdentity = ChrootAppIdentity(10445, 10445, listOf(3003)),
        cwd: String = "/root/workspace",
        argv: List<String> = listOf("/bin/sh", "-c", "printf done"),
        env: List<String> = listOf("HOME=/root", "PATH=/bin:/usr/bin"),
        mounts: List<ChrootMount> = emptyList(),
        leaseMs: Int = 15000,
        timeoutMs: Int = 120000
    ) = ChrootLaunch.freeze(
        instanceId, launchId, owner, token, root, identity, ChrootIoMode.STDIO,
        leaseMs, timeoutMs, cwd, argv, env, mounts
    )

    private fun rejects(block: () -> Unit) {
        try {
            block()
            throw AssertionError("Expected rejected launch")
        } catch (_: IllegalArgumentException) {
        }
    }

    private fun DataInputStream.string(): String = ByteArray(readInt()).also(::readFully).toString(Charsets.UTF_8)

    @Test
    fun encodesTheNativeWireLayoutWithoutImplicitPadding() {
        val launch = freeze()
        DataInputStream(ByteArrayInputStream(launch.payload())).use { input ->
            val token = ByteArray(32).also(input::readFully)
            assertArrayEquals(launch.token(), token)
            assertEquals(10445, input.readInt())
            assertEquals(10445, input.readInt())
            assertEquals(1, input.readInt())
            assertEquals(3003, input.readInt())
            assertEquals(0, input.readInt())
            assertEquals(15000, input.readInt())
            assertEquals(120000, input.readInt())
            assertEquals(root.device, input.readLong())
            assertEquals(root.inode, input.readLong())
            assertEquals(instanceId, input.string())
            assertEquals(launchId, input.string())
            assertEquals("chat:session-one:call-one", input.string())
            assertEquals("/root/workspace", input.string())
            assertEquals(3, input.readInt())
            assertEquals(listOf("/bin/sh", "-c", "printf done"), List(3) { input.string() })
            assertEquals(2, input.readInt())
            assertEquals(listOf("HOME=/root", "PATH=/bin:/usr/bin"), List(2) { input.string() })
            assertEquals(0, input.readInt())
            assertEquals(-1, input.read())
        }
    }

    @Test
    fun copiesAllCallerOwnedCollectionsAndTokenBytes() {
        val groups = mutableListOf(3003)
        val args = mutableListOf("/bin/sh", "")
        val env = mutableListOf("PATH=/bin")
        val mounts = mutableListOf<ChrootMount>()
        val token = ByteArray(32) { 1 }
        val launch = freeze(token, identity = ChrootAppIdentity(10445, 10445, groups), argv = args, env = env, mounts = mounts)
        val encoded = launch.payload()
        groups.clear()
        args[0] = "changed"
        env.clear()
        token.fill(0)
        launch.payload().fill(0)
        launch.token().fill(0)
        assertArrayEquals(encoded, launch.payload())
        assertEquals(listOf(3003), launch.appIdentity.groups)
        assertEquals(listOf("/bin/sh", ""), launch.argv)
        assertEquals(listOf("PATH=/bin"), launch.environment)
    }

    @Test
    fun payloadDigestChangesForOwnerArgvOrEnvironmentChanges() {
        val original = freeze()
        assertNotEquals(original.digest, freeze(owner = "chat:other:call").digest)
        assertNotEquals(original.digest, freeze(argv = listOf("/bin/true")).digest)
        assertNotEquals(original.digest, freeze(env = listOf("PATH=/usr/bin")).digest)
        assertEquals(original.digest, freeze().digest)
    }

    @Test
    fun rejectsRootIdentityAndForeignOrPrivilegedSupplementaryGroups() {
        for (uid in listOf(0, 1000, 9999)) rejects { ChrootAppIdentity(uid, uid, emptyList()) }
        rejects { ChrootAppIdentity(10445, 10446, emptyList()) }
        for (group in listOf(0, 1, 1000, 1001, 1010, 2000, 3004, 10446, 20446)) {
            rejects { ChrootAppIdentity(10445, 10445, listOf(group)) }
        }
        rejects { ChrootAppIdentity(10445, 10445, listOf(3003, 3003)) }
        assertEquals(6, ChrootAppIdentity(10445, 10445, listOf(1079, 3003, 9997, 20445, 50445, 99909997)).groups.size)
    }

    @Test
    fun rejectsMalformedTokensOwnerEnvironmentAndGuestPaths() {
        rejects { freeze(token = ByteArray(32)) }
        rejects { freeze(token = ByteArray(31) { 1 }) }
        rejects { freeze(owner = "") }
        rejects { freeze(owner = "x".repeat(513)) }
        rejects { freeze(env = listOf("PATH=/bin", "PATH=/usr/bin")) }
        rejects { freeze(env = listOf("1KEY=value")) }
        rejects { freeze(env = listOf("NOT_AN_ASSIGNMENT")) }
        rejects { freeze(argv = listOf("")) }
        rejects { freeze(argv = listOf("/bin/sh\u0000extra")) }
        rejects { freeze(argv = emptyList()) }
        for (path in listOf("relative", "/root/../etc", "/root//workspace", "/root/./workspace", "/root/")) {
            rejects { freeze(cwd = path) }
        }
        rejects { freeze(leaseMs = 4999) }
        rejects { freeze(timeoutMs = 1800001) }
    }

    @Test
    fun rejectsMalformedUnicodeAndPayloadBeyondFrameBudget() {
        try {
            freeze(argv = listOf("/bin/echo", "\ud800"))
            throw AssertionError("Malformed UTF-16 must not be replaced")
        } catch (_: java.nio.charset.CharacterCodingException) {
        }
        rejects { freeze(argv = listOf("/bin/echo") + List(17) { "x".repeat(65536) }) }
        rejects { freeze(argv = List(257) { "x" }) }
    }

    @Test
    fun enforcesMountTargetsSourceScopeAndReadOnlyConfiguration() {
        val identity = ChrootFileIdentity(65098, 123)
        fun mount(source: String, target: String, readOnly: Boolean = false, kind: ChrootMountKind = ChrootMountKind.DIRECTORY) =
            ChrootMount(kind, readOnly, source, target, identity)
        val home = mount("/data/user/0/com.ajiu/files/chroot-home/$instanceId", "/root")
        val workspace = mount("/data/user/0/com.ajiu/files/projects/example", "/root/workspace")
        val docs = mount("/data/user/0/com.ajiu/files/aicode/docs", "/root/.aicode/docs", true)
        val launch = freeze(mounts = listOf(docs, workspace, home))
        assertEquals(listOf("/root", "/root/workspace", "/root/.aicode/docs"), launch.mounts.map { it.target })
        rejects { freeze(mounts = listOf(home, home)) }
        rejects { freeze(mounts = listOf(mount("/data", "/root"))) }
        rejects { freeze(mounts = listOf(mount("/data/user/0/com.ajiu", "/root/workspace"))) }
        rejects { freeze(mounts = listOf(mount("/data/user/0/com.ajiu/files/aicode", "/root/workspace"))) }
        rejects { freeze(mounts = listOf(mount("/data/user/0/com.ajiu/files/aicode/docs", "/root/.aicode/docs"))) }
        rejects { freeze(mounts = listOf(mount("/system", "/system", true))) }
        rejects { freeze(mounts = listOf(mount("/data/user/0/com.ajiu/files/aicode", "/root/.aicode"))) }
        rejects { freeze(mounts = listOf(mount("/tmp", "/proc", true))) }
    }

    @Test
    fun toolOutputIsWritableWithoutExposingHostPrivateConfiguration() {
        fun mount(source: String, target: String, readOnly: Boolean = false) =
            ChrootMount(ChrootMountKind.DIRECTORY, readOnly, source, target, ChrootFileIdentity(65098, 123))
        val output = mount("/data/user/0/com.ajiu/files/aicode/tool-output", "/root/.aicode/tool-output")
        assertEquals(listOf(output), freeze(mounts = listOf(output)).mounts)
        for (source in listOf("/data/user/0/com.ajiu/files/aicode", "/data/user/0/com.ajiu/files/aicode/mcp-oauth",
            "/data/user/0/com.ajiu/files/credentials", "/data/user/0/com.other/files/aicode/tool-output",
            "/data/user/1/com.ajiu/files/aicode/tool-output")) {
            for (target in listOf("/root", "/root/.aicode", "/root/.aicode/tool-output", "/root/.aicode/mcp-oauth")) {
                rejects { freeze(mounts = listOf(mount(source, target))) }
            }
        }
        for (name in listOf("skills", "agents", "docs", "bin", "lib", "prompts.custom")) {
            val binding = mount("/data/user/0/com.ajiu/files/aicode/$name", "/root/.aicode/$name")
            rejects { freeze(mounts = listOf(binding)) }
            assertEquals(listOf(binding.copy(readOnly = true)), freeze(mounts = listOf(binding.copy(readOnly = true))).mounts)
        }
    }

    @Test
    fun diagnosticStringDoesNotExposeArgumentsEnvironmentOrOwner() {
        val launch = freeze(owner = "private-owner", argv = listOf("/bin/echo", "private-argument"), env = listOf("TOKEN=private-value"))
        assertFalse(launch.toString().contains("private-"))
        assertTrue(launch.toString().contains("argc=2"))
    }
}
