package eu.darken.butler.explorer.ui

import androidx.compose.ui.graphics.vector.ImageVector
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import dagger.multibindings.IntoSet
import eu.darken.butler.common.ca.CaString
import eu.darken.butler.common.ca.toCaString
import eu.darken.butler.explorer.R
import eu.darken.butler.explorer.core.arguments.ExplorerArguments
import eu.darken.butler.workspace.core.Workspace
import eu.darken.butler.workspace.core.icon
import eu.darken.butler.workspace.ui.template.WorkspaceTemplate

data class ExplorerWorkspaceTemplate(
    override val title: CaString = R.string.explorer_title.toCaString(),
    override val subtitle: CaString = R.string.explorer_subtitle.toCaString(),
    override val arguments: Workspace.Arguments = ExplorerArguments.Default(),
) : WorkspaceTemplate {
    override val type: Workspace.Type
        get() = Workspace.Type.EXPLORER

    override val icon: ImageVector
        get() = type.icon

    override val sortOrder: Int
        get() = 10

    override val isQuickCreate: Boolean
        get() = true

    @Module
    @InstallIn(SingletonComponent::class)
    object TemplateModule {
        @Provides
        @IntoSet
        fun template(): WorkspaceTemplate = ExplorerWorkspaceTemplate()
    }
}