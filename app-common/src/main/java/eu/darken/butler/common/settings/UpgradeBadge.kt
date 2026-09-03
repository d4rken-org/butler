package eu.darken.butler.common.settings

import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.twotone.Stars
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.tooling.preview.PreviewWrapper as ComposePreviewWrapper
import androidx.compose.ui.unit.dp
import eu.darken.butler.common.R
import eu.darken.butler.common.compose.ButlerPreviewWrapper
import eu.darken.butler.common.compose.Preview2

/**
 * Inline "Pro"/"FOSS" chip rendered next to a settings row title when the feature requires an
 * upgrade. Pure presentation, taps are handled by the hosting row.
 *
 * [label] exists so previews can show both flavor values, production callers use the default.
 */
@Composable
fun UpgradeBadge(
    modifier: Modifier = Modifier,
    label: String = stringResource(R.string.app_name_upgrade_postfix),
) {
    Row(
        modifier = modifier,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        // Decorative, the adjacent label is the accessible announcement.
        Icon(
            imageVector = Icons.TwoTone.Stars,
            contentDescription = null,
            tint = MaterialTheme.colorScheme.primary,
            modifier = Modifier.size(16.dp),
        )
        Spacer(Modifier.width(2.dp))
        Text(
            text = label,
            color = MaterialTheme.colorScheme.primary,
            style = MaterialTheme.typography.labelSmall,
            maxLines = 1,
        )
    }
}

@Preview2
@ComposePreviewWrapper(ButlerPreviewWrapper::class)
@Composable
private fun UpgradeBadgePreview() {
    UpgradeBadge()
}

@Preview2
@ComposePreviewWrapper(ButlerPreviewWrapper::class)
@Composable
private fun UpgradeBadgeFossPreview() {
    UpgradeBadge(label = "FOSS")
}
