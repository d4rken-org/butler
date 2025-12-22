package eu.darken.butler.searcher.ui.search.elements

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.twotone.Add
import androidx.compose.material.icons.twotone.ExpandLess
import androidx.compose.material.icons.twotone.ExpandMore
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import eu.darken.butler.common.compose.Preview2
import eu.darken.butler.common.compose.PreviewWrapper
import eu.darken.butler.common.compose.asComposable
import eu.darken.butler.common.files.LocalPath
import eu.darken.butler.searcher.R
import eu.darken.butler.searcher.core.SearchTarget

@Composable
fun MultiPathChipBar(
    modifier: Modifier = Modifier,
    paths: List<SearchTarget>,
    onPathRemove: (SearchTarget) -> Unit,
    onPathToggle: (SearchTarget) -> Unit,
    onAddPathClick: () -> Unit,
    isSearching: Boolean = false,
) {
    var isExpanded by rememberSaveable { mutableStateOf(false) }
    val visibleSize = 2
    val hasMore = paths.size > visibleSize
    val visiblePaths = if (isExpanded || !hasMore) paths else paths.take(visibleSize)

    FlowRow(
        modifier = modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        if (paths.isEmpty()) {
            Text(
                text = stringResource(R.string.searcher_no_paths_hint),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier
                    .align(Alignment.CenterVertically)
                    .padding(end = 8.dp)
            )
        }

        visiblePaths.forEach { target ->
            when (target) {
                is SearchTarget.Path -> {
                    CompactFilterChip(
                        label = target.displayText.asComposable(),
                        selected = target.enabled,
                        enabled = !isSearching,
                        onClick = { if (!isSearching) onPathToggle(target) },
                        onRemove = { onPathRemove(target) },
                    )
                }
            }
        }

        // Show more/fewer button
        if (hasMore) {
            val remainingCount = paths.size - visibleSize
            CompactAssistChip(
                label = if (isExpanded) {
                    stringResource(R.string.searcher_multipath_show_fewer_action)
                } else {
                    pluralStringResource(
                        R.plurals.searcher_multipath_show_more_action,
                        remainingCount,
                        remainingCount
                    )
                },
                leadingIcon = if (isExpanded) Icons.TwoTone.ExpandLess else Icons.TwoTone.ExpandMore,
                onClick = { isExpanded = !isExpanded },
            )
        }

        // Add button
        CompactAssistChip(
            label = stringResource(R.string.searcher_add_path_action),
            leadingIcon = Icons.TwoTone.Add,
            enabled = !isSearching,
            onClick = onAddPathClick,
        )
    }
}

@Preview2
@Composable
private fun MultiPathChipBarMultiplePreview() {
    PreviewWrapper {
        MultiPathChipBar(
            paths = listOf(
                SearchTarget.Path.from(LocalPath.build("/storage/emulated/0/Download")),
                SearchTarget.Path.from(LocalPath.build("/storage/emulated/0/DCIM")),
                SearchTarget.Path.from(LocalPath.build("/storage/emulated/0/Music"))
            ),
            onPathRemove = {},
            onPathToggle = {},
            onAddPathClick = {},
        )
    }
}

@Preview2
@Composable
private fun MultiPathChipBarEmptyPreview() {
    PreviewWrapper {
        MultiPathChipBar(
            paths = emptyList(),
            onPathRemove = {},
            onPathToggle = {},
            onAddPathClick = {},
        )
    }
}


@Preview2
@Composable
private fun MultiPathChipBarSinglePreview() {
    PreviewWrapper {
        MultiPathChipBar(
            paths = listOf(SearchTarget.Path.from(LocalPath.build("/storage/emulated/0"))),
            onPathRemove = {},
            onPathToggle = {},
            onAddPathClick = {},
        )
    }
}

@Preview2
@Composable
private fun MultiPathChipBarManyPathsPreview() {
    PreviewWrapper {
        MultiPathChipBar(
            paths = listOf(
                SearchTarget.Path.from(LocalPath.build("/storage/emulated/0/aaatest1")),
                SearchTarget.Path.from(LocalPath.build("/storage/emulated/0/aaatrestdir")),
                SearchTarget.Path.from(LocalPath.build("/storage/emulated/0/Android")),
                SearchTarget.Path.from(LocalPath.build("/storage/emulated/0/Download")),
                SearchTarget.Path.from(LocalPath.build("/storage/emulated/0/DCIM")),
                SearchTarget.Path.from(LocalPath.build("/storage/emulated/0/Music")),
                SearchTarget.Path.from(LocalPath.build("/storage/emulated/0/Pictures")),
                SearchTarget.Path.from(LocalPath.build("/storage/emulated/0/Documents")),
                SearchTarget.Path.from(LocalPath.build("/storage/4BBD-D3E7")),
                SearchTarget.Path.from(LocalPath.build("/[primary]/Android/data")),
            ),
            onPathRemove = {},
            onPathToggle = {},
            onAddPathClick = {},
        )
    }
}

