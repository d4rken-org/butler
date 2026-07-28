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

// Described restore section, shared by all restore audiences (copy and emphasis differ, wiring
// doesn't). Deliberately NO contact-support action here: escalation is offered only after a
// restore came up empty (the failed-restore dialog), so self-service gets its chance first.
@Composable
internal fun UpgradeRestoreSection(
    title: String,
    body: String,
    onRestore: () -> Unit,
    modifier: Modifier = Modifier,
    busy: BusyOp? = null,
    emphasized: Boolean = false,
    restoreTag: String = UpgradeScreenTags.RESTORE,
) {
    // Any running entitlement action (purchase included) blocks a restore — they all reconcile the
    // same Play account state. Only a running RESTORE shows the spinner.
    val restoreInProgress = busy == BusyOp.RESTORE
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
            // Plain Text: the tinted container brings its own content color, the muted
            // UpgradeSectionBody tone is for neutral surface cards only.
            Text(
                text = body,
                style = MaterialTheme.typography.bodyMedium,
            )
            Button(
                onClick = onRestore,
                enabled = busy == null,
                modifier = Modifier
                    .fillMaxWidth()
                    .testTag(restoreTag),
            ) {
                RestoreButtonLabel(restoreInProgress = restoreInProgress)
            }
        } else {
            UpgradeSectionBody(text = body)
            OutlinedButton(
                onClick = onRestore,
                enabled = busy == null,
                modifier = Modifier
                    .fillMaxWidth()
                    .testTag(restoreTag),
            ) {
                RestoreButtonLabel(restoreInProgress = restoreInProgress)
            }
        }
    }
}

@Composable
private fun RestoreButtonLabel(restoreInProgress: Boolean) {
    if (restoreInProgress) {
        CircularProgressIndicator(
            modifier = Modifier.size(18.dp),
            strokeWidth = 2.dp,
        )
        Spacer(modifier = Modifier.width(8.dp))
    }
    Text(stringResource(R.string.upgrade_screen_restore_purchase_action))
}

/**
 * Shown when Play answered and no purchase was found. Leads with the just-happened live check,
 * which is literally true here: non-answers route to [RestoreInconclusiveDialog] instead. This is
 * the ONLY contact-support surface — escalation comes after an empty restore, never before.
 */
@Composable
internal fun RestoreFailedDialog(
    onContactSupport: () -> Unit = {},
    onDismiss: () -> Unit = {},
) {
    val checkedMsg = stringResource(R.string.upgrade_screen_restore_checked_message)
    val multiAccountHint = stringResource(R.string.upgrade_screen_restore_multiaccount_hint)
    val syncHint = stringResource(R.string.upgrade_screen_restore_sync_patience_hint)
    val contactHint = stringResource(R.string.upgrade_screen_restore_contact_hint)
    AlertDialog(
        onDismissRequest = onDismiss,
        text = { Text(text = "$checkedMsg\n\n$multiAccountHint\n\n$syncHint\n\n$contactHint") },
        confirmButton = {
            TextButton(onClick = onContactSupport) {
                Text(stringResource(R.string.upgrade_screen_contact_support_action))
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text(stringResource(eu.darken.butler.common.R.string.general_dismiss_action))
            }
        },
    )
}

/**
 * Shown when the restore never got an answer (timeout, or a Play error absorbed by grace). Carries
 * no multi-account hint and no contact-support action: nothing was established, so both would be
 * premature. Retry is the useful move, and `restorePurchase()` is single-flight.
 */
@Composable
internal fun RestoreInconclusiveDialog(
    onRetry: () -> Unit = {},
    onDismiss: () -> Unit = {},
) {
    val inconclusiveMsg = stringResource(R.string.upgrade_screen_restore_inconclusive_message)
    val syncHint = stringResource(R.string.upgrade_screen_restore_sync_patience_hint)
    AlertDialog(
        onDismissRequest = onDismiss,
        text = { Text(text = "$inconclusiveMsg\n\n$syncHint") },
        confirmButton = {
            TextButton(onClick = onRetry) {
                Text(stringResource(eu.darken.butler.common.R.string.general_retry_action))
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text(stringResource(eu.darken.butler.common.R.string.general_dismiss_action))
            }
        },
    )
}

@Composable
internal fun SimpleMessageDialog(
    title: String?,
    message: String,
    onDismiss: () -> Unit,
    positiveLabel: String? = null,
    onPositive: (() -> Unit)? = null,
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = title?.let { { Text(text = it) } },
        text = { Text(text = message) },
        confirmButton = {
            if (positiveLabel != null && onPositive != null) {
                TextButton(onClick = onPositive) { Text(positiveLabel) }
            } else {
                TextButton(onClick = onDismiss) {
                    Text(stringResource(eu.darken.butler.common.R.string.general_dismiss_action))
                }
            }
        },
        dismissButton = if (positiveLabel != null && onPositive != null) {
            {
                TextButton(onClick = onDismiss) {
                    Text(stringResource(eu.darken.butler.common.R.string.general_dismiss_action))
                }
            }
        } else {
            null
        },
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

@Preview2
@ComposePreviewWrapper(ButlerPreviewWrapper::class)
@Composable
private fun UpgradeRestoreSectionEmphasizedPreview() {
    UpgradeRestoreSection(
        title = "Already bought Pro?",
        body = "It looks like you upgraded to Pro on this device before.",
        onRestore = {},
        emphasized = true,
        busy = BusyOp.RESTORE,
    )
}
