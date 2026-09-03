package eu.darken.butler.workspace.ui.manager

import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.lazy.grid.LazyGridState
import androidx.compose.foundation.lazy.grid.rememberLazyGridState
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.unit.dp
import eu.darken.butler.common.ca.toCaString
import eu.darken.butler.common.compose.PreviewWrapper
import eu.darken.butler.common.compose.tour.LocalTourTargetRegistry
import eu.darken.butler.common.compose.tour.TourTargetRegistry
import eu.darken.butler.workspace.core.Workspace
import eu.darken.butler.workspace.ui.manager.rows.TEST_TAG_NEW_TAB_CARD
import eu.darken.butler.workspace.ui.manager.tour.WorkspaceManagerTour
import io.kotest.matchers.shouldBe
import io.kotest.matchers.shouldNotBe
import org.junit.Test
import org.robolectric.annotation.Config
import testhelpers.ComposeTest

/**
 * The placeholder is the manager's only route to "pick any tool", so it has to be there whenever
 * selection is off - and it has to be the grid's last item, which is where the tour scrolls to.
 */
@Config(qualifiers = "w720dp-h1600dp")
class WorkspaceManagerGridLayoutNewTabTest : ComposeTest() {

    private val idA = Workspace.Id()
    private val idB = Workspace.Id()

    private fun item(id: Workspace.Id, title: String) = WorkspaceManagerViewModel.WorkspaceItem(
        id = id,
        topId = id,
        type = Workspace.Type.EXPLORER,
        title = title.toCaString(),
        autoTitle = title.toCaString(),
        subtitle = null,
    )

    private fun state(
        workspaces: List<WorkspaceManagerViewModel.WorkspaceItem> = listOf(
            item(idA, "Tab one"),
            item(idB, "Tab two"),
        ),
        selectedIds: Set<Workspace.Id>? = null,
        showBadgeExplanation: Boolean = false,
    ) = WorkspaceManagerViewModel.State(
        workspaces = workspaces,
        selectedIds = selectedIds,
        showBadgeExplanation = showBadgeExplanation,
        // Card thumbnails load through Coil, which has nothing to serve here.
        useLivePreview = false,
    )

    private fun compose(
        state: WorkspaceManagerViewModel.State,
        onNewTabClick: () -> Unit = {},
        registry: TourTargetRegistry = TourTargetRegistry(),
        onGridState: (LazyGridState) -> Unit = {},
    ) {
        composeTestRule.setContent {
            CompositionLocalProvider(LocalTourTargetRegistry provides registry) {
                PreviewWrapper {
                    val gridState = rememberLazyGridState()
                    onGridState(gridState)
                    WorkspaceManagerGridLayout(
                        state = state,
                        paddingValues = PaddingValues(),
                        screenWidth = 720.dp,
                        onCloseWorkspace = {},
                        onReorderWorkspaces = {},
                        onSelectWorkspace = {},
                        onPauseWorkspace = {},
                        onResumeWorkspace = {},
                        onDismissBadgeExplanation = {},
                        onNewTabClick = onNewTabClick,
                        lazyGridState = gridState,
                    )
                }
            }
        }
        composeTestRule.waitForIdle()
    }

    @Test
    fun `the placeholder is shown and creates a tab`() {
        var clicks = 0
        compose(state(), onNewTabClick = { clicks++ })

        composeTestRule.onNodeWithTag(TEST_TAG_NEW_TAB_CARD).performClick()

        clicks shouldBe 1
    }

    @Test
    fun `selection mode hides the placeholder, like the button`() {
        compose(state(selectedIds = setOf(idA)))

        composeTestRule.onNodeWithTag(TEST_TAG_NEW_TAB_CARD).assertDoesNotExist()
    }

    @Test
    fun `an empty manager still offers the placeholder`() {
        compose(state(workspaces = emptyList()))

        composeTestRule.onNodeWithTag(TEST_TAG_NEW_TAB_CARD).assertExists()
    }

    @Test
    fun `the placeholder anchors the tour`() {
        val registry = TourTargetRegistry()
        compose(state(), registry = registry)

        registry.get(WorkspaceManagerTour.NEW_TAB_TARGET) shouldNotBe null
    }

    @Test
    fun `the placeholder is the last item, even below the explanation card`() {
        lateinit var gridState: LazyGridState
        compose(state(showBadgeExplanation = true), onGridState = { gridState = it })

        gridState.layoutInfo.visibleItemsInfo.last().key shouldBe WorkspaceManagerColumnItemKey.NewTab
    }
}
