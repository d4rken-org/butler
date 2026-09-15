package eu.darken.butler.workspace.ui.workspaces

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.unit.IntSize
import eu.darken.butler.common.ca.toCaString
import eu.darken.butler.common.compose.PreviewWrapper
import eu.darken.butler.common.compose.tour.LocalTourTargetRegistry
import eu.darken.butler.common.compose.tour.TourTargetRegistry
import eu.darken.butler.workspace.core.Workspace
import eu.darken.butler.workspace.ui.manager.WorkspaceDesign
import eu.darken.butler.workspace.ui.workspaces.adaptive.DividerPositions
import eu.darken.butler.workspace.ui.workspaces.adaptive.layouts.DualHorizontalLayout
import eu.darken.butler.workspace.ui.workspaces.adaptive.layouts.DualVerticalLayout
import eu.darken.butler.workspace.ui.workspaces.adaptive.layouts.QuadGridLayout
import eu.darken.butler.workspace.ui.workspaces.adaptive.layouts.TripleMainLeftLayout
import eu.darken.butler.workspace.ui.workspaces.adaptive.layouts.TripleMainRightLayout
import eu.darken.butler.workspace.ui.workspaces.tour.WorkspacePanesTour
import io.kotest.matchers.nulls.shouldNotBeNull
import io.kotest.matchers.shouldBe
import org.junit.Test
import org.robolectric.annotation.Config
import testhelpers.ComposeTest

/**
 * Every multi-pane layout has to offer the panes tour a divider to point at, and it has to be the
 * primary split: the three-and-four-pane layouts carry a second, secondary divider, and tagging both
 * would file two rects under one id with whichever positioned last winning.
 *
 * Which one was tagged is read off the rect's orientation - the primary split runs along the layout's
 * main axis, the secondary one across it.
 */
@Config(qualifiers = "w720dp-h1600dp")
class PaneDividerTourAnchorTest : ComposeTest() {

    private fun paneInfo(title: String) = Workspace.Info(
        id = Workspace.Id(),
        type = Workspace.Type.EXPLORER,
        title = title.toCaString(),
        lifecycleState = Workspace.LifecycleState.Ready,
    ).asPaneInfo()

    private val selected = (0..3).associateWith { paneInfo("Pane $it") }

    private fun dividerRect(layout: WorkspaceDesign.Layout): Rect? {
        val registry = TourTargetRegistry()
        composeTestRule.setContent {
            CompositionLocalProvider(LocalTourTargetRegistry provides registry) {
                PreviewWrapper {
                    val positions = DividerPositions()
                    val containerSize = IntSize(1440, 3200)
                    val paneContent: @Composable (WorkspacePaneInfo?, Int) -> Unit =
                        { _, _ -> Box(modifier = Modifier.fillMaxSize()) }
                    when (layout) {
                        WorkspaceDesign.Layout.SINGLE -> error("$layout composes no divider")
                        WorkspaceDesign.Layout.DUAL_VERTICAL -> DualVerticalLayout(
                            selected = selected,
                            focusedTabId = null,
                            dividerPositions = positions,
                            containerSize = containerSize,
                            showPaneNumbers = false,
                            showPaneOverlay = false,
                            onTabFocus = {},
                            onDividerPositionsChange = {},
                            paneContent = paneContent,
                        )
                        WorkspaceDesign.Layout.DUAL_HORIZONTAL -> DualHorizontalLayout(
                            selected = selected,
                            focusedTabId = null,
                            dividerPositions = positions,
                            containerSize = containerSize,
                            showPaneNumbers = false,
                            showPaneOverlay = false,
                            onTabFocus = {},
                            onDividerPositionsChange = {},
                            paneContent = paneContent,
                        )
                        WorkspaceDesign.Layout.TRIPLE_MAIN_LEFT -> TripleMainLeftLayout(
                            selected = selected,
                            focusedTabId = null,
                            dividerPositions = positions,
                            containerSize = containerSize,
                            showPaneNumbers = false,
                            showPaneOverlay = false,
                            onTabFocus = {},
                            onDividerPositionsChange = {},
                            paneContent = paneContent,
                        )
                        WorkspaceDesign.Layout.TRIPLE_MAIN_RIGHT -> TripleMainRightLayout(
                            selected = selected,
                            focusedTabId = null,
                            dividerPositions = positions,
                            containerSize = containerSize,
                            showPaneNumbers = false,
                            showPaneOverlay = false,
                            onTabFocus = {},
                            onDividerPositionsChange = {},
                            paneContent = paneContent,
                        )
                        WorkspaceDesign.Layout.QUAD_GRID -> QuadGridLayout(
                            selected = selected,
                            focusedTabId = null,
                            dividerPositions = positions,
                            containerSize = containerSize,
                            showPaneNumbers = false,
                            showPaneOverlay = false,
                            onTabFocus = {},
                            onDividerPositionsChange = {},
                            paneContent = paneContent,
                        )
                    }
                }
            }
        }
        composeTestRule.waitForIdle()
        return registry.get(WorkspacePanesTour.PANE_DIVIDER_TARGET)
    }

    private fun Rect.isTallerThanWide(): Boolean = height > width

    @Test
    fun `the dual vertical layout tags the split between its two panes`() {
        val rect = dividerRect(WorkspaceDesign.Layout.DUAL_VERTICAL).shouldNotBeNull()

        (rect.width > 0f && rect.height > 0f) shouldBe true
        rect.isTallerThanWide() shouldBe true
    }

    @Test
    fun `the dual horizontal layout tags the split between its two panes`() {
        val rect = dividerRect(WorkspaceDesign.Layout.DUAL_HORIZONTAL).shouldNotBeNull()

        (rect.width > 0f && rect.height > 0f) shouldBe true
        // The one layout whose primary split runs across the window rather than down it.
        rect.isTallerThanWide() shouldBe false
    }

    @Test
    fun `the triple main left layout tags the main split, not the one inside the column`() {
        val rect = dividerRect(WorkspaceDesign.Layout.TRIPLE_MAIN_LEFT).shouldNotBeNull()

        (rect.width > 0f && rect.height > 0f) shouldBe true
        rect.isTallerThanWide() shouldBe true
    }

    @Test
    fun `the triple main right layout tags the main split, not the one inside the column`() {
        val rect = dividerRect(WorkspaceDesign.Layout.TRIPLE_MAIN_RIGHT).shouldNotBeNull()

        (rect.width > 0f && rect.height > 0f) shouldBe true
        rect.isTallerThanWide() shouldBe true
    }

    @Test
    fun `the quad grid tags the centre split between its two columns`() {
        val rect = dividerRect(WorkspaceDesign.Layout.QUAD_GRID).shouldNotBeNull()

        (rect.width > 0f && rect.height > 0f) shouldBe true
        rect.isTallerThanWide() shouldBe true
    }
}
