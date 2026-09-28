package eu.darken.butler.explorer.ui.explorer.elements

import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.tooling.preview.PreviewWrapper as ComposePreviewWrapper
import androidx.compose.ui.unit.dp
import eu.darken.butler.common.compose.ButlerPreviewWrapper
import eu.darken.butler.common.compose.Preview2
import eu.darken.butler.common.compose.PreviewWrapper
import eu.darken.butler.explorer.R
import eu.darken.butler.explorer.ui.explorer.SmbUpgradeHint
import eu.darken.butler.common.R as CommonR

/** Snackbar-style bar telling a free user that browsing network storage needs the upgrade. */
@Composable
fun SmbUpgradeHintBar(
    modifier: Modifier = Modifier,
    hint: SmbUpgradeHint,
    onUpgrade: () -> Unit,
) {
    Card(
        modifier = modifier
            .fillMaxWidth()
            .border(
                width = 1.dp,
                color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.3f),
                shape = RoundedCornerShape(16.dp),
            ),
        elevation = CardDefaults.cardElevation(defaultElevation = 8.dp),
        shape = RoundedCornerShape(16.dp),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceContainerHighest,
        ),
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(start = 16.dp, end = 8.dp, top = 4.dp, bottom = 4.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Text(
                modifier = Modifier.weight(1f),
                text = stringResource(
                    when (hint.reason) {
                        SmbUpgradeHint.Reason.SAVED -> R.string.explorer_network_upgrade_hint_saved
                        SmbUpgradeHint.Reason.LOCKED -> R.string.explorer_network_upgrade_hint_locked
                    }
                ),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurface,
            )
            TextButton(onClick = onUpgrade) {
                Text(
                    text = stringResource(CommonR.string.general_upgrade_action),
                    style = MaterialTheme.typography.labelLarge,
                )
            }
        }
    }
}

@Preview2
@ComposePreviewWrapper(ButlerPreviewWrapper::class)
@Composable
private fun SmbUpgradeHintBarSavedPreview() {
    PreviewWrapper {
        SmbUpgradeHintBar(hint = SmbUpgradeHint(id = 1L, reason = SmbUpgradeHint.Reason.SAVED), onUpgrade = {})
    }
}

@Preview2
@ComposePreviewWrapper(ButlerPreviewWrapper::class)
@Composable
private fun SmbUpgradeHintBarLockedPreview() {
    PreviewWrapper {
        SmbUpgradeHintBar(hint = SmbUpgradeHint(id = 1L, reason = SmbUpgradeHint.Reason.LOCKED), onUpgrade = {})
    }
}
