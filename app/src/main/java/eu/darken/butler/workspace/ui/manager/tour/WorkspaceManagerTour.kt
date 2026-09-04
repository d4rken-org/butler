package eu.darken.butler.workspace.ui.manager.tour

import eu.darken.butler.R
import eu.darken.butler.common.ca.toCaString
import eu.darken.butler.common.compose.tour.GuidedTour
import eu.darken.butler.common.compose.tour.TourDefinition
import eu.darken.butler.common.compose.tour.TourId
import eu.darken.butler.common.compose.tour.TourStep

/**
 * Walks the tab manager: the button's shortcut menu, then the two card long-presses that are
 * otherwise undiscoverable — the title row reorders, the preview below it starts a selection — and
 * finally the card that opens a new tab.
 *
 * [REORDER_TARGET] and [SELECT_TARGET] are registered by exactly one card, the first in the grid.
 * That is what keeps the ids unique: tagging every card would file one rect per tab under a single
 * id, and the last one positioned would win. [NEW_TAB_TARGET] belongs to the placeholder card,
 * of which there is only ever one.
 *
 * The tour only starts once the grid holds at least one card, so those two anchors exist.
 *
 * Every step carries a `prepareTarget`. The status card above the cards is a wrapping `FlowRow` of
 * chips and each preview is a fixed 160dp, so in a short window at large font scale the first
 * card's preview sits below the viewport and never registers. The button step prepares in the
 * other direction: it hides on scroll, so stepping back to it has to restore the top of the grid.
 * The placeholder sits at the end of the tab cards with the explanation cards below it, so its
 * step scrolls to the end of the grid and, if needed, one item back.
 */
object WorkspaceManagerTour : GuidedTour {

    override val id: TourId = TourId("tour.workspace.manager")

    const val ADD_TAB_TARGET = "workspaceManager.addTab"
    const val REORDER_TARGET = "workspaceManager.cardHeader"
    const val SELECT_TARGET = "workspaceManager.cardPreview"
    const val NEW_TAB_TARGET = "workspaceManager.newTabCard"

    fun definition(
        prepareAddTab: suspend () -> Unit,
        prepareFirstCard: suspend () -> Unit,
        prepareNewTab: suspend () -> Unit,
    ): TourDefinition = TourDefinition(
        id = id,
        clickProtection = true,
        steps = listOf(
            TourStep(
                stepId = "addTab",
                targetId = ADD_TAB_TARGET,
                title = R.string.tour_manager_fab_title.toCaString(),
                body = R.string.tour_manager_fab_body.toCaString(),
                prepareTarget = prepareAddTab,
            ),
            TourStep(
                stepId = "reorder",
                targetId = REORDER_TARGET,
                title = R.string.tour_manager_reorder_title.toCaString(),
                body = R.string.tour_manager_reorder_body.toCaString(),
                prepareTarget = prepareFirstCard,
            ),
            TourStep(
                stepId = "select",
                targetId = SELECT_TARGET,
                title = R.string.tour_manager_select_title.toCaString(),
                body = R.string.tour_manager_select_body.toCaString(),
                prepareTarget = prepareFirstCard,
            ),
            TourStep(
                stepId = "newTab",
                targetId = NEW_TAB_TARGET,
                title = R.string.tour_manager_new_tab_title.toCaString(),
                body = R.string.tour_manager_new_tab_body.toCaString(),
                prepareTarget = prepareNewTab,
            ),
        ),
    )
}
