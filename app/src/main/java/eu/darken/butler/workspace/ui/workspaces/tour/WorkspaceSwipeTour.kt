package eu.darken.butler.workspace.ui.workspaces.tour

import eu.darken.butler.R
import eu.darken.butler.common.ca.toCaString
import eu.darken.butler.common.compose.tour.GuidedTour
import eu.darken.butler.common.compose.tour.TourDefinition
import eu.darken.butler.common.compose.tour.TourId
import eu.darken.butler.common.compose.tour.TourStep

/**
 * Walks the one-pane tab pager: swiping between tabs, swiping past the last one for a fresh tab, and
 * the side-by-side layouts a wider window opens up.
 *
 * Every step is centerless. A swipe has no element to point at, and the pager fills the window, so
 * anchoring on it would cut a hole the size of the screen and single out nothing.
 *
 * That also decides how the tour can end: only a step carrying a target id ever reaches the host's
 * missing-target grace window, so none of these steps can grace-skip and the tour cannot end itself
 * once the window gains a second pane and its copy stops describing the screen. The layout-change
 * effect in `WorkspaceScreen` is what ends it there.
 *
 * The create-by-swipe step is conditional: the pager adds its trailing placeholder page only while
 * `onDemandWorkspaceCreation` is on. A settings flip mid-tour is safe rather than confusing - the
 * controller adopts a rebuilt definition only when the step-id lists match, so the two variants
 * fail to adopt each other instead of carrying a step index onto a different step.
 */
object WorkspaceSwipeTour : GuidedTour {

    override val id: TourId = TourId("tour.workspaces.swipe")

    fun definition(includeOnDemandStep: Boolean): TourDefinition = TourDefinition(
        id = id,
        clickProtection = true,
        steps = listOfNotNull(
            TourStep(
                stepId = "switch",
                targetId = null,
                title = R.string.tour_swipe_switch_title.toCaString(),
                body = R.string.tour_swipe_switch_body.toCaString(),
            ),
            TourStep(
                stepId = "createBySwipe",
                targetId = null,
                title = R.string.tour_swipe_create_title.toCaString(),
                body = R.string.tour_swipe_create_body.toCaString(),
            ).takeIf { includeOnDemandStep },
            TourStep(
                stepId = "panes",
                targetId = null,
                title = R.string.tour_swipe_panes_title.toCaString(),
                body = R.string.tour_swipe_panes_body.toCaString(),
            ),
        ),
    )
}
