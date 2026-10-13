package eu.darken.butler.explorer.ui.explorer.dialogs

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.twotone.GppMaybe
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.tooling.preview.PreviewWrapper as ComposePreviewWrapper
import androidx.compose.ui.unit.dp
import eu.darken.butler.common.DateTimeStyle
import eu.darken.butler.common.compose.ButlerPreviewWrapper
import eu.darken.butler.common.compose.Preview2
import eu.darken.butler.common.files.webdav.WebDavCertificateInfo
import eu.darken.butler.common.formatDateTime
import eu.darken.butler.explorer.R
import eu.darken.butler.explorer.ui.explorer.preview.MockDataProvider
import eu.darken.butler.workspace.ui.dialogs.PaneBoundAlertDialog
import kotlin.uuid.Uuid
import eu.darken.butler.common.R as CommonR

/** Asks whether a certificate the system does not trust is the one the tested server should have. */
@Composable
fun WebDavCertificateConfirmationDialog(
    modifier: Modifier = Modifier,
    confirmation: WebDavCertificateConfirmation,
    onAccept: () -> Unit,
    onCancel: () -> Unit,
) {
    PaneBoundAlertDialog(
        modifier = modifier,
        onDismissRequest = onCancel,
        icon = { Icon(imageVector = Icons.TwoTone.GppMaybe, contentDescription = null) },
        title = { Text(stringResource(R.string.explorer_webdav_certificate_untrusted_title)) },
        text = {
            Column(
                modifier = Modifier.fillMaxWidth(),
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                Text(
                    text = stringResource(R.string.explorer_webdav_certificate_untrusted_message, confirmation.url),
                    style = MaterialTheme.typography.bodyMedium,
                )
                WebDavCertificateDetails(certificate = confirmation.presented)
            }
        },
        confirmButton = {
            TextButton(onClick = onAccept) {
                Text(stringResource(R.string.explorer_webdav_certificate_accept_action))
            }
        },
        dismissButton = {
            TextButton(onClick = onCancel) {
                Text(stringResource(CommonR.string.general_cancel_action))
            }
        },
    )
}

/**
 * Asks whether a stored location may pin the certificate its server presents now: one the system
 * stopped trusting, or one that replaced the pinned certificate.
 */
@Composable
fun WebDavCertificateRetrustDialog(
    modifier: Modifier = Modifier,
    confirmation: WebDavRetrustConfirmation,
    onAccept: () -> Unit,
    onCancel: () -> Unit,
) {
    val stored = confirmation.stored
    PaneBoundAlertDialog(
        modifier = modifier,
        onDismissRequest = onCancel,
        icon = {
            Icon(
                imageVector = Icons.TwoTone.GppMaybe,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.error,
            )
        },
        title = {
            Text(
                stringResource(
                    if (stored == null) R.string.explorer_webdav_certificate_untrusted_title
                    else R.string.explorer_webdav_certificate_retrust_title
                )
            )
        },
        text = {
            Column(
                modifier = Modifier.fillMaxWidth(),
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                Text(
                    text = stringResource(
                        if (stored == null) R.string.explorer_webdav_certificate_now_untrusted_message
                        else R.string.explorer_webdav_certificate_retrust_message,
                        confirmation.endpoint,
                    ),
                    style = MaterialTheme.typography.bodyMedium,
                )
                if (stored == null) {
                    WebDavCertificateDetails(certificate = confirmation.presented)
                } else {
                    WebDavCertificateComparison(stored = stored, presented = confirmation.presented)
                }
            }
        },
        confirmButton = {
            TextButton(onClick = onAccept) {
                Text(
                    text = stringResource(
                        if (stored == null) R.string.explorer_webdav_certificate_accept_action
                        else R.string.explorer_webdav_certificate_retrust_action
                    ),
                    color = MaterialTheme.colorScheme.error,
                )
            }
        },
        dismissButton = {
            TextButton(onClick = onCancel) {
                Text(stringResource(CommonR.string.general_cancel_action))
            }
        },
    )
}

/** Who the certificate names, who signed it, how long it is valid and its fingerprint. */
@Composable
fun WebDavCertificateDetails(
    modifier: Modifier = Modifier,
    certificate: WebDavCertificateInfo,
) {
    Column(
        modifier = modifier.fillMaxWidth(),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        CertificateField(
            label = stringResource(R.string.explorer_webdav_certificate_subject_label),
            value = certificate.subject,
        )
        CertificateField(
            label = stringResource(R.string.explorer_webdav_certificate_issuer_label),
            value = certificate.issuer,
        )
        CertificateField(
            label = stringResource(R.string.explorer_webdav_certificate_validity_label),
            value = stringResource(
                R.string.explorer_webdav_certificate_validity_format,
                formatDateTime(certificate.notBefore, DateTimeStyle.DATE_NUMERIC),
                formatDateTime(certificate.notAfter, DateTimeStyle.DATE_NUMERIC),
            ),
            monospace = false,
        )
        CertificateField(
            label = stringResource(R.string.explorer_webdav_certificate_fingerprint_label),
            value = certificate.sha256Fingerprint,
        )
    }
}

/** The pinned and the presented fingerprint below each other, as the retrust dialog and the error card show them. */
@Composable
fun WebDavCertificateComparison(
    modifier: Modifier = Modifier,
    stored: WebDavCertificateInfo,
    presented: WebDavCertificateInfo,
) {
    Column(
        modifier = modifier.fillMaxWidth(),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        CertificateField(
            label = stringResource(R.string.explorer_webdav_certificate_stored_label),
            value = stored.sha256Fingerprint,
        )
        CertificateField(
            label = stringResource(R.string.explorer_webdav_certificate_presented_label),
            value = presented.sha256Fingerprint,
        )
    }
}

@Composable
private fun CertificateField(
    modifier: Modifier = Modifier,
    label: String,
    value: String,
    monospace: Boolean = true,
) {
    Column(modifier = modifier) {
        Text(
            text = label,
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        SelectionContainer {
            Text(
                text = value,
                style = MaterialTheme.typography.bodyMedium,
                fontFamily = if (monospace) FontFamily.Monospace else null,
            )
        }
    }
}

@Preview2
@ComposePreviewWrapper(ButlerPreviewWrapper::class)
@Composable
private fun WebDavCertificateConfirmationDialogPreview() {
    WebDavCertificateConfirmationDialog(
        confirmation = WebDavCertificateConfirmation(
            url = "https://cloud.example.com/remote.php/dav/files/alice/",
            presented = MockDataProvider.mockWebDavCertificate(1),
            formRevision = 0,
        ),
        onAccept = {},
        onCancel = {},
    )
}

@Preview2
@ComposePreviewWrapper(ButlerPreviewWrapper::class)
@Composable
private fun WebDavCertificateRetrustDialogChangedPreview() {
    WebDavCertificateRetrustDialog(
        confirmation = WebDavRetrustConfirmation(
            locationId = Uuid.parse("88888888-9999-aaaa-bbbb-cccccccccccc"),
            url = "https://cloud.example.com/remote.php/dav/files/alice/",
            endpoint = "alice@cloud.example.com/remote.php/dav/files/alice",
            stored = MockDataProvider.mockWebDavCertificate(1),
            presented = MockDataProvider.mockWebDavCertificate(2),
            trustRevision = 1,
        ),
        onAccept = {},
        onCancel = {},
    )
}

@Preview2
@ComposePreviewWrapper(ButlerPreviewWrapper::class)
@Composable
private fun WebDavCertificateRetrustDialogUntrustedPreview() {
    WebDavCertificateRetrustDialog(
        confirmation = WebDavRetrustConfirmation(
            locationId = Uuid.parse("88888888-9999-aaaa-bbbb-cccccccccccc"),
            url = "https://cloud.example.com/remote.php/dav/files/alice/",
            endpoint = "alice@cloud.example.com/remote.php/dav/files/alice",
            stored = null,
            presented = MockDataProvider.mockWebDavCertificate(2),
            trustRevision = 1,
        ),
        onAccept = {},
        onCancel = {},
    )
}

@Preview2
@ComposePreviewWrapper(ButlerPreviewWrapper::class)
@Composable
private fun WebDavCertificateDetailsPreview() {
    WebDavCertificateDetails(certificate = MockDataProvider.mockWebDavCertificate(1))
}

@Preview2
@ComposePreviewWrapper(ButlerPreviewWrapper::class)
@Composable
private fun WebDavCertificateComparisonPreview() {
    WebDavCertificateComparison(
        stored = MockDataProvider.mockWebDavCertificate(1),
        presented = MockDataProvider.mockWebDavCertificate(2),
    )
}
