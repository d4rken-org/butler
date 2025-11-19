package eu.darken.butler.common.compose

import androidx.annotation.StringRes
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.twotone.CheckCircle
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FilterChipDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow

@Composable
fun ToggleFilterChip(
    modifier: Modifier = Modifier,
    selected: Boolean,
    onClick: () -> Unit,
    @StringRes labelRes: Int,
    iconVector: ImageVector,
    @StringRes contentDescriptionRes: Int,
) {
    val contentDesc = stringResource(contentDescriptionRes)
    FilterChip(
        selected = selected,
        onClick = onClick,
        label = {
            Text(
                text = stringResource(labelRes),
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
        },
        leadingIcon = {
            Icon(
                imageVector = iconVector,
                contentDescription = null,
                modifier = Modifier.size(FilterChipDefaults.IconSize)
            )
        },
        colors = FilterChipDefaults.filterChipColors(
            selectedContainerColor = MaterialTheme.colorScheme.primaryContainer,
            selectedLabelColor = MaterialTheme.colorScheme.onPrimaryContainer,
            selectedLeadingIconColor = MaterialTheme.colorScheme.onPrimaryContainer
        ),
        modifier = modifier.semantics {
            contentDescription = contentDesc
        }
    )
}

@Preview2
@Composable
private fun ToggleFilterChipPreview() {
    PreviewWrapper {
        ToggleFilterChip(
            selected = true,
            onClick = {},
            labelRes = android.R.string.ok,
            iconVector = Icons.TwoTone.CheckCircle,
            contentDescriptionRes = android.R.string.ok,
        )
    }
}
