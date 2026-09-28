package eu.darken.butler.workspace.ui.workspaces.tour

import eu.darken.butler.R
import eu.darken.butler.common.ca.toCaString
import eu.darken.butler.common.compose.tour.GuidedTour
import eu.darken.butler.common.compose.tour.TourDefinition
import eu.darken.butler.common.compose.tour.TourId
import eu.darken.butler.common.compose.tour.TourStep
import eu.darken.butler.workspace.ui.tour.WorkspaceTourTargets

/**
 * Walks the multi-pane surface: the rail's tab list, the divider between the panes, and where the
 * arrangement itself is changed.
 *
 * [PANE_DIVIDER_TARGET] is registered by exactly one divider per layout, the primary split. Tagging
 * every divider would file one rect per split under a single id and the last one positioned would
 * win, which is the same uniqueness trap the manager tour's card anchors avoid. [RAIL_LIST_TARGET]
 * needs no such rule: one rail is composed per screen.
 *
 * No step carries a `prepareTarget`, because none of the three anchors can be out of bounds while
 * its layout is on screen: the rail's list is the rail's weighted child and is laid out whenever the
 * rail is, the tagged divider is a direct child of the layout composable, and the Butler button is
 * unconditional rail chrome. None of them sits inside a scrolling viewport, unlike the manager grid
 * and the templates list, which is why those tours need hooks and this one does not.
 *
 * The layout step anchors on the Butler button and names the menu row in copy rather than pointing
 * at it: the row lives in a `DropdownMenu`, which has a Compose root of its own, and the host reads
 * anchor rects from the root it is mounted at.
 *
 * Ending the tour when the window goes back to a single pane is not something this tour can do on
 * its own. Its rail and divider anchors do unregister, but [WorkspaceTourTargets.BUTLER_BUTTON] does
 * not: the Templates page registers the same id for the button it draws when there is no rail, so
 * the layout step would render over a single pane and describe rearranging panes that are not
 * there. The layout-change effect in `WorkspaceScreen` is what prevents it.
 * A tour ended that way keeps the steps it already showed in the controller's rendered set, so it
 * is not persisted as completed but stays suppressed for the rest of the process and comes back
 * after an app restart.
 */
object WorkspacePanesTour : GuidedTour {

    override val id: TourId = TourId("tour.workspaces.panes")

    const val RAIL_LIST_TARGET = "workspaces.railList"
    const val PANE_DIVIDER_TARGET = "workspaces.paneDivider"

    fun definition(): TourDefinition = TourDefinition(
        id = id,
        clickProtection = true,
        steps = listOf(
            TourStep(
                stepId = "railList",
                targetId = RAIL_LIST_TARGET,
                title = R.string.tour_panes_rail_title.toCaString(),
                body = R.string.tour_panes_rail_body.toCaString(),
            ),
            TourStep(
                stepId = "divider",
                targetId = PANE_DIVIDER_TARGET,
                title = R.string.tour_panes_divider_title.toCaString(),
                body = R.string.tour_panes_divider_body.toCaString(),
            ),
            TourStep(
                stepId = "layout",
                targetId = WorkspaceTourTargets.BUTLER_BUTTON,
                title = R.string.tour_panes_layout_title.toCaString(),
                body = R.string.tour_panes_layout_body.toCaString(),
            ),
        ),
    )
}
