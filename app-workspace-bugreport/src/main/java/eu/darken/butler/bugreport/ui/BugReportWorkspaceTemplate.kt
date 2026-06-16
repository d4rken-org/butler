package eu.darken.butler.bugreport.ui

import androidx.compose.ui.graphics.vector.ImageVector
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import dagger.multibindings.IntoSet
import eu.darken.butler.bugreport.R
import eu.darken.butler.common.BuildConfigWrap
import eu.darken.butler.common.ca.CaString
import eu.darken.butler.common.ca.toCaString
import eu.darken.butler.workspace.contracts.bugreport.BugReportArguments
import eu.darken.butler.workspace.core.Workspace
import eu.darken.butler.workspace.core.icon
import eu.darken.butler.workspace.ui.template.WorkspaceTemplate
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flowOf

data class BugReportWorkspaceTemplate(
    override val title: CaString = R.string.bugreport_workspace_title.toCaString(),
    override val subtitle: CaString = R.string.bugreport_workspace_subtitle.toCaString(),
    override val arguments: Workspace.Arguments = BugReportArguments.Default(),
) : WorkspaceTemplate {
    override val type: Workspace.Type
        get() = Workspace.Type.BUG_REPORT

    override val icon: ImageVector
        get() = type.icon

    override val sortOrder: Int
        get() = 110

    // Manual discovery is for testers/devs; production users only ever see this workspace when it
    // auto-surfaces after a crash (which is independent of this template).
    override val availability: Flow<Boolean>
        get() = flowOf(BuildConfigWrap.BUILD_TYPE != BuildConfigWrap.BuildType.RELEASE)

    @Module
    @InstallIn(SingletonComponent::class)
    object TemplateModule {
        @Provides
        @IntoSet
        fun template(): WorkspaceTemplate = BugReportWorkspaceTemplate()
    }
}
