package eu.darken.butler.apps.ui.apps.items

import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.tooling.preview.PreviewWrapper as ComposePreviewWrapper
import eu.darken.butler.apps.core.AppTag
import eu.darken.butler.common.compose.ButlerChip
import eu.darken.butler.common.compose.ButlerChipColors
import eu.darken.butler.common.compose.ButlerChipSize
import eu.darken.butler.common.compose.ButlerPreviewWrapper
import eu.darken.butler.common.compose.Preview2
import eu.darken.butler.common.compose.PreviewWrapper

@Composable
fun AppTagChip(
    tag: AppTag,
    modifier: Modifier = Modifier,
    compact: Boolean = false,
) {
    val tagColors = tag.colors()
    ButlerChip(
        modifier = modifier,
        label = tag.label(),
        size = if (compact) ButlerChipSize.Mini else ButlerChipSize.Compact,
        colors = ButlerChipColors(
            containerColor = tagColors.container,
            contentColor = tagColors.content,
            selectedContainerColor = tagColors.container,
            selectedContentColor = tagColors.content,
        ),
    )
}

@Preview2
@ComposePreviewWrapper(ButlerPreviewWrapper::class)
@Composable
private fun AppTagChipSystemPreview() {
    AppTagChip(tag = AppTag.System)
}

@Preview2
@ComposePreviewWrapper(ButlerPreviewWrapper::class)
@Composable
private fun AppTagChipDisabledPreview() {
    AppTagChip(tag = AppTag.Disabled)
}

@Preview2
@ComposePreviewWrapper(ButlerPreviewWrapper::class)
@Composable
private fun AppTagChipSideloadedPreview() {
    AppTagChip(tag = AppTag.Sideloaded)
}

@Preview2
@ComposePreviewWrapper(ButlerPreviewWrapper::class)
@Composable
private fun AppTagChipSplitApkPreview() {
    AppTagChip(tag = AppTag.SplitApk)
}

@Preview2
@ComposePreviewWrapper(ButlerPreviewWrapper::class)
@Composable
private fun AppTagChipUserWithLabelPreview() {
    AppTagChip(
        tag = AppTag.User(handleId = 10, label = "Work")
    )
}

@Preview2
@ComposePreviewWrapper(ButlerPreviewWrapper::class)
@Composable
private fun AppTagChipUserWithoutLabelPreview() {
    AppTagChip(
        tag = AppTag.User(handleId = 10)
    )
}
