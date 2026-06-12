package eu.darken.butler.history.ui

import androidx.compose.ui.graphics.vector.ImageVector
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import dagger.multibindings.IntoSet
import eu.darken.butler.common.ca.CaString
import eu.darken.butler.common.ca.toCaString
import eu.darken.butler.history.R
import eu.darken.butler.history.core.arguments.HistoryArguments
import eu.darken.butler.workspace.core.Workspace
import eu.darken.butler.workspace.core.icon
import eu.darken.butler.workspace.ui.template.WorkspaceTemplate

data class HistoryWorkspaceTemplate(
    override val title: CaString = R.string.history_workspace_title.toCaString(),
    override val subtitle: CaString = R.string.history_workspace_subtitle.toCaString(),
    override val arguments: Workspace.Arguments = HistoryArguments.Default(),
) : WorkspaceTemplate {
    override val type: Workspace.Type
        get() = Workspace.Type.HISTORY

    override val icon: ImageVector
        get() = type.icon

    override val sortOrder: Int
        get() = 50

    @Module
    @InstallIn(SingletonComponent::class)
    object TemplateModule {
        @Provides
        @IntoSet
        fun template(): WorkspaceTemplate = HistoryWorkspaceTemplate()
    }
}
