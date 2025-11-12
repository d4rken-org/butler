package eu.darken.butler.workspace.ui.dialogs

import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.res.stringResource
import eu.darken.butler.common.R as CommonR
import eu.darken.butler.common.compose.Preview2
import eu.darken.butler.common.compose.PreviewWrapper
import eu.darken.butler.workspace.R
import eu.darken.butler.workspace.core.OpenInNewTabsUseCase

@Composable
fun OpenInNewTabsConfirmationDialog(
    analysis: OpenInNewTabsUseCase.AnalysisResult,
    onDismiss: () -> Unit,
    onConfirm: () -> Unit,
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = {
            Text(text = stringResource(R.string.workspace_open_tabs_confirmation_title))
        },
        text = {
            Text(
                text = stringResource(
                    R.string.workspace_open_tabs_confirmation_message,
                    analysis.totalOpenableCount
                )
            )
        },
        confirmButton = {
            TextButton(onClick = onConfirm) {
                Text(text = stringResource(CommonR.string.general_open_action))
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text(text = stringResource(CommonR.string.general_cancel_action))
            }
        },
    )
}

@Preview2
@Composable
private fun OpenInNewTabsConfirmationDialogPreview() {
    PreviewWrapper {
        OpenInNewTabsConfirmationDialog(
            analysis = OpenInNewTabsUseCase.AnalysisResult(
                directoriesToOpen = emptyList(),
                textFilesToOpen = emptyList(),
                skippedCount = 2,
                totalOpenableCount = 15,
                needsConfirmation = true,
            ),
            onDismiss = {},
            onConfirm = {},
        )
    }
}
