package eu.darken.butler.upgrade.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
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

// Single restore section reused by both the acquisition and the ownership screens ("Status looks
// wrong?"), so the two can't drift apart.
@Composable
fun UpgradeRestoreSection(
    modifier: Modifier = Modifier,
    onRestorePurchase: () -> Unit,
    restoreInProgress: Boolean,
) {
    Column(
        modifier = modifier
            .fillMaxWidth()
            .testTag(UpgradeScreenTestTags.RESTORE_SECTION),
        verticalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        Text(
            text = stringResource(R.string.upgrade_screen_restore_status_title),
            style = MaterialTheme.typography.titleSmall,
            color = MaterialTheme.colorScheme.primary,
        )
        Text(
            text = stringResource(R.string.upgrade_screen_restore_status_body),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        TextButton(
            onClick = onRestorePurchase,
            enabled = !restoreInProgress,
            modifier = Modifier
                .fillMaxWidth()
                .testTag(UpgradeScreenTestTags.RESTORE_ACTION),
        ) {
            if (restoreInProgress) {
                CircularProgressIndicator(
                    modifier = Modifier.size(18.dp),
                    strokeWidth = 2.dp,
                )
            } else {
                Text(text = stringResource(R.string.upgrade_screen_restore_purchase_action))
            }
        }
    }
}

@Composable
fun RestoreFailedDialog(onDismiss: () -> Unit) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(text = stringResource(eu.darken.butler.common.R.string.general_error_label)) },
        confirmButton = {
            TextButton(onClick = onDismiss) {
                Text(stringResource(id = android.R.string.ok))
            }
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
            TextButton(onClick = onDismiss) {
                Text(stringResource(id = android.R.string.ok))
            }
        },
        text = { Text(text = message) },
    )
}

internal object UpgradeScreenTestTags {
    const val RESTORE_SECTION = "upgrade_restore_section"
    const val RESTORE_ACTION = "upgrade_restore_action"
    const val SWITCH_ACTION = "upgrade_switch_action"
    const val IAP_ACTION = "upgrade_iap_action"
    const val SUB_ACTION = "upgrade_sub_action"
}

@Preview2
@ComposePreviewWrapper(ButlerPreviewWrapper::class)
@Composable
private fun UpgradeRestoreSectionPreview() {
    UpgradeRestoreSection(onRestorePurchase = {}, restoreInProgress = false)
}
