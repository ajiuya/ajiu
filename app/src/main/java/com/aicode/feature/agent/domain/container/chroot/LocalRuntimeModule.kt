package com.aicode.feature.agent.domain.container.chroot

import com.aicode.feature.agent.domain.container.runtime.LocalRuntimeCommands
import com.aicode.feature.agent.domain.container.runtime.LocalRuntimeUserActions
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent

@Module
@InstallIn(SingletonComponent::class)
internal object LocalRuntimeModule {
    @Provides
    fun commands(facade: ChrootRuntimeFacade): LocalRuntimeCommands = facade

    @Provides
    fun userActions(facade: ChrootRuntimeFacade): LocalRuntimeUserActions = facade
}
