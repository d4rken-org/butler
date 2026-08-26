package eu.darken.butler.workspace.ui.workspaces

import eu.darken.butler.workspace.core.Workspace
import eu.darken.butler.workspace.ui.dialogs.ManagerDialog

/**
 * Which host composes which workspace-targeted dialog.
 *
 * The two hosts are the workspace panes and the tab manager overlay. Every dialog appears in
 * exactly one of the two fields, so double composition is not representable rather than merely
 * guarded against.
 */
internal data class ManagerDialogRouting(
    /** Keyed by the pane that hosts the dialog, which is what a pane looks itself up by. */
    val paneHosted: Map<Workspace.Id, ManagerDialog.WorkspaceTargeted>,
    /** At most one: the manager asks about one tab at a time and takes the next once it resolves. */
    val managerHosted: ManagerDialog.WorkspaceTargeted.CloseConfirmation?,
)

/**
 * A close confirmation is composed in a pane, and the manager overlay covers every pane — so while
 * it is up, a close confirmation it triggered would render underneath it, or off screen entirely
 * when its host is not the tab on display.
 *
 * [tabOrder] only picks which one the manager asks about first, oldest tab first, so the choice
 * cannot move between recompositions while both are pending.
 */
internal fun routeManagerDialogs(
    dialogs: List<ManagerDialog>,
    isManagerOverlayVisible: Boolean,
    tabOrder: List<Workspace.Id>,
): ManagerDialogRouting {
    val targeted = dialogs.filterIsInstance<ManagerDialog.WorkspaceTargeted>()

    if (!isManagerOverlayVisible) {
        return ManagerDialogRouting(
            paneHosted = targeted.associateBy { it.targetWorkspaceId },
            managerHosted = null,
        )
    }

    val closeConfirmations = targeted
        .filterIsInstance<ManagerDialog.WorkspaceTargeted.CloseConfirmation>()

    return ManagerDialogRouting(
        // Batch creation confirmations keep their pane host: they belong to what that pane was
        // doing, and nothing in the manager triggers them.
        paneHosted = targeted
            .filter { it !is ManagerDialog.WorkspaceTargeted.CloseConfirmation }
            .associateBy { it.targetWorkspaceId },
        managerHosted = closeConfirmations.minWithOrNull(
            compareBy(
                { tabOrder.indexOf(it.closingWorkspaceId).takeIf { index -> index >= 0 } ?: tabOrder.size },
                { it.id },
            ),
        ),
    )
}
