package eu.darken.butler.developer.ui

import androidx.compose.ui.graphics.vector.ImageVector
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import dagger.multibindings.IntoSet
import eu.darken.butler.common.ca.CaString
import eu.darken.butler.common.ca.toCaString
import eu.darken.butler.common.developer.DeveloperSettings
import eu.darken.butler.developer.R
import eu.darken.butler.developer.core.arguments.DeveloperArguments
import eu.darken.butler.workspace.core.Workspace
import eu.darken.butler.workspace.core.icon
import eu.darken.butler.workspace.ui.template.WorkspaceTemplate
import kotlinx.coroutines.flow.Flow

class DeveloperWorkspaceTemplate(
    developerSettings: DeveloperSettings,
) : WorkspaceTemplate {
    override val title: CaString = R.string.developer_title.toCaString()
    override val subtitle: CaString = R.string.developer_subtitle.toCaString()
    override val arguments: Workspace.Arguments = DeveloperArguments.Default()

    override val type: Workspace.Type
        get() = Workspace.Type.DEVELOPER

    override val icon: ImageVector
        get() = type.icon

    override val sortOrder: Int
        get() = 100

    override val availability: Flow<Boolean> = developerSettings.isDeveloperModeUnlocked.flow

    @Module
    @InstallIn(SingletonComponent::class)
    object TemplateModule {
        @Provides
        @IntoSet
        fun template(settings: DeveloperSettings): WorkspaceTemplate = DeveloperWorkspaceTemplate(settings)
    }
}
