package eu.darken.butler.workspace.ui

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.systemBars
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import eu.darken.butler.workspace.core.Workspace
import eu.darken.butler.workspace.ui.dialogs.OpenInNewTabsConfirmationDialog
import eu.darken.butler.workspace.ui.dialogs.WorkspaceManagerDialogState
import eu.darken.butler.workspace.ui.feedback.BannerState
import eu.darken.butler.workspace.ui.feedback.WorkspaceBanner

/**
 * Container that wraps workspace content with an overlay layer for manager-controlled UI elements.
 *
 * This provides a unified location for rendering:
 * - Manager-controlled dialogs (e.g., batch operation confirmations)
 * - Future: Workspace-level notifications
 * - Future: Workspace-level loading states
 * - Future: Floating action buttons
 *
 * The container is used consistently across both single-pane (ClassicWorkspaceContainer)
 * and multi-pane (AdaptiveWorkspaceLayout) layouts.
 *
 * @param workspaceId The ID of the workspace being wrapped
 * @param managerDialogStates Map of dialog states by workspace ID from WorkspacesViewModel
 * @param onDismissManagerDialog Callback to dismiss the manager dialog for a specific workspace
 * @param onConfirmManagerDialog Callback when manager dialog is confirmed
 * @param bannerStates Map of banner states by workspace ID from WorkspacesViewModel
 * @param onDismissBanner Callback to dismiss the banner for a specific workspace
 * @param modifier Optional modifier for the container
 * @param content The workspace content to be wrapped
 */
@Composable
fun WorkspaceOverlayContainer(
    workspaceId: Workspace.Id?,
    managerDialogStates: Map<Workspace.Id, WorkspaceManagerDialogState.Targeted>,
    onDismissManagerDialog: (Workspace.Id) -> Unit,
    onConfirmManagerDialog: (WorkspaceManagerDialogState.Targeted) -> Unit,
    bannerStates: Map<Workspace.Id, BannerState>,
    onDismissBanner: (Workspace.Id) -> Unit,
    modifier: Modifier = Modifier,
    content: @Composable () -> Unit,
) {
    Box(modifier = modifier) {
        // Workspace content
        content()

        // Manager dialog overlay - direct lookup for this workspace
        val dialogState = workspaceId?.let { managerDialogStates[it] }

        dialogState?.let { dialog ->
            when (dialog) {
                is WorkspaceManagerDialogState.OpenInNewTabsConfirmation -> {
                    OpenInNewTabsConfirmationDialog(
                        totalCount = dialog.totalCount,
                        onDismiss = { onDismissManagerDialog(dialog.targetWorkspaceId) },
                        onConfirm = { onConfirmManagerDialog(dialog) },
                    )
                }

                // Future dialog types handled here:
                // is WorkspaceManagerDialogState.CloseConfirmation -> { ... }
                // is WorkspaceManagerDialogState.DuplicateDialog -> { ... }
            }
        }

        // Banner feedback overlay - direct lookup for this workspace
        val bannerState = workspaceId?.let { bannerStates[it] }

        bannerState?.let { banner ->
            Box(
                modifier = Modifier
                    .align(Alignment.BottomCenter)
                    .windowInsetsPadding(WindowInsets.systemBars)
                    .padding(horizontal = 16.dp, vertical = 8.dp)
            ) {
                WorkspaceBanner(
                    state = banner,
                    onDismiss = { workspaceId?.let { onDismissBanner(it) } }
                )
            }
        }

        // Future overlay types can be added here:
        // - Workspace-level loading indicators
        // - Floating action buttons
    }
}
