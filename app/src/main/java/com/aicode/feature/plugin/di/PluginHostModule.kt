package com.aicode.feature.plugin.di

import com.aicode.feature.plugin.runtime.PluginHostApi
import com.aicode.feature.plugin.runtime.PluginNativePermissionApi
import com.aicode.feature.plugin.runtime.PluginSystemHostApi
import com.aicode.feature.plugin.runtime.PluginConversationApi
import com.aicode.feature.plugin.runtime.PluginResourceHostApi
import com.aicode.feature.plugin.presentation.PluginUiHostApi
import dagger.Binds
import dagger.Module
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import dagger.multibindings.IntoSet

@Module
@InstallIn(SingletonComponent::class)
abstract class PluginHostModule {
    @Binds @IntoSet abstract fun system(impl: PluginSystemHostApi): PluginHostApi
    @Binds @IntoSet abstract fun permissions(impl: PluginNativePermissionApi): PluginHostApi
    @Binds @IntoSet abstract fun ui(impl: PluginUiHostApi): PluginHostApi
    @Binds @IntoSet abstract fun conversation(impl: PluginConversationApi): PluginHostApi
    @Binds @IntoSet abstract fun resources(impl: PluginResourceHostApi): PluginHostApi
}
