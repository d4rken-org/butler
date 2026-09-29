package eu.darken.butler.workspace.ui.operations.details

import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.LifecycleResumeEffect
import eu.darken.butler.common.debug.logging.Logging.Priority.WARN
import eu.darken.butler.common.debug.logging.asLog
import eu.darken.butler.common.debug.logging.log
import eu.darken.butler.common.debug.logging.logTag
import eu.darken.butler.common.pkgs.Pkg
import eu.darken.butler.common.pkgs.getLaunchIntent
import eu.darken.butler.common.pkgs.toPkgId
import eu.darken.butler.workspace.core.operations.Operation
import eu.darken.butler.workspace.ui.operations.OperationDisplay

@Composable
fun OperationDialogHost(
    dialogState: OperationDialogState,
    operations: List<OperationDisplay>,
    onDismissDialog: () -> Unit,
    onCancelOperation: ((Operation.Id) -> Unit)? = null,
    onShareError: ((Operation.Id) -> Unit)? = null,
    onHandleIssue: ((Operation.Id) -> Unit)? = null,
    onShowInHistory: ((Operation.Id) -> Unit)? = null,
    historyEnabled: Boolean = false,
    topInset: Dp = 0.dp,
    bottomInset: Dp = 0.dp,
) {
    when (dialogState) {
        is OperationDialogState.None -> {
            // No dialog to show
        }

        is OperationDialogState.OperationDetails -> {
            // Find the current operation from the operations list
            val currentOperation = operations.find { it.id == dialogState.operationId }

            if (currentOperation != null) {
                val context = LocalContext.current
                val installedPkgId = currentOperation.installedPkgId
                // Re-checked on every resume: the app may have been uninstalled or disabled meanwhile.
                var resumeCount by remember { mutableIntStateOf(0) }
                LifecycleResumeEffect(Unit) {
                    resumeCount++
                    onPauseOrDispose {}
                }
                val canLaunchApp = remember(currentOperation.id, installedPkgId, resumeCount) {
                    installedPkgId?.getLaunchIntent(context) != null
                }

                OperationDetailsSheet(
                    operation = currentOperation,
                    onDismiss = onDismissDialog,
                    topInset = topInset,
                    bottomInset = bottomInset,
                    onCancel = if (currentOperation.canCancel && currentOperation.state is OperationDisplay.State.Running) {
                        {
                            onCancelOperation?.invoke(currentOperation.id)
                        }
                    } else null,
                    onShareError = if (currentOperation.state is OperationDisplay.State.Failed) {
                        {
                            onShareError?.invoke(currentOperation.id)
                            onDismissDialog()
                        }
                    } else null,
                    onHandleIssue = if (currentOperation.state is OperationDisplay.State.Waiting) {
                        {
                            onHandleIssue?.invoke(currentOperation.id)
                            onDismissDialog()
                        }
                    } else null,
                    onLaunchApp = if (installedPkgId != null && canLaunchApp) {
                        {
                            val intent = installedPkgId.getLaunchIntent(context)
                            if (intent == null) {
                                log(TAG, WARN) { "No launch intent for $installedPkgId anymore" }
                            } else {
                                try {
                                    context.startActivity(intent)
                                } catch (e: Exception) {
                                    log(TAG, WARN) { "Failed to launch $installedPkgId: ${e.asLog()}" }
                                }
                            }
                            onDismissDialog()
                        }
                    } else null,
                    // Only what the Operation History has actually recorded: an operation without a
                    // kind is never written there, and neither is anything at all while recording
                    // is off, so there'd be nothing for the History tab to open on.
                    onShowInHistory = if (onShowInHistory != null && historyEnabled && currentOperation.isInHistory) {
                        {
                            onShowInHistory.invoke(currentOperation.id)
                            onDismissDialog()
                        }
                    } else null,
                )
            } else {
                // Operation not found, dismiss the dialog
                onDismissDialog()
            }
        }
    }
}

private val OperationDisplay.isInHistory: Boolean
    get() = kind != null && when (state) {
        is OperationDisplay.State.Completed,
        is OperationDisplay.State.Failed,
        is OperationDisplay.State.Cancelled,
            -> true

        else -> false
    }

/** The app a successful install reported, the only operation whose result can be opened. */
private val OperationDisplay.installedPkgId: Pkg.Id?
    get() {
        if (kind != Operation.Metadata.Kind.INSTALL) return null
        val completed = state as? OperationDisplay.State.Completed ?: return null
        val report = completed.report as? Operation.Report.Packages ?: return null
        return report.outcomes
            .firstOrNull { it.status == Operation.Report.Packages.Outcome.Status.DONE }
            ?.packageName
            ?.toPkgId()
    }

private val TAG = logTag("Workspace", "Operation", "Details")
