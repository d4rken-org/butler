package eu.darken.butler.workspace.core.setup

import dagger.Binds
import dagger.Module
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import eu.darken.butler.setup.core.SetupManager
import eu.darken.butler.setup.core.SetupStateProvider
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class SetupStateProviderImpl @Inject constructor(
    setupManager: SetupManager,
) : SetupStateProvider {

    override val state: Flow<SetupStateProvider.State> = setupManager.moduleStates.map {
        SetupStateProvider.State(modules = it)
    }
}

@Module
@InstallIn(SingletonComponent::class)
abstract class SetupStateProviderModule {
    @Binds
    abstract fun bindSetupStateProvider(impl: SetupStateProviderImpl): SetupStateProvider
}