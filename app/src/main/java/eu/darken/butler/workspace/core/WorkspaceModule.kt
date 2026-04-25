package eu.darken.butler.workspace.core

import dagger.Binds
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import eu.darken.butler.apps.core.AppsWorkspace
import eu.darken.butler.apps.core.details.AppDetailsWorkspace
import eu.darken.butler.developer.core.DeveloperWorkspace
import eu.darken.butler.editor.core.EditorWorkspace
import eu.darken.butler.explorer.core.ExplorerWorkspace
import eu.darken.butler.history.core.HistoryWorkspace
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
            developerWorkspaceFactory: DeveloperWorkspace.Factory,
            historyWorkspaceFactory: HistoryWorkspace.Factory,
        ): Map<Workspace.Type, @JvmSuppressWildcards WorkspaceFactory<*>> {
            return buildMap {
                put(Workspace.Type.TEMPLATES, templatesWorkspaceFactory)
                put(Workspace.Type.EXPLORER, explorerWorkspaceFactory)
                put(Workspace.Type.SEARCHER, searcherWorkspaceFactory)
                put(Workspace.Type.EDITOR, editorWorkspaceFactory)
                put(Workspace.Type.APPS, appsWorkspaceFactory)
                put(Workspace.Type.APP_DETAILS, appDetailsWorkspaceFactory)
                put(Workspace.Type.SAVER, saverWorkspaceFactory)
                put(Workspace.Type.DEVELOPER, developerWorkspaceFactory)
                put(Workspace.Type.HISTORY, historyWorkspaceFactory)
            }
        }
    }
}