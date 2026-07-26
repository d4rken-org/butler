package eu.darken.butler.developer.ui

import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import eu.darken.butler.common.error.ErrorEventHandler
import eu.darken.butler.developer.ui.DeveloperWorkspaceViewModel.Factory
import eu.darken.butler.workspace.core.Workspace
import eu.darken.butler.workspace.core.operations.Operation
import eu.darken.butler.workspace.ui.operations.OperationDisplay
import eu.darken.butler.workspace.ui.operations.details.CancelOperationConfirmationHost

/**
 * Overlay slot of the developer page.
 *
 * Shares the ViewModel with [DeveloperWorkspacePageHost]; the navigation handler and the
 * share-intent collector stay there. The error handler lives here instead, because it renders a
 * dialog that has to be pane-bound.
 */
@Composable
fun DeveloperWorkspaceOverlaysHost(
    id: Workspace.Id,
    vm: DeveloperWorkspaceViewModel = hiltViewModel(
        key = id.longTag,
        creationCallback = { factory: Factory -> factory.create(id = id) }
    ),
) {
    val operationsState by vm.operations.collectAsState(initial = null)
    val cancelConfirmation by vm.cancelOperationConfirmation.collectAsState()

    DeveloperWorkspaceOverlays(
        operations = operationsState?.operations.orEmpty(),
        cancelConfirmationFor = cancelConfirmation,
        onDismissCancelConfirmation = { vm.dismissCancelOperationConfirmation() },
        onCancelOperation = { vm.cancelOperation(it) },
    )

    // Last on purpose: layers stack in composition order, so an error raised while one of
    // this page's own dialogs is up lands on top of it instead of underneath.
    ErrorEventHandler(vm)
}

@Composable
fun DeveloperWorkspaceOverlays(
    operations: List<OperationDisplay> = emptyList(),
    cancelConfirmationFor: Operation.Id? = null,
    onDismissCancelConfirmation: () -> Unit = {},
    onCancelOperation: (Operation.Id) -> Unit = {},
) {
    CancelOperationConfirmationHost(
        pendingId = cancelConfirmationFor,
        operations = operations,
        onDismiss = onDismissCancelConfirmation,
        onConfirm = { operationId ->
            onCancelOperation(operationId)
            onDismissCancelConfirmation()
        },
    )
}
