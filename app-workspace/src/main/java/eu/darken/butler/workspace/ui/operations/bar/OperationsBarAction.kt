package eu.darken.butler.workspace.ui.operations.bar

import eu.darken.butler.workspace.core.operations.Operation

/**
 * What the user did on a [WorkspaceOperationsFloatingBar], as a single typed channel.
 *
 * [ShowConflict] and [ShowDetails] are produced by the wrapper's own routing, never passed in: the
 * waiting-to-conflict decision stays in the one place that owns it.
 */
sealed interface OperationsBarAction {
    data class RequestCancel(val id: Operation.Id) : OperationsBarAction
    data class Dismiss(val id: Operation.Id) : OperationsBarAction
    data class ShowConflict(val id: Operation.Id) : OperationsBarAction
    data class ShowDetails(val id: Operation.Id) : OperationsBarAction
    data object ClearCompleted : OperationsBarAction
}
