package eu.darken.butler.editor.ui

import androidx.compose.ui.graphics.vector.ImageVector
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import dagger.multibindings.IntoSet
import eu.darken.butler.common.ca.CaString
import eu.darken.butler.common.ca.toCaString
import eu.darken.butler.editor.R
import eu.darken.butler.workspace.contracts.editor.EditorArguments
import eu.darken.butler.workspace.core.Workspace
import eu.darken.butler.workspace.core.icon
import eu.darken.butler.workspace.ui.template.WorkspaceTemplate


data class EditorWorkspaceTemplate(
    override val title: CaString = R.string.editor_title.toCaString(),
    override val subtitle: CaString = R.string.editor_subtitle.toCaString(),
    override val arguments: Workspace.Arguments = EditorArguments.Default(),
) : WorkspaceTemplate {
    override val type: Workspace.Type
        get() = Workspace.Type.EDITOR

    override val icon: ImageVector
        get() = type.icon

    override val sortOrder: Int
        get() = 30

    override val isQuickCreate: Boolean
        get() = true

    @Module
    @InstallIn(SingletonComponent::class)
    object TemplateModule {
        @Provides
        @IntoSet
        fun template(): WorkspaceTemplate = EditorWorkspaceTemplate()
    }
}