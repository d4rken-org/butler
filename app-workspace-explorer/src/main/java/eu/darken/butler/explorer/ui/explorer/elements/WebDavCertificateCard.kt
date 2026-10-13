package eu.darken.butler.explorer.ui.explorer.elements

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.text.selection.SelectionContainer
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
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.tooling.preview.PreviewWrapper as ComposePreviewWrapper
import androidx.compose.ui.unit.dp
import eu.darken.butler.common.compose.ButlerPreviewWrapper
import eu.darken.butler.common.compose.Preview2
import eu.darken.butler.common.files.webdav.WebDavCertificateInfo
import eu.darken.butler.explorer.R
import eu.darken.butler.explorer.ui.explorer.dialogs.WebDavCertificateComparison
import eu.darken.butler.explorer.ui.explorer.preview.MockDataProvider
import eu.darken.butler.common.R as CommonR

/**
 * Shown when a WebDAV server presented a certificate that is neither trusted by the system nor
 * pinned for it: [stored] is null when nothing was pinned, otherwise the pin was replaced. Nothing is
 * trusted from here: [onReview] opens the server's form, where trusting needs its own confirmation.
 */
@Composable
fun WebDavCertificateCard(
    modifier: Modifier = Modifier,
    endpoint: String,
    presented: WebDavCertificateInfo,
    stored: WebDavCertificateInfo?,
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
                    text = stringResource(
                        if (stored == null) R.string.explorer_webdav_certificate_card_untrusted_title
                        else R.string.explorer_webdav_certificate_card_changed_title
                    ),
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.SemiBold,
                )
            }

            Text(
                text = stringResource(
                    if (stored == null) R.string.explorer_webdav_certificate_card_untrusted_message
                    else R.string.explorer_webdav_certificate_card_changed_message,
                    endpoint,
                ),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )

            if (stored == null) {
                Column {
                    Text(
                        text = stringResource(R.string.explorer_webdav_certificate_presented_label),
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    SelectionContainer {
                        Text(
                            text = presented.sha256Fingerprint,
                            style = MaterialTheme.typography.bodyMedium,
                            fontFamily = FontFamily.Monospace,
                        )
                    }
                }
            } else {
                WebDavCertificateComparison(stored = stored, presented = presented)
            }

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
                    Text(text = stringResource(R.string.explorer_webdav_certificate_review_action))
                }
            }
        }
    }
}

@Preview2
@ComposePreviewWrapper(ButlerPreviewWrapper::class)
@Composable
private fun WebDavCertificateCardUntrustedPreview() {
    WebDavCertificateCard(
        endpoint = "alice@cloud.example.com/remote.php/dav/files/alice",
        presented = MockDataProvider.mockWebDavCertificate(1),
        stored = null,
        onReview = {},
        onRetry = {},
        onDismiss = {},
    )
}

@Preview2
@ComposePreviewWrapper(ButlerPreviewWrapper::class)
@Composable
private fun WebDavCertificateCardChangedPreview() {
    WebDavCertificateCard(
        endpoint = "alice@cloud.example.com/remote.php/dav/files/alice",
        presented = MockDataProvider.mockWebDavCertificate(2),
        stored = MockDataProvider.mockWebDavCertificate(1),
        onReview = {},
        onRetry = {},
        onDismiss = {},
    )
}
