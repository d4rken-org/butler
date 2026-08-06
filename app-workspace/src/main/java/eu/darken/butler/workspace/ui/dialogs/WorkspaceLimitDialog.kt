package eu.darken.butler.workspace.ui.dialogs

import androidx.compose.foundation.layout.size
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.tooling.preview.PreviewWrapper as ComposePreviewWrapper
import androidx.compose.ui.unit.dp
import eu.darken.butler.common.ca.CaString
import eu.darken.butler.common.ca.toCaString
import eu.darken.butler.common.compose.ButlerIcon
import eu.darken.butler.common.compose.ButlerIconVariant
import eu.darken.butler.common.compose.ButlerPreviewWrapper
import eu.darken.butler.common.compose.Preview2
import eu.darken.butler.common.compose.PreviewWrapper
import eu.darken.butler.common.ui.dialogs.ButlerAlertDialog
import eu.darken.butler.workspace.R
import eu.darken.butler.common.R as CommonR

/**
 * Stays a window-level dialog on purpose: it has to sit above the tab manager overlay AND above a
 * full-screen modal workspace, which is also where [onCloseOldest] matters most - inside such a
 * modal there is no tab manager to reach, so closing a tab from here is the only way forward.
 *
 * @param closableTitle tab the neutral action would close; null hides that action.
 */
@Composable
fun WorkspaceLimitDialog(
    limit: Int,
    onDismiss: () -> Unit,
    onUpgrade: () -> Unit,
    closableTitle: CaString? = null,
    onCloseOldest: () -> Unit = {},
) {
    ButlerAlertDialog(
        onDismissRequest = onDismiss,
        icon = {
            ButlerIcon(
                modifier = Modifier.size(48.dp),
                variant = ButlerIconVariant.SAD,
            )
        },
        title = {
            Text(text = stringResource(R.string.workspace_limit_reached_title))
        },
        text = {
            Text(
                text = stringResource(
                    R.string.workspace_limit_reached_message,
                    limit,
                )
            )
        },
        confirmButton = {
            TextButton(onClick = onUpgrade) {
                Text(text = stringResource(CommonR.string.general_upgrade_action))
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text(text = stringResource(CommonR.string.general_dismiss_action))
            }
        },
        neutralButton = closableTitle?.let { title ->
            {
                TextButton(onClick = onCloseOldest) {
                    Text(
                        text = stringResource(
                            R.string.workspace_limit_close_oldest_action,
                            title.get(LocalContext.current),
                        )
                    )
                }
            }
        },
    )
}

@Preview2
@ComposePreviewWrapper(ButlerPreviewWrapper::class)
@Composable
private fun WorkspaceLimitDialogPreview() {
    WorkspaceLimitDialog(
        limit = 5,
        onDismiss = {},
        onUpgrade = {},
    )
}

@Preview2
@ComposePreviewWrapper(ButlerPreviewWrapper::class)
@Composable
private fun WorkspaceLimitDialogWithCloseOldestPreview() {
    WorkspaceLimitDialog(
        limit = 5,
        onDismiss = {},
        onUpgrade = {},
        closableTitle = "Downloads".toCaString(),
        onCloseOldest = {},
    )
}
