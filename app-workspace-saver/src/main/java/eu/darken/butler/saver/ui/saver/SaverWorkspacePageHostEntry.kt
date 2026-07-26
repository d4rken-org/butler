package eu.darken.butler.saver.ui.saver

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

object SaverWorkspacePageHostEntry : WorkspacePageHostEntry {

    @Composable
    override fun Content(id: Workspace.Id, design: WorkspaceDesign) {
        SaverWorkspacePageHost(id = id, design = design)
    }

    @Composable
    override fun Overlays(id: Workspace.Id, design: WorkspaceDesign) {
        // No pane-scoped overlays
    }

    @Module
    @InstallIn(SingletonComponent::class)
    object PageHostModule {
        @Provides
        @IntoMap
        @WorkspaceTypeKey(Workspace.Type.SAVER)
        fun entry(): WorkspacePageHostEntry = SaverWorkspacePageHostEntry
    }
}
