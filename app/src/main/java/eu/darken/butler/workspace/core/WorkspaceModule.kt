package eu.darken.butler.workspace.core

import dagger.Binds
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import eu.darken.butler.apps.core.AppsWorkspace
import eu.darken.butler.apps.core.details.AppDetailsWorkspace
import eu.darken.butler.editor.core.EditorWorkspace
import eu.darken.butler.explorer.core.ExplorerWorkspace
import eu.darken.butler.saver.core.SaverWorkspace
import eu.darken.butler.searcher.core.SearcherWorkspace
import eu.darken.butler.templates.core.TemplatesWorkspace
import javax.inject.Singleton

@Module
@InstallIn(SingletonComponent::class)
abstract class WorkspaceModule {

    @Binds
    @Singleton
    abstract fun bindWorkspaceProvider(workspaceRepo: WorkspaceRepo): WorkspaceProvider

    @Binds
    @Singleton
    abstract fun bindWorkspaceRemote(workspaceRepo: WorkspaceRepo): WorkspaceRemote

    companion object {
        @Provides
        @Singleton
        fun provideWorkspaceFactoryMap(
            templatesWorkspaceFactory: TemplatesWorkspace.Factory,
            explorerWorkspaceFactory: ExplorerWorkspace.Factory,
            searcherWorkspaceFactory: SearcherWorkspace.Factory,
            editorWorkspaceFactory: EditorWorkspace.Factory,
            appsWorkspaceFactory: AppsWorkspace.Factory,
            appDetailsWorkspaceFactory: AppDetailsWorkspace.Factory,
            saverWorkspaceFactory: SaverWorkspace.Factory,
        ): Map<Workspace.Type, @JvmSuppressWildcards WorkspaceFactory<*>> {
            return Workspace.Type.entries.associateWith { type ->
                when (type) {
                    Workspace.Type.TEMPLATES -> templatesWorkspaceFactory
                    Workspace.Type.EXPLORER -> explorerWorkspaceFactory
                    Workspace.Type.SEARCHER -> searcherWorkspaceFactory
                    Workspace.Type.EDITOR -> editorWorkspaceFactory
                    Workspace.Type.APPS -> appsWorkspaceFactory
                    Workspace.Type.APP_DETAILS -> appDetailsWorkspaceFactory
                    Workspace.Type.SAVER -> saverWorkspaceFactory
                }
            }
        }
    }
}