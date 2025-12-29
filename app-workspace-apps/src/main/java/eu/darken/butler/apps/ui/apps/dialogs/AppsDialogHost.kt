package eu.darken.butler.apps.ui.apps.dialogs

import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import eu.darken.butler.apps.core.engine.AppItem
import eu.darken.butler.apps.core.SortSettings
import eu.darken.butler.apps.core.TagFilterConfig
import eu.darken.butler.apps.ui.apps.AppsAction

@Composable
fun AppsDialogHost(
    modifier: Modifier = Modifier,
    dialogState: AppsDialogState,
    onDismiss: () -> Unit,
    onAction: (AppsAction) -> Unit,
    onFilterApply: (TagFilterConfig) -> Unit,
    onSortApply: (SortSettings) -> Unit,
    onConfirmEnable: (List<AppItem>) -> Unit,
    onConfirmDisable: (List<AppItem>) -> Unit,
) {
    when (dialogState) {
        is AppsDialogState.None -> {
            // No dialog to show
        }

        is AppsDialogState.AppDetails -> {
            AppDetailsDialog(
                modifier = modifier,
                app = dialogState.app,
                availablePaths = dialogState.availablePaths,
                onDismiss = onDismiss,
                onAction = onAction,
            )
        }

        is AppsDialogState.FilterOptions -> {
            FilterOptionsDialog(
                modifier = modifier,
                currentFilter = dialogState.currentFilter,
                availableTags = dialogState.availableTags,
                onDismiss = onDismiss,
                onApply = onFilterApply,
            )
        }

        is AppsDialogState.SortOptions -> {
            SortOptionsDialog(
                currentSortSettings = dialogState.currentSortSettings,
                onDismiss = onDismiss,
                onApply = onSortApply,
                modifier = modifier,
            )
        }

        is AppsDialogState.ConfirmDisable -> {
            ConfirmationDialog(
                title = "Disable apps?",
                message = "The following apps will be disabled:",
                apps = dialogState.apps,
                confirmButtonText = "Disable",
                isDestructive = false,
                onConfirm = { onConfirmDisable(dialogState.apps) },
                onDismiss = onDismiss,
                modifier = modifier,
            )
        }

        is AppsDialogState.ConfirmEnable -> {
            ConfirmationDialog(
                title = "Enable apps?",
                message = "The following apps will be enabled:",
                apps = dialogState.apps,
                confirmButtonText = "Enable",
                isDestructive = false,
                onConfirm = { onConfirmEnable(dialogState.apps) },
                onDismiss = onDismiss,
                modifier = modifier,
            )
        }

        is AppsDialogState.ConfirmUninstall -> {
            ConfirmationDialog(
                title = "Uninstall apps?",
                message = "The following apps will be permanently uninstalled. This action cannot be undone.",
                apps = dialogState.apps,
                confirmButtonText = "Uninstall",
                isDestructive = true,
                onConfirm = {
                    // TODO: Implement actual uninstall operation
                },
                onDismiss = onDismiss,
                modifier = modifier,
            )
        }

        is AppsDialogState.ConfirmClearCache -> {
            ConfirmationDialog(
                title = "Clear cache?",
                message = "Cache will be cleared for the following apps:",
                apps = dialogState.apps,
                confirmButtonText = "Clear cache",
                isDestructive = false,
                onConfirm = {
                    // TODO: Implement actual clear cache operation
                },
                onDismiss = onDismiss,
                modifier = modifier,
            )
        }

        is AppsDialogState.ConfirmClearData -> {
            ConfirmationDialog(
                title = "Clear data?",
                message = "All data will be cleared for the following apps. This action cannot be undone.",
                apps = dialogState.apps,
                confirmButtonText = "Clear data",
                isDestructive = true,
                onConfirm = {
                    // TODO: Implement actual clear data operation
                },
                onDismiss = onDismiss,
                modifier = modifier,
            )
        }
    }
}
