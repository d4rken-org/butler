package eu.darken.butler.workspace.ui.workspaces

import eu.darken.butler.workspace.core.Workspace
import eu.darken.butler.workspace.ui.dialogs.ManagerDialog

/**
 * Which host composes which dialog.
 *
 * The four hosts are the full-screen modal window, the tab manager overlay, the workspace panes and
 * the screen itself. Every dialog appears in exactly one of the fields, so double composition is not
 * representable rather than merely guarded against.
 */
internal data class ManagerDialogRouting(
    /** Keyed by the pane that hosts the dialog, which is what a pane looks itself up by. */
    val paneHosted: Map<Workspace.Id, ManagerDialog.WorkspaceTargeted>,
    /** At most one: the manager asks about one tab at a time and takes the next once it resolves. */
    val managerHosted: ManagerDialog.WorkspaceTargeted.CloseConfirmation?,
    /** At most one: the modal window renders a single workspace and one dialog layer above it. */
    val modalHosted: ManagerDialog.WorkspaceTargeted.CloseConfirmation?,
    /** At most one: a window dialog covers the screen, so a second one would render behind it. */
    val globalHosted: ManagerDialog.Global.CloseConfirmation?,
)

/**
 * A close confirmation is composed in a pane, and the manager overlay covers every pane — so while
 * it is up, a close confirmation it triggered would render underneath it, or off screen entirely
 * when its host is not the tab on display.
 *
 * [fullScreenModalId] wins over the overlay: that modal is a platform window drawn above it, so a
 * dialog the manager composed would sit behind the very workspace it is anchored to.
 *
 * [tabOrder] only picks which one a host asks about first, oldest tab first, so the choice cannot
 * move between recompositions while several are pending.
 */
internal fun routeManagerDialogs(
    dialogs: List<ManagerDialog>,
    isManagerOverlayVisible: Boolean,
    tabOrder: List<Workspace.Id>,
    fullScreenModalId: Workspace.Id? = null,
): ManagerDialogRouting {
    val targeted = dialogs.filterIsInstance<ManagerDialog.WorkspaceTargeted>()

    val globalHosted = dialogs
        .filterIsInstance<ManagerDialog.Global.CloseConfirmation>()
        .firstToAsk(tabOrder) { it.closingWorkspaceId }

    val modalAnchored = targeted
        .filterIsInstance<ManagerDialog.WorkspaceTargeted.CloseConfirmation>()
        .filter { fullScreenModalId != null && it.targetWorkspaceId == fullScreenModalId }
    val modalAnchoredIds = modalAnchored.mapTo(mutableSetOf()) { it.id }
    val remaining = targeted.filter { it.id !in modalAnchoredIds }

    if (!isManagerOverlayVisible) {
        return ManagerDialogRouting(
            paneHosted = remaining.associateBy { it.targetWorkspaceId },
            managerHosted = null,
            modalHosted = modalAnchored.firstToAsk(tabOrder) { it.closingWorkspaceId },
            globalHosted = globalHosted,
        )
    }

    return ManagerDialogRouting(
        // Batch creation confirmations keep their pane host: they belong to what that pane was
        // doing, and nothing in the manager triggers them.
        paneHosted = remaining
            .filter { it !is ManagerDialog.WorkspaceTargeted.CloseConfirmation }
            .associateBy { it.targetWorkspaceId },
        managerHosted = remaining
            .filterIsInstance<ManagerDialog.WorkspaceTargeted.CloseConfirmation>()
            .firstToAsk(tabOrder) { it.closingWorkspaceId },
        modalHosted = modalAnchored.firstToAsk(tabOrder) { it.closingWorkspaceId },
        globalHosted = globalHosted,
    )
}

/** Oldest tab first, tabs the order does not list last, the confirmation id as the tie-break. */
private fun <T : ManagerDialog> List<T>.firstToAsk(
    tabOrder: List<Workspace.Id>,
    closingWorkspaceId: (T) -> Workspace.Id,
): T? = minWithOrNull(
    compareBy(
        { tabOrder.indexOf(closingWorkspaceId(it)).takeIf { index -> index >= 0 } ?: tabOrder.size },
        { it.id },
    ),
)
