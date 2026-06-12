package eu.darken.butler.apps.ui.apps.elements

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.twotone.Add
import androidx.compose.material.icons.twotone.Edit
import androidx.compose.runtime.Composable
import androidx.compose.runtime.key
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.tooling.preview.PreviewWrapper as ComposePreviewWrapper
import androidx.compose.ui.unit.dp
import eu.darken.butler.apps.R
import eu.darken.butler.workspace.contracts.apps.AppTag
import eu.darken.butler.workspace.contracts.apps.TagFilterConfig
import eu.darken.butler.apps.ui.apps.items.colors
import eu.darken.butler.apps.ui.apps.items.label
import eu.darken.butler.common.compose.ButlerChip
import eu.darken.butler.common.compose.ButlerChipColors
import eu.darken.butler.common.compose.ButlerChipDefaults
import eu.darken.butler.common.compose.ButlerPreviewWrapper
import eu.darken.butler.common.compose.Preview2
import eu.darken.butler.common.compose.PreviewWrapper

@Composable
fun AppsFilterChipBar(
    modifier: Modifier = Modifier,
    filterConfig: TagFilterConfig,
    onTagRemove: (AppTag, isExcluded: Boolean) -> Unit,
    onAddClick: () -> Unit,
) {
    FlowRow(
        modifier = modifier,
        horizontalArrangement = Arrangement.spacedBy(6.dp),
        verticalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        filterConfig.includeTags.forEach { tag ->
            key(tag) {
                val tagColors = tag.colors()
                ButlerChip(
                    label = tag.label(),
                    onRemove = { onTagRemove(tag, false) },
                    colors = ButlerChipColors(
                        containerColor = tagColors.container,
                        contentColor = tagColors.content,
                        selectedContainerColor = tagColors.container,
                        selectedContentColor = tagColors.content,
                    ),
                )
            }
        }

        filterConfig.excludeTags.forEach { tag ->
            key(tag) {
                ButlerChip(
                    label = tag.label(),
                    onRemove = { onTagRemove(tag, true) },
                    strikethrough = true,
                    colors = ButlerChipDefaults.errorColors(),
                )
            }
        }

        // Add chip
        ButlerChip(
            label = stringResource(R.string.apps_filter_add_action),
            leadingIcon = if (filterConfig.isEmpty) Icons.TwoTone.Add else Icons.TwoTone.Edit,
            onClick = onAddClick,
        )
    }
}

@Preview2
@ComposePreviewWrapper(ButlerPreviewWrapper::class)
@Composable
private fun AppsFilterChipBarEmptyPreview() {
    AppsFilterChipBar(
        modifier = Modifier.padding(16.dp),
        filterConfig = TagFilterConfig(),
        onTagRemove = { _, _ -> },
        onAddClick = {},
    )
}

@Preview2
@ComposePreviewWrapper(ButlerPreviewWrapper::class)
@Composable
private fun AppsFilterChipBarIncludeOnlyPreview() {
    AppsFilterChipBar(
        modifier = Modifier.padding(16.dp),
        filterConfig = TagFilterConfig(
            includeTags = setOf(AppTag.System, AppTag.Disabled),
        ),
        onTagRemove = { _, _ -> },
        onAddClick = {},
    )
}

@Preview2
@ComposePreviewWrapper(ButlerPreviewWrapper::class)
@Composable
private fun AppsFilterChipBarExcludeOnlyPreview() {
    AppsFilterChipBar(
        modifier = Modifier.padding(16.dp),
        filterConfig = TagFilterConfig(
            excludeTags = setOf(AppTag.System, AppTag.Disabled),
        ),
        onTagRemove = { _, _ -> },
        onAddClick = {},
    )
}

@Preview2
@ComposePreviewWrapper(ButlerPreviewWrapper::class)
@Composable
private fun AppsFilterChipBarMixedPreview() {
    AppsFilterChipBar(
        modifier = Modifier.padding(16.dp),
        filterConfig = TagFilterConfig(
            includeTags = setOf(AppTag.UserApp, AppTag.Enabled),
            excludeTags = setOf(AppTag.System, AppTag.Disabled),
        ),
        onTagRemove = { _, _ -> },
        onAddClick = {},
    )
}
