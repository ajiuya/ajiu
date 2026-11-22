package com.aicode.feature.plugin.runtime

import android.app.Application
import androidx.room.Room
import com.aicode.feature.agent.data.local.database.AgentDatabase
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import org.robolectric.annotation.ConscryptMode
import org.robolectric.annotation.SQLiteMode

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28], application = Application::class, manifest = Config.NONE)
@ConscryptMode(ConscryptMode.Mode.OFF)
@SQLiteMode(SQLiteMode.Mode.NATIVE)
class PluginProviderRoomIntegrationTest : PluginProviderServiceTest() {
    // Requires a host supported by Robolectric's SQLite JNI; SQL behavior is not substituted here.
    override fun createDatabase(): AgentDatabase = Room.inMemoryDatabaseBuilder(
        RuntimeEnvironment.getApplication(), AgentDatabase::class.java
    ).allowMainThreadQueries().build()

    @Test override fun declaredProvidersAreRegisteredWithoutOverwritingUserConfiguration() =
        super.declaredProvidersAreRegisteredWithoutOverwritingUserConfiguration()

    @Test override fun disableDoesNotDeleteUserKeysModelsOrDefaultBindingsAndEnableRestoresAvailability() =
        super.disableDoesNotDeleteUserKeysModelsOrDefaultBindingsAndEnableRestoresAvailability()

    @Test override fun userDisabledProviderDoesNotBecomeEnabledWhenPluginReactivates() =
        super.userDisabledProviderDoesNotBecomeEnabledWhenPluginReactivates()

    @Test override fun keyUpdateUsesEncryptedTargetedColumnWriteAndNeverEchoesOldOrNewKey() =
        super.keyUpdateUsesEncryptedTargetedColumnWriteAndNeverEchoesOldOrNewKey()

    @Test override fun ownerCannotMutateUserOrOtherPluginProvider() =
        super.ownerCannotMutateUserOrOtherPluginProvider()

    @Test override fun addModelPreservesExistingModelsAndNoKeyIsIncludedInListing() =
        super.addModelPreservesExistingModelsAndNoKeyIsIncludedInListing()

    @Test override fun differentKeyWritesRequireDifferentExactApprovalFingerprints() =
        super.differentKeyWritesRequireDifferentExactApprovalFingerprints()

    @Test override fun providerIdsRejectAmbiguousSeparatorsAndInvalidOwners() =
        super.providerIdsRejectAmbiguousSeparatorsAndInvalidOwners()
}
