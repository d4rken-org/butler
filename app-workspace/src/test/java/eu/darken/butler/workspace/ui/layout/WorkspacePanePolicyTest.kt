package eu.darken.butler.workspace.ui.layout

import androidx.compose.runtime.Composable
import androidx.compose.ui.unit.dp
import eu.darken.butler.common.compose.PreviewWrapper
import eu.darken.butler.workspace.core.layout.WorkspacePanelMode
import eu.darken.butler.workspace.ui.manager.rememberWindowSizeInfo
import io.kotest.matchers.shouldBe
import org.junit.Test
import org.robolectric.annotation.Config
import testhelpers.ComposeTest

/**
 * Everyone gets the panes their window is recommended; Pro is what pins more of them than that.
 *
 * The window cases below go through the real size-class chain rather than a hand-written pane count,
 * because that chain is what decides which side of the gate a device lands on.
 */
class WorkspacePanePolicyTest : ComposeTest() {

    @Test
    fun `the modes that follow the recommendation are never gated`() {
        listOf(WorkspacePanelMode.AUTO, WorkspacePanelMode.ADAPTIVE).forEach { mode ->
            mode.paneCount shouldBe null
            (1..4).forEach { recommended -> mode.requiresPro(recommended) shouldBe false }
        }
    }

    /** SINGLE is the Classic surface, not a pinned geometry, so it is outside the gate entirely. */
    @Test
    fun `the classic surface is never gated`() {
        (1..4).forEach { recommended ->
            WorkspacePanelMode.SINGLE.requiresPro(recommended) shouldBe false
        }
    }

    @Test
    fun `a pinned geometry is gated exactly when it pins more panes than the recommendation`() {
        val panesByMode = mapOf(
            WorkspacePanelMode.SINGLE_RAIL to 1,
            WorkspacePanelMode.DUAL_VERTICAL to 2,
            WorkspacePanelMode.DUAL_HORIZONTAL to 2,
            WorkspacePanelMode.TRIPLE_SIDEBAR_LEFT to 3,
            WorkspacePanelMode.TRIPLE_SIDEBAR_RIGHT to 3,
            WorkspacePanelMode.QUAD_GRID to 4,
        )

        panesByMode.keys shouldBe ADAPTIVE_GEOMETRIES.toSet()
        panesByMode.forEach { (mode, panes) ->
            mode.paneCount shouldBe panes
            (1..4).forEach { recommended ->
                mode.requiresPro(recommended) shouldBe (panes > recommended)
            }
        }
    }

    private fun gatedGeometries(): List<WorkspacePanelMode> {
        lateinit var gated: List<WorkspacePanelMode>
        composeTestRule.setContent {
            PreviewWrapper {
                gated = gatedHere()
            }
        }
        composeTestRule.waitForIdle()
        return gated
    }

    @Composable
    private fun gatedHere(): List<WorkspacePanelMode> {
        val recommended = rememberWindowSizeInfo().recommendedPaneCount
        return ADAPTIVE_GEOMETRIES.filter { it.requiresPro(recommended) }
    }

    @Test
    @Config(qualifiers = "w411dp-h891dp-port")
    fun `a phone is offered a dual split but has to pay for it`() {
        gatedGeometries() shouldBe listOf(
            WorkspacePanelMode.DUAL_VERTICAL,
            WorkspacePanelMode.DUAL_HORIZONTAL,
            WorkspacePanelMode.TRIPLE_SIDEBAR_LEFT,
            WorkspacePanelMode.TRIPLE_SIDEBAR_RIGHT,
            WorkspacePanelMode.QUAD_GRID,
        )
    }

    @Test
    @Config(qualifiers = "w800dp-h1280dp-port")
    fun `a tablet in portrait gets two panes free and pays for the triples`() {
        gatedGeometries() shouldBe listOf(
            WorkspacePanelMode.TRIPLE_SIDEBAR_LEFT,
            WorkspacePanelMode.TRIPLE_SIDEBAR_RIGHT,
            WorkspacePanelMode.QUAD_GRID,
        )
    }

    /** A short side of 840dp or more is where the quad grid is offered at all. */
    @Test
    @Config(qualifiers = "w900dp-h1280dp-port")
    fun `a large window gets three panes free and pays only for the quad grid`() {
        gatedGeometries() shouldBe listOf(WorkspacePanelMode.QUAD_GRID)
    }

    @Test
    @Config(qualifiers = "w1280dp-h800dp-land")
    fun `a tablet in landscape pays for nothing it is offered`() {
        val gated = gatedGeometries()
        val offered = offeredGeometries(width = 1280.dp, height = 800.dp, stored = WorkspacePanelMode.AUTO)

        // Only the quad grid sits above this window's three panes, and an 800dp short side is not
        // offered it in the first place.
        gated shouldBe listOf(WorkspacePanelMode.QUAD_GRID)
        offered.any { it in gated } shouldBe false
    }

    /** Under 840dp on the long side only the single-pane rail is offered, so nothing can be gated. */
    @Test
    fun `a small phone is offered nothing that could be gated`() {
        val offered = offeredGeometries(width = 360.dp, height = 640.dp, stored = WorkspacePanelMode.AUTO)

        offered shouldBe listOf(WorkspacePanelMode.SINGLE_RAIL)
        offered.any { it.requiresPro(recommendedPaneCount = 1) } shouldBe false
    }
}
