package eu.darken.butler.apps.ui.details

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.tooling.preview.PreviewWrapper as ComposePreviewWrapper
import eu.darken.butler.apps.R
import eu.darken.butler.apps.core.details.AppInfo
import eu.darken.butler.apps.ui.apps.preview.AppsMockDataProvider
import eu.darken.butler.common.compose.ButlerPreviewWrapper
import eu.darken.butler.common.compose.Preview2
import eu.darken.butler.common.compose.PreviewWrapper
import eu.darken.butler.workspace.ui.dialogs.PaneBoundAlertDialog
import eu.darken.butler.common.R as CommonR

/** A destructive app-wide package action awaiting confirmation. */
sealed interface AppDetailsConfirmRequest {
    val app: AppInfo

    data class ClearData(override val app: AppInfo) : AppDetailsConfirmRequest

    /** Only raised on the elevated path; without it Android's own dialog does the asking. */
    data class Uninstall(override val app: AppInfo) : AppDetailsConfirmRequest
}

/**
 * Confirmation for clearing data or uninstalling.
 *
 * Pane-bound like every other dialog in this workspace, so it never covers a sibling pane.
 */
@Composable
fun AppDetailsConfirmDialog(
    modifier: Modifier = Modifier,
    request: AppDetailsConfirmRequest,
    onConfirm: () -> Unit,
    onDismiss: () -> Unit,
) {
    val context = LocalContext.current
    val label = request.app.label.get(context)

    PaneBoundAlertDialog(
        onDismissRequest = onDismiss,
        modifier = modifier,
        title = {
            Text(
                text = when (request) {
                    is AppDetailsConfirmRequest.ClearData ->
                        stringResource(R.string.apps_details_confirm_clear_data_title)

                    is AppDetailsConfirmRequest.Uninstall ->
                        stringResource(R.string.apps_details_confirm_uninstall_title)
                },
                style = MaterialTheme.typography.titleLarge,
                fontWeight = FontWeight.Bold,
            )
        },
        text = {
            Text(
                text = when (request) {
                    is AppDetailsConfirmRequest.ClearData ->
                        stringResource(R.string.apps_details_confirm_clear_data_message, label)

                    is AppDetailsConfirmRequest.Uninstall ->
                        stringResource(R.string.apps_details_confirm_uninstall_message, label)
                },
                style = MaterialTheme.typography.bodyMedium,
            )
        },
        confirmButton = {
            TextButton(onClick = onConfirm) {
                Text(
                    text = when (request) {
                        is AppDetailsConfirmRequest.ClearData -> stringResource(R.string.apps_action_clear_data)
                        is AppDetailsConfirmRequest.Uninstall -> stringResource(R.string.apps_action_uninstall)
                    },
                )
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
@ComposePreviewWrapper(ButlerPreviewWrapper::class)
@Composable
private fun AppDetailsConfirmDialogClearDataPreview() {
    AppDetailsConfirmDialog(
        request = AppDetailsConfirmRequest.ClearData(AppsMockDataProvider.Presets.chrome),
        onConfirm = {},
        onDismiss = {},
    )
}

@Preview2
@ComposePreviewWrapper(ButlerPreviewWrapper::class)
@Composable
private fun AppDetailsConfirmDialogUninstallPreview() {
    AppDetailsConfirmDialog(
        request = AppDetailsConfirmRequest.Uninstall(AppsMockDataProvider.Presets.chrome),
        onConfirm = {},
        onDismiss = {},
    )
}
