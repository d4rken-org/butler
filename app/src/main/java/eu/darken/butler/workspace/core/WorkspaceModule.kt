package eu.darken.butler.workspace.core

import dagger.Binds
import dagger.Module
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import javax.inject.Singleton

@Module
@InstallIn(SingletonComponent::class)
abstract class WorkspaceModule {

    @Binds
    @Singleton
    abstract fun bindWorkspaceProvider(workspaceRepo: WorkspaceRepo): WorkspaceProvider
}