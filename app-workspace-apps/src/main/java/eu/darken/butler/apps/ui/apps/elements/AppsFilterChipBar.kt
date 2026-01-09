package eu.darken.butler.apps.ui.apps.elements

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.twotone.Add
import androidx.compose.runtime.Composable
import androidx.compose.runtime.key
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import eu.darken.butler.apps.R
import eu.darken.butler.apps.core.AppTag
import eu.darken.butler.apps.core.TagFilterConfig
import eu.darken.butler.apps.ui.apps.items.colors
import eu.darken.butler.apps.ui.apps.items.label
import eu.darken.butler.common.compose.ButlerChip
import eu.darken.butler.common.compose.ButlerChipColors
import eu.darken.butler.common.compose.ButlerChipDefaults
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
            leadingIcon = Icons.TwoTone.Add,
            onClick = onAddClick,
        )
    }
}

@Preview2
@Composable
private fun AppsFilterChipBarEmptyPreview() {
    PreviewWrapper {
        AppsFilterChipBar(
            modifier = Modifier.padding(16.dp),
            filterConfig = TagFilterConfig(),
            onTagRemove = { _, _ -> },
            onAddClick = {},
        )
    }
}

@Preview2
@Composable
private fun AppsFilterChipBarIncludeOnlyPreview() {
    PreviewWrapper {
        AppsFilterChipBar(
            modifier = Modifier.padding(16.dp),
            filterConfig = TagFilterConfig(
                includeTags = setOf(AppTag.System, AppTag.Disabled),
            ),
            onTagRemove = { _, _ -> },
            onAddClick = {},
        )
    }
}

@Preview2
@Composable
private fun AppsFilterChipBarExcludeOnlyPreview() {
    PreviewWrapper {
        AppsFilterChipBar(
            modifier = Modifier.padding(16.dp),
            filterConfig = TagFilterConfig(
                excludeTags = setOf(AppTag.System, AppTag.Disabled),
            ),
            onTagRemove = { _, _ -> },
            onAddClick = {},
        )
    }
}

@Preview2
@Composable
private fun AppsFilterChipBarMixedPreview() {
    PreviewWrapper {
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
}
