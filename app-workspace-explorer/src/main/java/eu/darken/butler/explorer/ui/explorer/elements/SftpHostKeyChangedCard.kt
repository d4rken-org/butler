package eu.darken.butler.explorer.ui.explorer.elements

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.twotone.GppMaybe
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.tooling.preview.PreviewWrapper as ComposePreviewWrapper
import androidx.compose.ui.unit.dp
import eu.darken.butler.common.compose.ButlerPreviewWrapper
import eu.darken.butler.common.compose.Preview2
import eu.darken.butler.common.files.sftp.location.TrustedHostKey
import eu.darken.butler.explorer.R
import eu.darken.butler.explorer.ui.explorer.dialogs.HostKeyComparison
import eu.darken.butler.explorer.ui.explorer.dialogs.previewHostKey
import eu.darken.butler.common.R as CommonR

/**
 * Shown when an SFTP server presented another key than the confirmed one. Nothing is trusted from
 * here: [onReview] opens the server's form, where replacing the key needs its own confirmation.
 */
@Composable
fun SftpHostKeyChangedCard(
    modifier: Modifier = Modifier,
    endpoint: String,
    storedKey: TrustedHostKey,
    presentedKey: TrustedHostKey,
    onReview: () -> Unit,
    onRetry: () -> Unit,
    onDismiss: () -> Unit,
) {
    Card(
        modifier = modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceContainerHigh,
        ),
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                Icon(
                    imageVector = Icons.TwoTone.GppMaybe,
                    contentDescription = null,
                    modifier = Modifier.size(24.dp),
                    tint = MaterialTheme.colorScheme.error,
                )
                Text(
                    text = stringResource(R.string.explorer_sftp_host_key_changed_title),
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.SemiBold,
                )
            }

            Text(
                text = stringResource(R.string.explorer_sftp_host_key_changed_message, endpoint),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )

            HostKeyComparison(stored = storedKey, presented = presentedKey)

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp, Alignment.End),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                TextButton(onClick = onRetry) {
                    Text(text = stringResource(CommonR.string.general_retry_action))
                }
                TextButton(onClick = onDismiss) {
                    Text(text = stringResource(CommonR.string.general_dismiss_action))
                }
                FilledTonalButton(onClick = onReview) {
                    Text(text = stringResource(R.string.explorer_sftp_host_key_review_action))
                }
            }
        }
    }
}

@Preview2
@ComposePreviewWrapper(ButlerPreviewWrapper::class)
@Composable
private fun SftpHostKeyChangedCardPreview() {
    SftpHostKeyChangedCard(
        endpoint = "darken@build.lan/srv/builds",
        storedKey = previewHostKey(1),
        presentedKey = previewHostKey(2),
        onReview = {},
        onRetry = {},
        onDismiss = {},
    )
}
