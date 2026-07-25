package eu.darken.butler.workspace.ui.workspaces.adaptive

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.layout.boundsInRoot
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import eu.darken.butler.common.compose.PreviewWrapper
import eu.darken.butler.workspace.ui.manager.WorkspaceDesign
import eu.darken.butler.workspace.ui.manager.WorkspaceDesign.Layout
import eu.darken.butler.workspace.ui.manager.WorkspaceDesign.PaneEdges
import eu.darken.butler.workspace.ui.workspaces.WorkspacePaneInfo
import io.kotest.assertions.withClue
import io.kotest.matchers.shouldBe
import org.junit.Test
import testhelpers.ComposeTest

/**
 * Cross-checks [WorkspaceDesign.forPane] against the layouts as they actually render.
 *
 * `forPane` is a hand-maintained table of where each layout composable places its panes; nothing in
 * the type system ties the two together. This test measures the rendered panes and fails if the
 * table and the layout ever disagree - which is exactly how the quad grid ended up column-major in
 * the layout but row-major in the table.
 */
class PaneEdgesGeometryTest : ComposeTest() {

    private var container = Rect.Zero
    private val panes = mutableMapOf<Int, Rect>()

    /**
     * Renders with an empty `selected` map on purpose: [WorkspacePaneWrapper] only adds its 2dp
     * focus-border padding when more than one workspace is selected, and that padding would keep
     * pane content from literally touching the container edges.
     */
    private fun setContent(
        passState: () -> Int,
        layoutState: () -> Layout,
        direction: LayoutDirection,
    ) = composeTestRule.setContent {
        PreviewWrapper {
            CompositionLocalProvider(LocalLayoutDirection provides direction) {
                Box(
                    modifier = Modifier
                        .size(width = 800.dp, height = 600.dp)
                        .onGloballyPositioned { container = it.boundsInRoot() },
                ) {
                    // Keyed on the pass so every iteration re-creates (and therefore re-measures)
                    // the panes, even when the layout happens to be unchanged.
                    key(passState()) {
                        AdaptiveWorkspaceContainer(
                            design = WorkspaceDesign(layout = layoutState()),
                            selected = emptyMap<Int, WorkspacePaneInfo>(),
                            focusedTabId = null,
                            dividerPositions = DividerPositions(),
                            onDividerPositionsChange = {},
                            onTabFocus = {},
                        ) { _, paneNumber ->
                            PaneProbe { panes[paneNumber] = it }
                        }
                    }
                }
            }
        }
    }

    @Composable
    private fun PaneProbe(onBounds: (Rect) -> Unit) {
        Box(
            modifier = Modifier
                .fillMaxSize()
                .onGloballyPositioned { onBounds(it.boundsInRoot()) },
        )
    }

    private fun measuredEdges(paneIndex: Int, direction: LayoutDirection): PaneEdges {
        val pane = panes.getValue(paneIndex)
        val touchesLeft = pane.left <= container.left + TOLERANCE_PX
        val touchesRight = pane.right >= container.right - TOLERANCE_PX
        val startIsLeft = direction == LayoutDirection.Ltr
        return PaneEdges(
            touchesTop = pane.top <= container.top + TOLERANCE_PX,
            touchesBottom = pane.bottom >= container.bottom - TOLERANCE_PX,
            touchesStart = if (startIsLeft) touchesLeft else touchesRight,
            touchesEnd = if (startIsLeft) touchesRight else touchesLeft,
        )
    }

    private fun assertAllLayoutsMatchTable(direction: LayoutDirection) {
        var pass by mutableStateOf(0)
        var layout by mutableStateOf(Layout.entries.first())
        setContent(passState = { pass }, layoutState = { layout }, direction = direction)

        Layout.entries.forEachIndexed { index, current ->
            panes.clear()
            layout = current
            pass = index + 1
            composeTestRule.waitForIdle()

            val design = WorkspaceDesign(layout = current)
            withClue("$current / $direction: pane count") {
                panes.keys.sorted() shouldBe (1..design.maxPanes).toList()
            }
            (1..design.maxPanes).forEach { paneIndex ->
                withClue("$current / $direction / pane $paneIndex") {
                    measuredEdges(paneIndex, direction) shouldBe design.forPane(paneIndex).paneEdges
                }
            }
        }
    }

    @Test
    fun `every layout places its panes where the geometry table says - ltr`() {
        assertAllLayoutsMatchTable(LayoutDirection.Ltr)
    }

    @Test
    fun `every layout places its panes where the geometry table says - rtl`() {
        assertAllLayoutsMatchTable(LayoutDirection.Rtl)
    }

    companion object {
        // Panes that don't touch an edge are separated by a divider (>= 8dp), so a 1px slack only
        // absorbs rounding.
        private const val TOLERANCE_PX = 1f
    }
}
