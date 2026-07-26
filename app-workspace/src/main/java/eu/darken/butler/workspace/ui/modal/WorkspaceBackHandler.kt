package eu.darken.butler.workspace.ui.modal

import androidx.activity.compose.BackHandler
import androidx.compose.runtime.Composable

/**
 * Back handler that only fires while its layer is the active one.
 *
 * Every workspace-scoped back handler must use this instead of [BackHandler]: a page handler that
 * stays enabled while a dialog is up would consume back and navigate the workspace instead of
 * dismissing the dialog.
 *
 * Being gated on the layer rather than on registration order also makes it independent of
 * `BackHandler`'s LIFO ordering, which conditional composition perturbs.
 */
@Composable
fun WorkspaceBackHandler(
    enabled: Boolean = true,
    onBack: () -> Unit,
) = BackHandler(
    enabled = enabled && LocalLayerActive.current,
    onBack = onBack,
)
