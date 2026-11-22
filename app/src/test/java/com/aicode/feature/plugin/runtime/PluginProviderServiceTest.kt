package com.aicode.feature.plugin.runtime

import android.app.Application
import com.aicode.core.security.KeystoreCipher
import com.aicode.feature.agent.data.local.database.AgentDatabase
import com.aicode.feature.plugin.domain.PluginCoreFixtures
import com.aicode.feature.settings.data.local.CustomModelMetadataStore
import com.aicode.feature.settings.data.repository.AIProviderRepositoryImpl
import com.aicode.feature.settings.data.repository.DefaultModelSettingsRepository
import com.aicode.feature.settings.domain.model.AIProviderConfig
import com.aicode.feature.settings.domain.model.ProviderType
import com.aicode.feature.settings.domain.model.ProviderKey
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import io.mockk.mockkObject
import io.mockk.unmockkObject
import java.util.Base64
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.spec.GCMParameterSpec
import javax.inject.Provider
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import org.robolectric.annotation.ConscryptMode

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28], application = Application::class, manifest = Config.NONE)
@ConscryptMode(ConscryptMode.Mode.OFF)
open class PluginProviderServiceTest {
    @get:Rule val temporary = TemporaryFolder()
    private lateinit var fixture: PluginConversationFixture
    private lateinit var database: AgentDatabase
    private var providerBoundary: PluginProviderDaoFixture? = null
    private lateinit var repository: AIProviderRepositoryImpl
    private lateinit var registrar: PluginProviderRegistrar
    private lateinit var service: PluginProviderService
    private val gateway = mockk<PluginHostGateway>()
    private val defaults = mockk<DefaultModelSettingsRepository>()
    private val id = PluginProviderIds.idOf("sample.plugin", "local")
    private val approvalDetails = mutableListOf<String>()
    private val approvedArguments = mutableListOf<JsonObject>()

    @Before fun setup() = runBlocking<Unit> {
        fixture = PluginConversationFixture(temporary.root)
        fixture.install()
        database = createDatabase()
        // Android Keystore bytes use real AES/GCM while leaving repository string encoding intact.
        val key = KeyGenerator.getInstance("AES").apply { init(128) }.generateKey()
        mockkObject(KeystoreCipher)
        every { KeystoreCipher.encryptBytes(any()) } answers {
            val cipher = Cipher.getInstance("AES/GCM/NoPadding")
            cipher.init(Cipher.ENCRYPT_MODE, key)
            cipher.iv + cipher.doFinal(firstArg())
        }
        every { KeystoreCipher.decryptBytes(any()) } answers {
            val bytes = firstArg<ByteArray>()
            val cipher = Cipher.getInstance("AES/GCM/NoPadding")
            cipher.init(Cipher.DECRYPT_MODE, key, GCMParameterSpec(128, bytes.copyOfRange(0, 12)))
            cipher.doFinal(bytes.copyOfRange(12, bytes.size))
        }
        repository = AIProviderRepositoryImpl(database.aiProviderDao(), database, Provider { fixture.manager })
        registrar = PluginProviderRegistrar(fixture.manager, repository)
        registrar.syncAll()
        coEvery { gateway.authorize(any(), any(), any(), PluginApprovalOperation.EXECUTE_TOOL, any(), any()) } coAnswers {
            firstArg<PluginInvocationContext>().checkCurrent()
            approvalDetails += arg<String>(4)
            approvedArguments += arg<JsonObject>(2)
        }
        service = PluginProviderService(repository, registrar, fixture.metadata, CustomModelMetadataStore(RuntimeEnvironment.getApplication()),
            defaults, mockk(), mockk(), mockk(), mockk(), mockk(), Provider { gateway })
    }

    protected open fun createDatabase(): AgentDatabase = PluginProviderDaoFixture().also { providerBoundary = it }.database

    @After fun close() {
        try { if (::database.isInitialized) database.close() }
        finally { providerBoundary?.close(); unmockkObject(KeystoreCipher) }
    }
    private fun params(vararg values: Pair<String, String>) = buildJsonObject {
        put("providerId", "local"); values.forEach { (key, value) -> put(key, value) }
    }
    private suspend fun error(block: suspend () -> Any?): String = try { block(); throw AssertionError("Expected denial") }
        catch (failure: PluginHostException) { failure.code }

    @Test open fun declaredProvidersAreRegisteredWithoutOverwritingUserConfiguration() = runBlocking<Unit> {
        val original = repository.getProviderById(id)!!
        assertEquals("http://127.0.0.1:8787/v1", original.baseUrl)
        val user = original.copy(name = "My name", apiKey = "user-secret", models = listOf("kept"), selectedModel = "kept", baseUrl = "https://example.invalid/custom")
        repository.saveProvider(user)
        registrar.syncAll()
        val actual = repository.getProviderById(id)!!
        assertEquals(user.copy(sortOrder = actual.sortOrder), actual)
        val raw = database.aiProviderDao().getProviderById(id)!!
        assertTrue(KeystoreCipher.isEncryptedString(raw.apiKey))
        assertNotEquals("user-secret", raw.apiKey)
    }

    @Test open fun disableDoesNotDeleteUserKeysModelsOrDefaultBindingsAndEnableRestoresAvailability() = runBlocking<Unit> {
        val original = repository.getProviderById(id)!!
        repository.saveProvider(original.copy(apiKey = "user-secret", models = listOf("kept"), selectedModel = "kept"))
        fixture.manager.disable(fixture.capture.id)
        registrar.syncAll()
        val disabled = repository.getProviderById(id)!!
        assertFalse(disabled.isEnabled)
        assertEquals("user-secret", disabled.apiKey)
        assertEquals(listOf("kept"), disabled.models)
        assertTrue(database.aiProviderDao().getProviderById(id)!!.isEnabled)
        coVerify(exactly = 0) { defaults.clear() }
        fixture.capture = PluginCoreFixtures.activate(fixture.manager)
        assertTrue(repository.getProviderById(id)!!.isEnabled)
    }

    @Test open fun userDisabledProviderDoesNotBecomeEnabledWhenPluginReactivates() = runBlocking<Unit> {
        repository.setProviderEnabled(id, false)
        fixture.manager.disable(fixture.capture.id)
        fixture.capture = PluginCoreFixtures.activate(fixture.manager)
        registrar.syncAll()
        assertFalse(repository.getProviderById(id)!!.isEnabled)
    }

    @Test open fun keyUpdateUsesEncryptedTargetedColumnWriteAndNeverEchoesOldOrNewKey() = runBlocking<Unit> {
        val original = repository.getProviderById(id)!!
        repository.saveProvider(original.copy(name = "Custom", apiKey = "old-key", models = listOf("model"), selectedModel = "model",
            multiKeyEnabled = true, apiKeys = listOf(ProviderKey("first-key"), ProviderKey("second-key"))))
        val before = database.aiProviderDao().getProviderById(id)!!
        providerBoundary?.writes?.clear()
        val result = service.setApiKey(fixture.context(), params("apiKey" to "new-secret"))
        assertEquals("true", result.jsonObject.getValue("hasKey").jsonPrimitive.content)
        assertFalse(result.toString().contains("new-secret"))
        assertFalse(approvalDetails.joinToString().contains("new-secret"))
        assertFalse(approvalDetails.joinToString().contains("old-key"))
        assertEquals("new-secret", repository.getProviderById(id)!!.apiKey)
        assertEquals("Custom", repository.getProviderById(id)!!.name)
        assertEquals(listOf("model"), repository.getProviderById(id)!!.models)
        val after = database.aiProviderDao().getProviderById(id)!!
        assertNotEquals("new-secret", after.apiKey)
        assertTrue(KeystoreCipher.isEncryptedString(after.apiKey))
        assertFalse(after.multiKeyEnabled)
        assertEquals("", after.apiKeys)
        assertEquals(before.copy(apiKey = after.apiKey, multiKeyEnabled = false, apiKeys = ""), after)
        providerBoundary?.let { assertEquals(listOf("setProviderApiKey"), it.writes) }
    }

    @Test open fun ownerCannotMutateUserOrOtherPluginProvider() = runBlocking<Unit> {
        for (target in listOf("plugin:other.plugin:local", "user-provider", "plugin:sample.plugin:missing", "plugin:sample.plugin:local:extra")) {
            val args = buildJsonObject { put("providerId", target); put("apiKey", "value") }
            assertEquals("PROVIDER_NOT_FOUND", error { service.setApiKey(fixture.context(), args) })
        }
        assertTrue(approvalDetails.isEmpty())
    }

    @Test open fun addModelPreservesExistingModelsAndNoKeyIsIncludedInListing() = runBlocking<Unit> {
        val context = fixture.context()
        service.addModel(context, params("model" to "first"))
        service.addModel(context, params("model" to "second"))
        service.addModel(context, params("model" to "first"))
        val actual = repository.getProviderById(id)!!
        assertEquals(listOf("first", "second"), actual.models)
        assertEquals("first", actual.selectedModel)
        val json = service.listModels(context, params()).toString()
        assertFalse(json.contains("apiKey"))
        assertFalse(json.contains("baseUrl"))
        assertTrue(repository.getAllProviders().first().any { it.id == id })
    }

    @Test open fun differentKeyWritesRequireDifferentExactApprovalFingerprints() = runBlocking<Unit> {
        service.setApiKey(fixture.context(), params("apiKey" to "first-private-key"))
        service.setApiKey(fixture.context(), params("apiKey" to "second-private-key"))
        assertNotEquals(approvedArguments[0].getValue("keySha256"), approvedArguments[1].getValue("keySha256"))
        assertFalse(approvedArguments.toString().contains("first-private-key"))
        assertFalse(approvedArguments.toString().contains("second-private-key"))
    }

    @Test open fun providerIdsRejectAmbiguousSeparatorsAndInvalidOwners() {
        assertEquals("sample.plugin", PluginProviderIds.pluginIdOf(id))
        assertEquals("local", PluginProviderIds.localIdOf(id))
        assertNull(PluginProviderIds.pluginIdOf("plugin:sample.plugin:local:other"))
        assertNull(PluginProviderIds.localIdOf("plugin::local"))
        assertNull(PluginProviderIds.localIdOf("plugin:sample.plugin:"))
    }
}
