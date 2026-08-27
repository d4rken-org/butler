package eu.darken.butler.workspace.ui.manager.tour

import eu.darken.butler.R
import eu.darken.butler.common.ca.toCaString
import eu.darken.butler.common.compose.tour.GuidedTour
import eu.darken.butler.common.compose.tour.TourDefinition
import eu.darken.butler.common.compose.tour.TourId
import eu.darken.butler.common.compose.tour.TourStep

/**
 * Points at the tab manager's add-tab button, whose long-press shortcut is otherwise unadvertised.
 *
 * No `prepareTarget`: the button hides itself while the grid scrolls down, but the overlay always
 * opens unscrolled, so the anchor is registered by the time the tour starts.
 */
object WorkspaceManagerTour : GuidedTour {

    override val id: TourId = TourId("tour.workspace.manager")

    const val ADD_TAB_TARGET = "workspaceManager.addTab"

    val definition: TourDefinition = TourDefinition(
        id = id,
        clickProtection = true,
        steps = listOf(
            TourStep(
                stepId = "addTab",
                targetId = ADD_TAB_TARGET,
                title = R.string.tour_manager_add_tab_title.toCaString(),
                body = R.string.tour_manager_add_tab_body.toCaString(),
            ),
        ),
    )
}
