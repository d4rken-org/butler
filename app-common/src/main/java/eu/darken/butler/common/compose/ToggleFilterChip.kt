package eu.darken.butler.common.compose

import androidx.annotation.StringRes
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.twotone.CheckCircle
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.res.stringResource

@Composable
fun ToggleFilterChip(
    modifier: Modifier = Modifier,
    selected: Boolean,
    onClick: () -> Unit,
    @StringRes labelRes: Int,
    iconVector: ImageVector,
    @StringRes contentDescriptionRes: Int,
) {
    ButlerChip(
        modifier = modifier,
        label = stringResource(labelRes),
        onClick = onClick,
        leadingIcon = iconVector,
        selected = selected,
        contentDescription = stringResource(contentDescriptionRes),
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
