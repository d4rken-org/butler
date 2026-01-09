package eu.darken.butler.apps.ui.apps.dialogs

import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import eu.darken.butler.apps.core.AppTag
import eu.darken.butler.apps.core.engine.FilterState
import eu.darken.butler.apps.core.engine.next
import eu.darken.butler.apps.ui.apps.items.colors
import eu.darken.butler.apps.ui.apps.items.label
import eu.darken.butler.common.compose.ButlerChip
import eu.darken.butler.common.compose.ButlerChipColors
import eu.darken.butler.common.compose.ButlerChipDefaults
import eu.darken.butler.common.compose.ButlerChipSize
import eu.darken.butler.common.compose.Preview2
import eu.darken.butler.common.compose.PreviewWrapper

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
@Composable
private fun TriStateFilterChipNeutralPreview() {
    PreviewWrapper {
        TriStateFilterChip(
            tag = AppTag.System,
            state = FilterState.NEUTRAL,
            onStateChange = {},
        )
    }
}

@Preview2
@Composable
private fun TriStateFilterChipIncludePreview() {
    PreviewWrapper {
        TriStateFilterChip(
            tag = AppTag.System,
            state = FilterState.INCLUDE,
            onStateChange = {},
        )
    }
}

@Preview2
@Composable
private fun TriStateFilterChipExcludePreview() {
    PreviewWrapper {
        TriStateFilterChip(
            tag = AppTag.System,
            state = FilterState.EXCLUDE,
            onStateChange = {},
        )
    }
}

@Preview2
@Composable
private fun TriStateFilterChipDisabledTagPreview() {
    PreviewWrapper {
        TriStateFilterChip(
            tag = AppTag.Disabled,
            state = FilterState.INCLUDE,
            onStateChange = {},
        )
    }
}

@Preview2
@Composable
private fun TriStateFilterChipUserAppPreview() {
    PreviewWrapper {
        TriStateFilterChip(
            tag = AppTag.UserApp,
            state = FilterState.INCLUDE,
            onStateChange = {},
        )
    }
}
