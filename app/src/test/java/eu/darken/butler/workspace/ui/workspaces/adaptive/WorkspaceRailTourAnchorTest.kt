package eu.darken.butler.workspace.ui.workspaces.adaptive

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Rect
import eu.darken.butler.common.ca.toCaString
import eu.darken.butler.common.compose.PreviewWrapper
import eu.darken.butler.common.compose.tour.LocalTourTargetRegistry
import eu.darken.butler.common.compose.tour.TourTargetRegistry
import eu.darken.butler.workspace.core.Workspace
import eu.darken.butler.workspace.ui.manager.WorkspaceDesign
import eu.darken.butler.workspace.ui.workspaces.tour.WorkspacePanesTour
import io.kotest.matchers.nulls.shouldNotBeNull
import io.kotest.matchers.shouldBe
import org.junit.Test
import org.robolectric.annotation.Config
import testhelpers.ComposeTest

/**
 * The rail's tab list is tagged once, above the placement branch, so the panes tour finds the same
 * anchor whether the rail runs down the start edge or along the bottom.
 *
 * The fixture carries tabs on purpose: an empty lazy list measures zero on its cross axis, and a
 * zero-size rect is dropped rather than registered - the test would then fail for the fixture
 * instead of for the anchor.
 */
@Config(qualifiers = "w720dp-h1600dp")
class WorkspaceRailTourAnchorTest : ComposeTest() {

    private fun tab(title: String) = Workspace.Info(
        id = Workspace.Id(),
        type = Workspace.Type.EXPLORER,
        title = title.toCaString(),
        lifecycleState = Workspace.LifecycleState.Ready,
    )

    private val tabs = listOf(tab("One"), tab("Two"))

    private fun railListRect(placement: WorkspaceDesign.RailPlacement): Rect? {
        // The Butler button's mascot animates on an endless loop, which never lets the clock idle.
        composeTestRule.mainClock.autoAdvance = false
        val registry = TourTargetRegistry()
        composeTestRule.setContent {
            CompositionLocalProvider(LocalTourTargetRegistry provides registry) {
                PreviewWrapper {
                    Box(modifier = Modifier.fillMaxSize()) {
                        WorkspaceNavigationRail(
                            modifier = Modifier.align(
                                when (placement) {
                                    WorkspaceDesign.RailPlacement.START -> Alignment.CenterStart
                                    WorkspaceDesign.RailPlacement.BOTTOM -> Alignment.BottomCenter
                                },
                            ),
                            workspaces = tabs,
                            selected = emptyMap(),
                            focusedId = null,
                            design = WorkspaceDesign(
                                layout = WorkspaceDesign.Layout.DUAL_VERTICAL,
                                railPlacement = placement,
                            ),
                            onTabAction = {},
                            onPaneAssignment = { _, _ -> },
                            onPaneUnassign = {},
                        )
                    }
                }
            }
        }
        composeTestRule.waitForIdle()
        return registry.get(WorkspacePanesTour.RAIL_LIST_TARGET)
    }

    @Test
    fun `the tab list anchors in the start-edge rail`() {
        val rect = railListRect(WorkspaceDesign.RailPlacement.START).shouldNotBeNull()

        (rect.width > 0f && rect.height > 0f) shouldBe true
    }

    @Test
    fun `the tab list anchors in the bottom rail`() {
        val rect = railListRect(WorkspaceDesign.RailPlacement.BOTTOM).shouldNotBeNull()

        (rect.width > 0f && rect.height > 0f) shouldBe true
    }
}
