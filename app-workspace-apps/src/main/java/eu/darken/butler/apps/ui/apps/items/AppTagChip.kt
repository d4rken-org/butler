package eu.darken.butler.apps.ui.apps.items

import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import eu.darken.butler.apps.core.AppTag
import eu.darken.butler.common.compose.ButlerChip
import eu.darken.butler.common.compose.ButlerChipColors
import eu.darken.butler.common.compose.ButlerChipSize
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
@Composable
private fun AppTagChipSystemPreview() {
    PreviewWrapper {
        AppTagChip(tag = AppTag.System)
    }
}

@Preview2
@Composable
private fun AppTagChipDisabledPreview() {
    PreviewWrapper {
        AppTagChip(tag = AppTag.Disabled)
    }
}

@Preview2
@Composable
private fun AppTagChipSideloadedPreview() {
    PreviewWrapper {
        AppTagChip(tag = AppTag.Sideloaded)
    }
}

@Preview2
@Composable
private fun AppTagChipSplitApkPreview() {
    PreviewWrapper {
        AppTagChip(tag = AppTag.SplitApk)
    }
}

@Preview2
@Composable
private fun AppTagChipUserWithLabelPreview() {
    PreviewWrapper {
        AppTagChip(
            tag = AppTag.User(handleId = 10, label = "Work")
        )
    }
}

@Preview2
@Composable
private fun AppTagChipUserWithoutLabelPreview() {
    PreviewWrapper {
        AppTagChip(
            tag = AppTag.User(handleId = 10)
        )
    }
}
