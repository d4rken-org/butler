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
import eu.darken.butler.workspace.ui.feedback.BannerState
import eu.darken.butler.workspace.ui.feedback.WorkspaceBanner

/**
 * Container that wraps workspace content with the feedback banner for that workspace.
 *
 * Banner and content share the pane's content layer on purpose: a banner competes for the same
 * attention the content does, so a modal covering the content covers the banner too. The banner's
 * auto-dismiss timer therefore only runs while that layer is the active one.
 *
 * Manager-controlled dialogs are NOT rendered here — they are their own layer, composed by the
 * pane layer host above this container.
 *
 * The container is used consistently across both single-pane (ClassicWorkspaceContainer)
 * and multi-pane (AdaptiveWorkspaceLayout) layouts.
 *
 * @param workspaceId The ID of the workspace being wrapped
 * @param bannerStates Map of banner states by workspace ID from WorkspacesViewModel
 * @param onDismissBanner Callback to dismiss the banner for a specific workspace
 * @param modifier Optional modifier for the container
 * @param content The workspace content to be wrapped
 */
@Composable
fun WorkspaceOverlayContainer(
    workspaceId: Workspace.Id,
    bannerStates: Map<Workspace.Id, BannerState>,
    onDismissBanner: (Workspace.Id) -> Unit,
    modifier: Modifier = Modifier,
    content: @Composable () -> Unit,
) {
    Box(modifier = modifier) {
        // Workspace content
        content()

        // Banner feedback overlay - direct lookup for this workspace
        val bannerState = bannerStates[workspaceId]

        bannerState?.let { banner ->
            Box(
                modifier = Modifier
                    .align(Alignment.BottomCenter)
                    .windowInsetsPadding(WindowInsets.systemBars)
                    .padding(horizontal = 16.dp, vertical = 8.dp)
            ) {
                WorkspaceBanner(
                    state = banner,
                    onDismiss = { onDismissBanner(workspaceId) }
                )
            }
        }
    }
}
