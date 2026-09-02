package eu.darken.butler.apps.ui.details.components

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.tooling.preview.PreviewWrapper as ComposePreviewWrapper
import androidx.compose.ui.unit.dp
import eu.darken.butler.apps.R
import eu.darken.butler.apps.core.details.components.ComponentEnabledState
import eu.darken.butler.apps.core.details.components.ComponentEntry
import eu.darken.butler.apps.core.details.components.ComponentKind
import eu.darken.butler.common.compose.BulletListItem
import eu.darken.butler.common.compose.ButlerPreviewWrapper
import eu.darken.butler.common.compose.Preview2
import eu.darken.butler.common.compose.PreviewWrapper
import eu.darken.butler.workspace.ui.dialogs.PaneBoundAlertDialog
import eu.darken.butler.common.R as CommonR

/** A pending batch toggle awaiting confirmation. Single-component toggles are applied directly. */
data class ComponentsConfirmRequest(
    val entries: List<ComponentEntry>,
    val enable: Boolean,
)

/**
 * Confirmation for a batch component toggle.
 *
 * Pane-bound like every other dialog in this workspace, so it never covers a sibling pane.
 */
@Composable
fun ComponentsConfirmDialog(
    modifier: Modifier = Modifier,
    request: ComponentsConfirmRequest,
    onConfirm: () -> Unit,
    onDismiss: () -> Unit,
) {
    val entries = request.entries

    PaneBoundAlertDialog(
        onDismissRequest = onDismiss,
        modifier = modifier,
        title = {
            Text(
                text = if (request.enable) {
                    stringResource(R.string.apps_components_confirm_enable_title)
                } else {
                    stringResource(R.string.apps_components_confirm_disable_title)
                },
                style = MaterialTheme.typography.titleLarge,
                fontWeight = FontWeight.Bold,
            )
        },
        text = {
            Column(modifier = Modifier.fillMaxWidth()) {
                Text(
                    text = if (request.enable) {
                        stringResource(R.string.apps_components_confirm_enable_message)
                    } else {
                        stringResource(R.string.apps_components_confirm_disable_message)
                    },
                    style = MaterialTheme.typography.bodyMedium,
                )

                Spacer(modifier = Modifier.height(16.dp))

                Text(
                    text = pluralStringResource(
                        R.plurals.apps_components_selected_count,
                        entries.size,
                        entries.size,
                    ),
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )

                Spacer(modifier = Modifier.height(8.dp))

                entries.take(5).forEach { entry ->
                    BulletListItem(
                        modifier = Modifier.padding(vertical = 2.dp),
                        text = entry.simpleName,
                        style = MaterialTheme.typography.bodySmall,
                    )
                }

                if (entries.size > 5) {
                    BulletListItem(
                        modifier = Modifier.padding(vertical = 2.dp),
                        text = stringResource(R.string.apps_components_and_more, entries.size - 5),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        },
        confirmButton = {
            TextButton(onClick = onConfirm) {
                Text(
                    text = if (request.enable) {
                        stringResource(R.string.apps_action_enable)
                    } else {
                        stringResource(R.string.apps_action_disable)
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

private fun previewEntry(index: Int, enabled: Boolean) = ComponentEntry(
    kind = ComponentKind.RECEIVER,
    packageName = "com.example.app",
    className = "com.example.app.Receiver$index",
    isExported = false,
    enabledState = if (enabled) ComponentEnabledState.ENABLED else ComponentEnabledState.DISABLED,
)

@Preview2
@ComposePreviewWrapper(ButlerPreviewWrapper::class)
@Composable
private fun ComponentsConfirmDialogDisablePreview() {
    ComponentsConfirmDialog(
        request = ComponentsConfirmRequest(
            entries = List(3) { previewEntry(it, enabled = true) },
            enable = false,
        ),
        onConfirm = {},
        onDismiss = {},
    )
}

@Preview2
@ComposePreviewWrapper(ButlerPreviewWrapper::class)
@Composable
private fun ComponentsConfirmDialogEnablePreview() {
    ComponentsConfirmDialog(
        request = ComponentsConfirmRequest(
            entries = List(2) { previewEntry(it, enabled = false) },
            enable = true,
        ),
        onConfirm = {},
        onDismiss = {},
    )
}

@Preview2
@ComposePreviewWrapper(ButlerPreviewWrapper::class)
@Composable
private fun ComponentsConfirmDialogManyPreview() {
    ComponentsConfirmDialog(
        request = ComponentsConfirmRequest(
            entries = List(9) { previewEntry(it, enabled = true) },
            enable = false,
        ),
        onConfirm = {},
        onDismiss = {},
    )
}
