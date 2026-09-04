package eu.darken.butler.templates.ui

import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.test.assertHasClickAction
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.getUnclippedBoundsInRoot
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.unit.dp
import eu.darken.butler.common.ca.CaString
import eu.darken.butler.common.ca.toCaString
import eu.darken.butler.common.compose.PreviewWrapper
import eu.darken.butler.common.compose.tour.LocalTourTargetRegistry
import eu.darken.butler.common.compose.tour.TourTargetRegistry
import eu.darken.butler.templates.ui.tour.TemplatesTour
import eu.darken.butler.workspace.core.Workspace
import eu.darken.butler.workspace.core.defaultArguments
import eu.darken.butler.workspace.core.icon
import eu.darken.butler.workspace.ui.manager.WorkspaceDesign
import eu.darken.butler.workspace.ui.modal.LocalLayerActive
import eu.darken.butler.workspace.ui.template.WorkspaceTemplate
import io.kotest.matchers.shouldBe
import kotlinx.coroutines.runBlocking
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

    private val explorerTemplate = template(Workspace.Type.EXPLORER, "Explorer")
    private val searcherTemplate = template(Workspace.Type.SEARCHER, "Searcher")

    private fun state(
        templates: List<WorkspaceTemplate> = listOf(explorerTemplate),
        customTitle: String? = null,
    ) = TemplatesWorkspaceViewModel.State(
        id = workspaceId,
        isUpgraded = false,
        templates = templates,
        versionDescription = "1.0.0-test",
        customTitle = customTitle,
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

    /**
     * Multi-pane on purpose: it renders neither the settings card nor the Butler button, so no
     * mascot Lottie animation is composed and the clock can keep running for scroll assertions.
     */
    private fun setTourPage(
        registry: TourTargetRegistry,
        templates: List<WorkspaceTemplate> = listOf(explorerTemplate, searcherTemplate),
        layerActive: Boolean = true,
        listState: LazyListState? = null,
        customTitle: String? = null,
        onEditName: () -> Unit = {},
    ) {
        composeTestRule.setContent {
            PreviewWrapper {
                CompositionLocalProvider(
                    LocalTourTargetRegistry provides registry,
                    LocalLayerActive provides layerActive,
                ) {
                    TemplatesWorkspacePage(
                        workspaceId = workspaceId,
                        design = WorkspaceDesign(layout = WorkspaceDesign.Layout.DUAL_VERTICAL),
                        state = state(templates, customTitle = customTitle),
                        listState = listState ?: LazyListState(),
                        onNavToSettings = {},
                        onEditName = onEditName,
                    )
                }
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

    @Test
    fun `header shows the automatic title when the tab has no custom name`() {
        setTourPage(TourTargetRegistry(), customTitle = null)
        composeTestRule.onNodeWithText("New tab").assertIsDisplayed()
    }

    @Test
    fun `header shows the custom name in place of the automatic title`() {
        setTourPage(TourTargetRegistry(), customTitle = "Holiday photos")
        composeTestRule.onNodeWithText("Holiday photos").assertIsDisplayed()
        composeTestRule.onNodeWithText("New tab").assertDoesNotExist()
    }

    @Test
    fun `the standalone name row is gone in the unnamed state`() {
        setTourPage(TourTargetRegistry(), customTitle = null)
        composeTestRule.onNodeWithText("Name this tab").assertDoesNotExist()
    }

    @Test
    fun `clicking the edit icon requests the rename dialog`() {
        var requested = false
        setTourPage(TourTargetRegistry(), onEditName = { requested = true })
        composeTestRule
            .onNodeWithTag(TemplatesWorkspacePageDefaults.EDIT_NAME_TEST_TAG)
            .performClick()
        requested shouldBe true
    }

    @Test
    fun `the first template card registers the tour target, later cards do not`() {
        val registry = TourTargetRegistry()
        setTourPage(registry)
        composeTestRule.waitForIdle()

        val registered = registry.get(TemplatesTour.FIRST_TEMPLATE_TARGET)
        (registered != null) shouldBe true
        // If a later card were tagged too it would overwrite the entry with its own bounds, so
        // comparing against the second card's position is what discriminates first from last.
        val secondCardTop = composeTestRule.onNodeWithText("Searcher").getUnclippedBoundsInRoot().top
        val registeredTop = with(composeTestRule.density) { registered!!.top.toDp() }
        (registeredTop < secondCardTop) shouldBe true
    }

    @Test
    fun `no tour target is registered while the layer is inactive`() {
        // The classic pager composes neighbouring pages: two adjacent Templates tabs would
        // otherwise both register the same id and the tour could anchor on the off-screen one.
        val registry = TourTargetRegistry()
        setTourPage(registry, layerActive = false)
        composeTestRule.waitForIdle()

        registry.has(TemplatesTour.FIRST_TEMPLATE_TARGET) shouldBe false
    }

    @Test
    fun `prepareTarget scrolls an off-screen first template card back into composition`() {
        val registry = TourTargetRegistry()
        val manyTemplates = List(30) { template(Workspace.Type.EXPLORER, "Template $it") }
        // A restored tab can start scrolled well past the template list's head.
        val listState = LazyListState(firstVisibleItemIndex = 20)
        setTourPage(registry, templates = manyTemplates, listState = listState)
        composeTestRule.waitForIdle()

        registry.has(TemplatesTour.FIRST_TEMPLATE_TARGET) shouldBe false

        val prepare = TemplatesTour
            .definition(
                prepareFirstTemplate = {
                    listState.scrollToItem(TemplatesWorkspacePageDefaults.FIRST_TEMPLATE_ITEM_INDEX)
                },
                ownerKey = "pane",
            )
            .steps.first().prepareTarget!!
        runBlocking { prepare() }
        composeTestRule.waitForIdle()

        // A bare `has` check passes even on a stale index: the list composes items backward into
        // the content padding, so an over-scrolled card is still registered, just above the top.
        val registered = registry.get(TemplatesTour.FIRST_TEMPLATE_TARGET)
        (registered != null) shouldBe true
        val top = with(composeTestRule.density) { registered!!.top.toDp() }
        (top >= 0.dp) shouldBe true
    }
}
