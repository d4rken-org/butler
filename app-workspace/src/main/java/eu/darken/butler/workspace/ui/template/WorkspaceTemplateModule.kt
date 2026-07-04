package eu.darken.butler.workspace.ui.template

import dagger.Module
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import dagger.multibindings.Multibinds

@Module
@InstallIn(SingletonComponent::class)
abstract class WorkspaceTemplateModule {
    /**
     * Declares the [WorkspaceTemplate] multibinding so consumers in this module (e.g.
     * [eu.darken.butler.workspace.ui.manager.WorkspaceButtonViewModel]) resolve an empty set when no
     * feature module contributes one. Feature modules add entries via `@Provides @IntoSet`.
     */
    @Multibinds
    abstract fun workspaceTemplates(): Set<@JvmSuppressWildcards WorkspaceTemplate>
}
