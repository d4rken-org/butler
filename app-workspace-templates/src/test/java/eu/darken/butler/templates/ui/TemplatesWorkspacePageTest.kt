package eu.darken.butler.templates.ui

import androidx.compose.ui.test.assertHasClickAction
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import eu.darken.butler.common.ca.CaString
import eu.darken.butler.common.ca.toCaString
import eu.darken.butler.common.compose.PreviewWrapper
import eu.darken.butler.workspace.core.Workspace
import eu.darken.butler.workspace.core.defaultArguments
import eu.darken.butler.workspace.core.icon
import eu.darken.butler.workspace.ui.manager.WorkspaceDesign
import eu.darken.butler.workspace.ui.template.WorkspaceTemplate
import io.kotest.matchers.shouldBe
import org.junit.Test
import testhelpers.ComposeTest

class TemplatesWorkspacePageTest : ComposeTest() {

    private val workspaceId = Workspace.Id()

    private fun template(type: Workspace.Type, title: String) = object : WorkspaceTemplate {
        override val type: Workspace.Type = type
        override val icon = type.icon
        override val title: CaString = title.toCaString()
        override val subtitle: CaString = title.toCaString()
        override val arguments: Workspace.Arguments = type.defaultArguments!!
        override val sortOrder: Int = 0
    }

    private fun state() = TemplatesWorkspaceViewModel.State(
        id = workspaceId,
        isUpgraded = false,
        templates = listOf(template(Workspace.Type.EXPLORER, "Explorer")),
        versionDescription = "1.0.0-test",
    )

    private fun setPage(design: WorkspaceDesign, onNavToSettings: () -> Unit = {}) {
        // Single-pane composes WorkspaceButton, whose default mascot is an infinite Lottie
        // animation. Under Robolectric that floods ShadowTrace per frame and OOMs, so freeze
        // the clock to park the animation (we assert layout/clicks, not animation frames).
        composeTestRule.mainClock.autoAdvance = false
        composeTestRule.setContent {
            PreviewWrapper {
                TemplatesWorkspacePage(
                    workspaceId = workspaceId,
                    design = design,
                    state = state(),
                    onNavToSettings = onNavToSettings,
                )
            }
        }
    }

    @Test
    fun `settings card is shown in single-pane`() {
        setPage(WorkspaceDesign(layout = WorkspaceDesign.Layout.SINGLE))
        composeTestRule
            .onNodeWithTag(TemplatesWorkspacePageDefaults.SETTINGS_CARD_TEST_TAG)
            .assertIsDisplayed()
            .assertHasClickAction()
    }

    @Test
    fun `settings card is hidden in multi-pane`() {
        setPage(WorkspaceDesign(layout = WorkspaceDesign.Layout.DUAL_VERTICAL))
        composeTestRule
            .onNodeWithTag(TemplatesWorkspacePageDefaults.SETTINGS_CARD_TEST_TAG)
            .assertDoesNotExist()
    }

    @Test
    fun `clicking settings card navigates to settings`() {
        var navigated = false
        setPage(WorkspaceDesign(layout = WorkspaceDesign.Layout.SINGLE)) { navigated = true }
        composeTestRule
            .onNodeWithTag(TemplatesWorkspacePageDefaults.SETTINGS_CARD_TEST_TAG)
            .performClick()
        navigated shouldBe true
    }

    // The card visibility gate is `design.isSingle`; assert it holds for every layout so the
    // representative compose check above transitively covers all non-single layouts.
    @Test
    fun `only the single layout counts as single-pane`() {
        WorkspaceDesign.Layout.entries.forEach { layout ->
            WorkspaceDesign(layout = layout).isSingle shouldBe (layout == WorkspaceDesign.Layout.SINGLE)
        }
    }
}
