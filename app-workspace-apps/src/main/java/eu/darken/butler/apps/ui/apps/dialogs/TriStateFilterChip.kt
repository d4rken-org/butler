package eu.darken.butler.apps.ui.apps.dialogs

import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.tooling.preview.PreviewWrapper as ComposePreviewWrapper
import eu.darken.butler.apps.core.engine.FilterState
import eu.darken.butler.apps.core.engine.next
import eu.darken.butler.apps.ui.apps.items.colors
import eu.darken.butler.apps.ui.apps.items.label
import eu.darken.butler.common.compose.ButlerChip
import eu.darken.butler.common.compose.ButlerChipColors
import eu.darken.butler.common.compose.ButlerChipDefaults
import eu.darken.butler.common.compose.ButlerChipSize
import eu.darken.butler.common.compose.ButlerPreviewWrapper
import eu.darken.butler.common.compose.Preview2
import eu.darken.butler.common.compose.PreviewWrapper
import eu.darken.butler.workspace.contracts.apps.AppTag

@Composable
fun TriStateFilterChip(
    modifier: Modifier = Modifier,
    tag: AppTag,
    state: FilterState,
    onStateChange: (FilterState) -> Unit,
) {
    val tagColors = tag.colors()

    val colors = when (state) {
        FilterState.NEUTRAL -> ButlerChipDefaults.colors()
        FilterState.INCLUDE -> ButlerChipColors(
            containerColor = tagColors.container,
            contentColor = tagColors.content,
            selectedContainerColor = tagColors.container,
            selectedContentColor = tagColors.content,
        )
        FilterState.EXCLUDE -> ButlerChipDefaults.errorColors()
    }

    ButlerChip(
        modifier = modifier,
        label = tag.label(),
        onClick = { onStateChange(state.next()) },
        selected = state != FilterState.NEUTRAL,
        strikethrough = state == FilterState.EXCLUDE,
        size = ButlerChipSize.Large,
        colors = colors,
    )
}

@Preview2
@ComposePreviewWrapper(ButlerPreviewWrapper::class)
@Composable
private fun TriStateFilterChipNeutralPreview() {
    TriStateFilterChip(
        tag = AppTag.System,
        state = FilterState.NEUTRAL,
        onStateChange = {},
    )
}

@Preview2
@ComposePreviewWrapper(ButlerPreviewWrapper::class)
@Composable
private fun TriStateFilterChipIncludePreview() {
    TriStateFilterChip(
        tag = AppTag.System,
        state = FilterState.INCLUDE,
        onStateChange = {},
    )
}

@Preview2
@ComposePreviewWrapper(ButlerPreviewWrapper::class)
@Composable
private fun TriStateFilterChipExcludePreview() {
    TriStateFilterChip(
        tag = AppTag.System,
        state = FilterState.EXCLUDE,
        onStateChange = {},
    )
}

@Preview2
@ComposePreviewWrapper(ButlerPreviewWrapper::class)
@Composable
private fun TriStateFilterChipDisabledTagPreview() {
    TriStateFilterChip(
        tag = AppTag.Disabled,
        state = FilterState.INCLUDE,
        onStateChange = {},
    )
}

@Preview2
@ComposePreviewWrapper(ButlerPreviewWrapper::class)
@Composable
private fun TriStateFilterChipUserAppPreview() {
    TriStateFilterChip(
        tag = AppTag.UserApp,
        state = FilterState.INCLUDE,
        onStateChange = {},
    )
}
