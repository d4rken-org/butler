package eu.darken.butler.apps.ui.apps.dialogs

import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import eu.darken.butler.apps.core.engine.AppItem
import eu.darken.butler.apps.ui.apps.elements.AppsActionBarItem
import eu.darken.butler.workspace.contracts.apps.SortSettings
import eu.darken.butler.workspace.contracts.apps.TagFilterConfig

@Composable
fun AppsDialogHost(
    modifier: Modifier = Modifier,
    dialogState: AppsDialogState,
    filterConfig: TagFilterConfig,
    onDismiss: () -> Unit,
    onAction: (AppsActionBarItem) -> Unit,
    onFilterApply: (TagFilterConfig) -> Unit,
    onSortApply: (SortSettings) -> Unit,
    onConfirmEnable: (List<AppItem>) -> Unit,
    onConfirmDisable: (List<AppItem>) -> Unit,
    onConfirmUninstall: (List<AppItem>) -> Unit,
    onConfirmClearData: (List<AppItem>) -> Unit,
    onOpenSizeSetup: () -> Unit,
    topInset: Dp = 0.dp,
    bottomInset: Dp = 0.dp,
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
                topInset = topInset,
                bottomInset = bottomInset,
            )
        }

        is AppsDialogState.FilterOptions -> {
            FilterBottomSheet(
                modifier = modifier,
                filterConfig = filterConfig,
                availableTags = dialogState.availableTags,
                onFilterChange = onFilterApply,
                onDismiss = onDismiss,
                topInset = topInset,
                bottomInset = bottomInset,
            )
        }

        is AppsDialogState.SortOptions -> {
            SortOptionsDialog(
                currentSortSettings = dialogState.currentSortSettings,
                onDismiss = onDismiss,
                onApply = onSortApply,
                onOpenSetup = onOpenSizeSetup,
                modifier = modifier,
                sizesAvailable = dialogState.sizesAvailable,
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
                onConfirm = { onConfirmUninstall(dialogState.apps) },
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
                onConfirm = { onConfirmClearData(dialogState.apps) },
                onDismiss = onDismiss,
                modifier = modifier,
            )
        }
    }
}
