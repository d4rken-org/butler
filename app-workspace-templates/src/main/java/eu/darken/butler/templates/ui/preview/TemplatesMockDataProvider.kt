package eu.darken.butler.templates.ui.preview

import eu.darken.butler.common.ca.CaString
import eu.darken.butler.common.ca.toCaString
import eu.darken.butler.templates.ui.TemplatesWorkspaceViewModel
import eu.darken.butler.workspace.contracts.templates.TemplatesArguments
import eu.darken.butler.workspace.core.Workspace
import eu.darken.butler.workspace.core.icon
import eu.darken.butler.workspace.ui.template.WorkspaceTemplate

/**
 * Mock data provider for Templates compose previews and Play Store screenshots.
 */
object TemplatesMockDataProvider {

    fun createMockState(
        workspaceId: Workspace.Id,
        customTitle: String? = null,
        includeTooling: Boolean = false,
    ) = TemplatesWorkspaceViewModel.State(
        id = workspaceId,
        customTitle = customTitle,
        // The tooling variant is a short list on purpose: the page is a LazyColumn whose bottom is
        // overlaid by the floating settings card, so extra cards would render below the fold.
        templates = if (includeTooling) {
            listOf(
                createMockTemplate(Workspace.Type.EXPLORER, "Explorer", "Browse and manage files", 10),
                createMockTemplate(Workspace.Type.SEARCHER, "Searcher", "Find files and folders", 20),
                createMockTemplate(
                    Workspace.Type.DEVELOPER,
                    "Developer",
                    "Tools and diagnostics",
                    100,
                    accent = WorkspaceTemplate.Accent.TERTIARY,
                ),
                createMockTemplate(
                    Workspace.Type.BUG_REPORT,
                    "Bug report",
                    "Report a problem",
                    110,
                    accent = WorkspaceTemplate.Accent.TERTIARY,
                ),
            )
        } else {
            listOf(
                createMockTemplate(Workspace.Type.EXPLORER, "Explorer", "Browse and manage files", 10),
                createMockTemplate(Workspace.Type.SEARCHER, "Searcher", "Find files and folders", 20),
                createMockTemplate(Workspace.Type.EDITOR, "Editor", "View and edit text files", 30),
                createMockTemplate(Workspace.Type.APPS, "Apps", "Manage installed apps", 40),
            )
        },
        isUpgraded = true,
        versionDescription = "1.0.0-preview",
    )

    fun createMockTemplate(
        type: Workspace.Type,
        title: String,
        subtitle: String,
        order: Int,
        accent: WorkspaceTemplate.Accent = WorkspaceTemplate.Accent.DEFAULT,
    ): WorkspaceTemplate = object : WorkspaceTemplate {
        override val type: Workspace.Type = type
        override val icon = type.icon
        override val title: CaString = title.toCaString()
        override val subtitle: CaString = subtitle.toCaString()
        override val arguments: Workspace.Arguments = TemplatesArguments.Default()
        override val sortOrder: Int = order
        override val accent: WorkspaceTemplate.Accent = accent
    }
}
