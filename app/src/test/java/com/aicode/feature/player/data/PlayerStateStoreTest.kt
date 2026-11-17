package com.aicode.feature.player.data

import android.content.SharedPreferences
import com.aicode.feature.agent.domain.memory.ProjectMemoryIdentity
import kotlinx.serialization.json.*
import org.junit.Assert.*
import org.junit.Test
import java.lang.reflect.Proxy

internal fun memoryPreferences(writes: MutableList<String> = mutableListOf(), commitAllowed: () -> Boolean = { true }): SharedPreferences {
    val strings = mutableMapOf<String, String>()
    fun editor(): SharedPreferences.Editor {
        val pending = linkedMapOf<String, String?>()
        fun commit(): Boolean {
            if (!commitAllowed()) return false
            pending.forEach { (key, value) ->
                if (value == null) strings.remove(key) else { strings[key] = value; writes += key }
            }
            pending.clear()
            return true
        }
        return Proxy.newProxyInstance(SharedPreferences.Editor::class.java.classLoader, arrayOf(SharedPreferences.Editor::class.java)) { proxy, method, args ->
            when (method.name) {
                "putString" -> { pending[args!![0] as String] = args[1] as? String; proxy }
                "remove" -> { pending[args!![0] as String] = null; proxy }
                "apply" -> { commit(); Unit }
                "commit" -> commit()
                else -> error("Unexpected editor ${method.name}")
            }
        } as SharedPreferences.Editor
    }
    return Proxy.newProxyInstance(SharedPreferences::class.java.classLoader, arrayOf(SharedPreferences::class.java)) { _, method, args ->
        when (method.name) {
            "getString" -> strings[args!![0]] ?: args[1]
            "getAll" -> strings.toMap()
            "edit" -> editor()
            else -> error("Unexpected preferences ${method.name}")
        }
    } as SharedPreferences
}

internal fun playerStateEpoch(store: PlayerStateStore): Long = Regex("window\\.__AJIU_STATE_EPOCH__=([0-9]+);")
    .find(store.bootstrapScript())!!.groupValues[1].toLong()

internal fun playerStatePayload(key: String, value: String?, epoch: Long, sequence: Long = 1): ByteArray = buildJsonObject {
    put("key", key)
    put("value", value?.let(::JsonPrimitive) ?: JsonNull)
    put("revision", buildJsonObject { put("epoch", epoch); put("sequence", sequence) })
}.toString().toByteArray()

class PlayerStateStoreTest {
    private val namespace = "a".repeat(64)
    private fun key(suffix: String) = "ajiu_player_v3:$namespace:$suffix"
    private fun store(preferences: SharedPreferences = memoryPreferences(), maxKeys: Int = 4096) = PlayerStateStore(preferences, Json, namespace, maxKeys)

    @Test fun stableStateSurvivesStoreRecreationAndJsonNullDeletion() {
        val preferences = memoryPreferences()
        val first = store(preferences)
        val epoch = playerStateEpoch(first)
        first.update(playerStatePayload(key("history"), "[{\"url\":\"https://cdn.example/movie.mp4\",\"time\":12}]", epoch))
        val reopened = store(preferences)
        assertEquals(first.snapshot(), reopened.snapshot())
        reopened.update(playerStatePayload(key("history"), null, epoch, 2))
        assertTrue(reopened.snapshot().isEmpty())
        assertTrue(store(preferences).snapshot().isEmpty())
        assertNull(preferences.getString("record:${key("history")}", null))
    }

    @Test fun rejectsForeignNamespacesTemporaryUrlsAndOversizedBodiesOrValues() {
        val store = store()
        val epoch = playerStateEpoch(store)
        for (foreign in listOf("unrelated", "ajiu_player_v2:history", "ajiu_player_v3:${"b".repeat(64)}:history")) {
            assertEquals(400, assertThrows(PlayerHttpException::class.java) { store.update(playerStatePayload(foreign, "value", epoch)) }.status)
        }
        for (value in listOf("http://127.0.0.1:1234/${"c".repeat(64)}/relay?u=x",
            "[\"http://127.0.0.1:1234/${"c".repeat(64)}/index.html\"]", "http:\\/\\/localhost:1234\\/${"c".repeat(64)}\\/local")) {
            assertEquals(400, assertThrows(PlayerHttpException::class.java) { store.update(playerStatePayload(key("history"), value, epoch)) }.status)
        }
        assertEquals(400, assertThrows(PlayerHttpException::class.java) { store.update(playerStatePayload(key("history"), "x".repeat(512 * 1024 + 1), epoch)) }.status)
        assertEquals(413, assertThrows(PlayerHttpException::class.java) { store.update(ByteArray(PlayerStateStore.MAX_BODY + 1)) }.status)
        assertTrue(store.snapshot().isEmpty())
    }

    @Test fun all164EpisodeProgressAndTracksSurviveStoreRecreation() {
        val preferences = memoryPreferences()
        val store = store(preferences)
        val epoch = playerStateEpoch(store)
        for (episode in 1..164) {
            store.update(playerStatePayload(key("position:series:$episode"), "{\"time\":$episode,\"url\":\"https://cdn.example/episode-$episode.m3u8\"}", epoch))
            store.update(playerStatePayload(key("tracks:series:$episode"), "{\"audio\":1,\"subtitle\":2}", epoch))
        }
        store.update(playerStatePayload(key("series"), "{\"episode\":164}", epoch))
        store.update(playerStatePayload(key("queue"), "[\"https://cdn.example/episode-164.m3u8\"]", epoch))
        val restored = store(preferences)
        assertEquals(330, restored.snapshot().size)
        assertEquals(store.snapshot(), restored.snapshot())
        assertEquals("{\"audio\":1,\"subtitle\":2}", restored.snapshot()[key("tracks:series:164")]!!.jsonPrimitive.content)
    }

    @Test fun recordWritesAreIncrementalAndExactRevisionRetriesDoNotWrite() {
        val writes = mutableListOf<String>()
        val preferences = memoryPreferences(writes)
        val store = store(preferences)
        val epoch = playerStateEpoch(store)
        for (i in 0 until 100) store.update(playerStatePayload(key("$i"), "{\"time\":$i}", epoch))
        writes.clear()
        repeat(20) { store.update(playerStatePayload(key("50"), "{\"time\":123.456}", epoch, 2)) }
        assertEquals(listOf("revision:${key("50")}", "record:${key("50")}"), writes)
        assertNull(preferences.getString("state", null))
        assertEquals(store.snapshot(), store(preferences).snapshot())
    }

    @Test fun foreignAndLegacyPreferencesCannotPopulateCurrentProject() {
        val preferences = memoryPreferences()
        preferences.edit().putString("state", "{\"ajiu_player_v2:history\":\"old\"}")
            .putString("record:ajiu_player_v2:history", "old")
            .putString("record:ajiu_player_v3:${"b".repeat(64)}:history", "foreign").commit()
        val store = store(preferences)
        assertTrue(store.snapshot().isEmpty())
        val epoch = playerStateEpoch(store)
        store.update(playerStatePayload(key("history"), "current", epoch))
        assertEquals(mapOf(key("history") to JsonPrimitive("current")), store(preferences).snapshot())
        assertTrue(PlayerStateStore(preferences, Json, "c".repeat(64)).snapshot().isEmpty())
    }

    @Test fun full4096KeysAndExactEscapedTotalBudgetSurviveIncrementalUpdates() {
        val preferences = memoryPreferences()
        val store = store(preferences)
        val epoch = playerStateEpoch(store)
        for (i in 0 until 4096) store.update(playerStatePayload(key("$i"), "x", epoch))
        assertEquals(4096, store(preferences).snapshot().size)
        assertEquals(413, assertThrows(PlayerHttpException::class.java) { store.update(playerStatePayload(key("extra"), "x", epoch)) }.status)
        val large = store()
        val largeEpoch = playerStateEpoch(large)
        val escaped = '"'.toString().repeat(512 * 1024)
        for (i in 0..2) large.update(playerStatePayload(key("large:$i"), escaped, largeEpoch))
        val previous = large.snapshot()
        assertEquals(413, assertThrows(PlayerHttpException::class.java) { large.update(playerStatePayload(key("large:3"), escaped, largeEpoch)) }.status)
        assertEquals(previous, large.snapshot())
    }

    @Test fun revisionsRejectReverseArrivalAndSurviveRestartIncludingTombstones() {
        val preferences = memoryPreferences()
        val store = store(preferences)
        val epoch = playerStateEpoch(store)
        store.update(playerStatePayload(key("ordered"), "{\"time\":12.345}", epoch, 2))
        assertEquals(409, assertThrows(PlayerHttpException::class.java) { store.update(playerStatePayload(key("ordered"), "old", epoch)) }.status)
        store.update(playerStatePayload(key("ordered"), "{\"time\":12.345}", epoch, 2))
        assertEquals(409, assertThrows(PlayerHttpException::class.java) { store.update(playerStatePayload(key("ordered"), "different", epoch, 2)) }.status)
        val reopened = store(preferences)
        assertEquals(store.snapshot(), reopened.snapshot())
        assertTrue(playerStateEpoch(reopened) > epoch)
        val unversioned = buildJsonObject { put("key", key("ordered")); put("value", "legacy") }.toString().toByteArray()
        assertEquals(400, assertThrows(PlayerHttpException::class.java) { reopened.update(unversioned) }.status)
        reopened.update(playerStatePayload(key("ordered"), null, epoch, 3))
        val deleted = store(preferences)
        assertTrue(deleted.snapshot().isEmpty())
        assertEquals(409, assertThrows(PlayerHttpException::class.java) { deleted.update(playerStatePayload(key("ordered"), "resurrect", epoch, 2)) }.status)
    }

    @Test fun bootstrapIssuesMonotonicWriterEpochAndRejectsUnissuedOrInvalidRevisions() {
        val preferences = memoryPreferences()
        val store = store(preferences)
        assertEquals(400, assertThrows(PlayerHttpException::class.java) { store.update(playerStatePayload(key("history"), "x", 1)) }.status)
        val epoch = playerStateEpoch(store)
        for ((writer, sequence) in listOf(0L to 1L, epoch to 0L, epoch to -1L, (epoch + 1) to 1L, epoch to 9007199254740992L)) {
            assertEquals(400, assertThrows(PlayerHttpException::class.java) { store.update(playerStatePayload(key("history"), "x", writer, sequence)) }.status)
        }
        val script = store.bootstrapScript()
        assertTrue(script.contains("window.__AJIU_STATE_NAMESPACE__=\"$namespace\";"))
        assertTrue(script.contains("window.__AJIU_SAVED_STATE__={};"))
        assertTrue(script.contains("window.__AJIU_STATE_REVISIONS__={};"))
        assertTrue(playerStateEpoch(store(preferences)) > epoch)
    }

    @Test fun valuesAndTombstonesCountTowardKeyBudgetWithAtomicFailure() {
        val preferences = memoryPreferences()
        val store = store(preferences, maxKeys = 128)
        val epoch = playerStateEpoch(store)
        val big = "x".repeat(512 * 1024)
        store.update(playerStatePayload(key("queue"), big, epoch))
        assertEquals(big, store.snapshot()[key("queue")]!!.jsonPrimitive.content)
        for (index in 1..127) store.update(playerStatePayload(key("$index"), "x", epoch))
        assertEquals(413, assertThrows(PlayerHttpException::class.java) { store.update(playerStatePayload(key("overflow"), "x", epoch)) }.status)
        assertEquals(128, store.snapshot().size)
        assertFalse(store.snapshot().containsKey(key("overflow")))
        store.update(playerStatePayload(key("1"), null, epoch, 2))
        val reopened = store(preferences, maxKeys = 128)
        assertEquals(127, reopened.snapshot().size)
        assertEquals(413, assertThrows(PlayerHttpException::class.java) { reopened.update(playerStatePayload(key("overflow"), "x", epoch)) }.status)
    }

    @Test fun failedCommitCannotPublishValueOrRevision() {
        var allowCommit = true
        val preferences = memoryPreferences(commitAllowed = { allowCommit })
        val store = store(preferences)
        val epoch = playerStateEpoch(store)
        store.update(playerStatePayload(key("history"), "before", epoch))
        allowCommit = false
        assertEquals(500, assertThrows(PlayerHttpException::class.java) { store.update(playerStatePayload(key("history"), "after", epoch, 2)) }.status)
        assertEquals("before", store.snapshot()[key("history")]!!.jsonPrimitive.content)
        assertEquals(store.snapshot(), store(preferences).snapshot())
        allowCommit = true
        store.update(playerStatePayload(key("history"), "after", epoch, 2))
        assertEquals("after", store.snapshot()[key("history")]!!.jsonPrimitive.content)
    }

    @Test fun malformedBodiesAndTypeConfusionCannotChangeSnapshot() {
        val store = store()
        val epoch = playerStateEpoch(store)
        val valid = Json.parseToJsonElement(playerStatePayload(key("history"), "x", epoch).toString(Charsets.UTF_8)).jsonObject
        val malformed = listOf("null", "[]", "{", JsonObject(valid - "value").toString(), JsonObject(valid - "revision").toString(),
            JsonObject(valid + ("value" to JsonPrimitive(42))).toString(), JsonObject(valid + ("value" to JsonObject(emptyMap()))).toString(),
            JsonObject(valid + ("key" to JsonPrimitive(7))).toString())
        malformed.forEach { raw ->
            assertEquals(raw, 400, assertThrows(PlayerHttpException::class.java) { store.update(raw.toByteArray()) }.status)
        }
        assertTrue(store.snapshot().isEmpty())
    }

    @Test fun stableProjectIdentityNormalizesRootAndSeparatesExecutionEndpoints() {
        val identity = ProjectMemoryIdentity("remote_ssh", "HOST.EXAMPLE", 22, "user", "/work/project/./", "")
        val namespace = playerStateNamespace(identity)
        assertEquals(64, namespace.length)
        assertEquals(namespace, playerStateNamespace(identity.copy(host = "host.example", projectRoot = "/work/project")))
        listOf(identity.copy(host = "other.example"), identity.copy(port = 2222), identity.copy(username = "other"),
            identity.copy(projectRoot = "/work/other")).forEach { assertNotEquals(namespace, playerStateNamespace(it)) }
        val local = ProjectMemoryIdentity("local_proot", projectRoot = "/work/project", profileId = "one")
        assertNotEquals(playerStateNamespace(local), playerStateNamespace(local.copy(profileId = "two")))
        assertNotEquals(namespace, playerStateNamespace(local))
        assertNotEquals(PlayerStateStore.preferencesName(namespace), PlayerStateStore.preferencesName(playerStateNamespace(local)))
    }
}
