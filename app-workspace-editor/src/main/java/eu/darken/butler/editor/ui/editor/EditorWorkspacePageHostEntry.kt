package eu.darken.butler.editor.ui.editor

import androidx.compose.runtime.Composable
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import dagger.multibindings.IntoMap
import eu.darken.butler.workspace.core.Workspace
import eu.darken.butler.workspace.core.WorkspaceTypeKey
import eu.darken.butler.workspace.ui.WorkspacePageHostEntry
import eu.darken.butler.workspace.ui.manager.WorkspaceDesign

object EditorWorkspacePageHostEntry : WorkspacePageHostEntry {

    @Composable
    override fun Content(id: Workspace.Id, design: WorkspaceDesign) {
        EditorWorkspacePageHost(id = id, design = design)
    }

    @Composable
    override fun Overlays(id: Workspace.Id, design: WorkspaceDesign) {
        EditorWorkspaceOverlaysHost(id = id)
    }

    @Module
    @InstallIn(SingletonComponent::class)
    object PageHostModule {
        @Provides
        @IntoMap
        @WorkspaceTypeKey(Workspace.Type.EDITOR)
        fun entry(): WorkspacePageHostEntry = EditorWorkspacePageHostEntry
    }
}
