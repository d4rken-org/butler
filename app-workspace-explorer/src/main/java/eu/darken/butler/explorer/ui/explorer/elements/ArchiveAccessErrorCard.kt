package eu.darken.butler.explorer.ui.explorer.elements

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.twotone.Download
import androidx.compose.material.icons.twotone.FolderZip
import androidx.compose.material.icons.twotone.Unarchive
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
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
import eu.darken.butler.explorer.R
import eu.darken.butler.common.R as CommonR

/**
 * Shown when an archive can't be browsed in place (forward-only storage, no random access).
 * Offers the two explicit ways out: streaming extraction, or an explicit local copy to browse.
 */
@Composable
fun ArchiveAccessErrorCard(
    modifier: Modifier = Modifier,
    archiveName: String,
    busy: Boolean,
    onExtract: () -> Unit,
    onDownloadCopy: () -> Unit,
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
        ) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                Icon(
                    imageVector = Icons.TwoTone.FolderZip,
                    contentDescription = null,
                    modifier = Modifier.size(24.dp),
                    tint = MaterialTheme.colorScheme.primary,
                )
                Text(
                    text = stringResource(R.string.explorer_archive_unbrowsable_title),
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.SemiBold,
                )
            }

            Spacer(modifier = Modifier.height(12.dp))

            Text(
                text = stringResource(R.string.explorer_archive_unbrowsable_msg, archiveName),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )

            Spacer(modifier = Modifier.height(16.dp))

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                FilledTonalButton(
                    modifier = Modifier.weight(1f),
                    enabled = !busy,
                    onClick = onExtract,
                ) {
                    Icon(
                        imageVector = Icons.TwoTone.Unarchive,
                        contentDescription = null,
                        modifier = Modifier.size(18.dp),
                    )
                    Spacer(modifier = Modifier.size(6.dp))
                    Text(text = stringResource(R.string.explorer_archive_unbrowsable_extract_action))
                }
                FilledTonalButton(
                    modifier = Modifier.weight(1f),
                    enabled = !busy,
                    onClick = onDownloadCopy,
                ) {
                    Icon(
                        imageVector = Icons.TwoTone.Download,
                        contentDescription = null,
                        modifier = Modifier.size(18.dp),
                    )
                    Spacer(modifier = Modifier.size(6.dp))
                    Text(text = stringResource(R.string.explorer_archive_unbrowsable_download_action))
                }
            }

            if (busy) {
                Spacer(modifier = Modifier.height(12.dp))
                LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
            }

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.End,
            ) {
                TextButton(enabled = !busy, onClick = onRetry) {
                    Text(text = stringResource(CommonR.string.general_retry_action))
                }
                TextButton(enabled = !busy, onClick = onDismiss) {
                    Text(text = stringResource(CommonR.string.general_dismiss_action))
                }
            }
        }
    }
}

@Preview2
@ComposePreviewWrapper(ButlerPreviewWrapper::class)
@Composable
private fun ArchiveAccessErrorCardPreview() {
    ArchiveAccessErrorCard(
        archiveName = "backup-2026.zip",
        busy = false,
        onExtract = {},
        onDownloadCopy = {},
        onRetry = {},
        onDismiss = {},
    )
}

@Preview2
@ComposePreviewWrapper(ButlerPreviewWrapper::class)
@Composable
private fun ArchiveAccessErrorCardBusyPreview() {
    ArchiveAccessErrorCard(
        archiveName = "backup-2026.zip",
        busy = true,
        onExtract = {},
        onDownloadCopy = {},
        onRetry = {},
        onDismiss = {},
    )
}
