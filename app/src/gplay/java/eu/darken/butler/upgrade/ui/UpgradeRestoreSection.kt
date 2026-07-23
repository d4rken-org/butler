package eu.darken.butler.upgrade.ui

import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.twotone.Restore
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.tooling.preview.PreviewWrapper as ComposePreviewWrapper
import androidx.compose.ui.unit.dp
import eu.darken.butler.R
import eu.darken.butler.common.compose.ButlerPreviewWrapper
import eu.darken.butler.common.compose.Preview2
import eu.darken.butler.common.compose.PreviewWrapper

// Described restore section, shared by all restore audiences (copy and emphasis differ, wiring
// doesn't). No contact-support action here — self-service gets its chance first; escalation is on
// the failed-restore dialog only.
@Composable
internal fun UpgradeRestoreSection(
    title: String,
    body: String,
    onRestore: () -> Unit,
    modifier: Modifier = Modifier,
    restoreInProgress: Boolean = false,
    emphasized: Boolean = false,
    restoreTag: String = UpgradeScreenTags.RESTORE,
) {
    UpgradeSectionCard(
        title = title,
        icon = Icons.TwoTone.Restore,
        modifier = modifier,
        colors = if (emphasized) {
            CardDefaults.elevatedCardColors(
                containerColor = MaterialTheme.colorScheme.tertiaryContainer,
                contentColor = MaterialTheme.colorScheme.onTertiaryContainer,
            )
        } else {
            null
        },
    ) {
        if (emphasized) {
            Text(text = body, style = MaterialTheme.typography.bodyMedium)
            Button(
                onClick = onRestore,
                enabled = !restoreInProgress,
                modifier = Modifier
                    .fillMaxWidth()
                    .testTag(restoreTag),
            ) { RestoreButtonLabel(restoreInProgress) }
        } else {
            UpgradeSectionBody(text = body)
            OutlinedButton(
                onClick = onRestore,
                enabled = !restoreInProgress,
                modifier = Modifier
                    .fillMaxWidth()
                    .testTag(restoreTag),
            ) { RestoreButtonLabel(restoreInProgress) }
        }
    }
}

@Composable
private fun RestoreButtonLabel(restoreInProgress: Boolean) {
    if (restoreInProgress) {
        CircularProgressIndicator(modifier = Modifier.size(18.dp), strokeWidth = 2.dp)
        Spacer(modifier = Modifier.width(8.dp))
    }
    Text(stringResource(R.string.upgrade_screen_restore_purchase_action))
}

@Composable
fun RestoreFailedDialog(onDismiss: () -> Unit) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(text = stringResource(eu.darken.butler.common.R.string.general_error_label)) },
        confirmButton = {
            TextButton(onClick = onDismiss) { Text(stringResource(id = android.R.string.ok)) }
        },
        text = {
            Text(
                text = """
                    ${stringResource(R.string.upgrade_screen_restore_purchase_message)}

                    ${stringResource(R.string.upgrade_screen_restore_troubleshooting_msg)}

                    ${stringResource(R.string.upgrade_screen_restore_sync_patience_hint)}

                    ${stringResource(R.string.upgrade_screen_restore_multiaccount_hint)}
                """.trimIndent()
            )
        },
    )
}

@Composable
fun SimpleMessageDialog(
    title: String,
    message: String,
    onDismiss: () -> Unit,
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(text = title) },
        confirmButton = {
            TextButton(onClick = onDismiss) { Text(stringResource(id = android.R.string.ok)) }
        },
        text = { Text(text = message) },
    )
}

@Preview2
@ComposePreviewWrapper(ButlerPreviewWrapper::class)
@Composable
private fun UpgradeRestoreSectionPreview() {
    UpgradeRestoreSection(
        title = "Already bought Pro?",
        body = "Restoring asks Google Play to re-check this app's purchases for the current account.",
        onRestore = {},
    )
}
