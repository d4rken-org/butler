package eu.darken.butler.templates.ui

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

object TemplatesWorkspacePageHostEntry : WorkspacePageHostEntry {

    @Composable
    override fun Content(id: Workspace.Id, design: WorkspaceDesign) {
        TemplatesWorkspacePageHost(id = id, design = design)
    }

    @Module
    @InstallIn(SingletonComponent::class)
    object PageHostModule {
        @Provides
        @IntoMap
        @WorkspaceTypeKey(Workspace.Type.TEMPLATES)
        fun entry(): WorkspacePageHostEntry = TemplatesWorkspacePageHostEntry
    }
}
