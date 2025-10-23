package eu.darken.butler.searcher.ui.search.input

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.twotone.Add
import androidx.compose.material.icons.twotone.Close
import androidx.compose.material3.AssistChip
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import eu.darken.butler.common.compose.Preview2
import eu.darken.butler.common.compose.PreviewWrapper
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
    FlowRow(
        modifier = modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalArrangement = Arrangement.spacedBy((-8).dp),
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

        paths.forEach { target ->
            when (target) {
                is SearchTarget.Path -> {
                    FilterChip(
                        selected = target.enabled,
                        onClick = { if (!isSearching) onPathToggle(target) },
                        label = {
                            Text(
                                text = target.displayText(),
                                style = MaterialTheme.typography.bodySmall,
                                color = if (target.enabled) {
                                    MaterialTheme.colorScheme.onSecondaryContainer
                                } else {
                                    MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.5f)
                                }
                            )
                        },
                        trailingIcon = {
                            Icon(
                                imageVector = Icons.TwoTone.Close,
                                contentDescription = "Remove path",
                                modifier = Modifier
                                    .size(18.dp)
                                    .clickable(enabled = !isSearching) {
                                        onPathRemove(target)
                                    }
                            )
                        },
                        enabled = !isSearching
                    )
                }
            }
        }

        // Add button
        AssistChip(
            onClick = onAddPathClick,
            label = {
                Text(
                    text = stringResource(R.string.searcher_add_path_action),
                    style = MaterialTheme.typography.bodySmall
                )
            },
            leadingIcon = {
                Icon(
                    imageVector = Icons.TwoTone.Add,
                    contentDescription = null,
                    modifier = Modifier.size(18.dp)
                )
            },
            enabled = !isSearching
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
            modifier = Modifier.padding(16.dp)
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
            modifier = Modifier.padding(16.dp)
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
            modifier = Modifier.padding(16.dp)
        )
    }
}

