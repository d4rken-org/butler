package eu.darken.butler.apps.ui.apps.items

import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.twotone.Scale
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.tooling.preview.PreviewWrapper as ComposePreviewWrapper
import eu.darken.butler.apps.R
import eu.darken.butler.apps.ui.apps.preview.AppsMockDataProvider
import eu.darken.butler.common.compose.ButlerChip
import eu.darken.butler.common.compose.ButlerChipDefaults
import eu.darken.butler.common.compose.ButlerChipSize
import eu.darken.butler.common.compose.ButlerPreviewWrapper
import eu.darken.butler.common.compose.Preview2
import eu.darken.butler.common.compose.PreviewWrapper
import eu.darken.butler.common.formatFileSize

@Composable
fun AppSizeChip(
    bytes: Long,
    modifier: Modifier = Modifier,
    compact: Boolean = false,
) {
    val label = formatFileSize(bytes)
    ButlerChip(
        modifier = modifier,
        label = label,
        leadingIcon = Icons.TwoTone.Scale,
        size = if (compact) ButlerChipSize.Mini else ButlerChipSize.Compact,
        colors = ButlerChipDefaults.colors(),
        contentDescription = stringResource(R.string.apps_size_chip_desc, label),
    )
}

@Preview2
@ComposePreviewWrapper(ButlerPreviewWrapper::class)
@Composable
private fun AppSizeChipPreview() {
    AppSizeChip(bytes = AppsMockDataProvider.MockSizes.mb(128))
}

@Preview2
@ComposePreviewWrapper(ButlerPreviewWrapper::class)
@Composable
private fun AppSizeChipCompactPreview() {
    AppSizeChip(
        bytes = AppsMockDataProvider.MockSizes.gb(3),
        compact = true,
    )
}
