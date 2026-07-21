package eu.darken.butler.searcher.ui.search.elements

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.twotone.Add
import androidx.compose.material.icons.twotone.Download
import androidx.compose.material.icons.twotone.ExpandLess
import androidx.compose.material.icons.twotone.ExpandMore
import androidx.compose.material.icons.twotone.Folder
import androidx.compose.material.icons.twotone.Image
import androidx.compose.material.icons.twotone.MusicNote
import androidx.compose.material.icons.twotone.Videocam
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.tooling.preview.PreviewWrapper as ComposePreviewWrapper
import androidx.compose.ui.unit.dp
import eu.darken.butler.common.compose.ButlerChip
import eu.darken.butler.common.compose.ButlerChipDefaults
import eu.darken.butler.common.compose.ButlerPreviewWrapper
import eu.darken.butler.common.compose.Preview2
import eu.darken.butler.common.compose.PreviewWrapper
import eu.darken.butler.common.compose.asComposable
import eu.darken.butler.common.files.LocalPath
import eu.darken.butler.searcher.R
import eu.darken.butler.workspace.contracts.searcher.SearchTarget

@Composable
fun MultiPathChipBar(
    modifier: Modifier = Modifier,
    paths: List<SearchTarget>,
    onPathRemove: (SearchTarget) -> Unit,
    onPathToggle: (SearchTarget) -> Unit,
    onAddPathClick: () -> Unit,
    addableMediaCollections: List<SearchTarget.MediaStore.Collection> = emptyList(),
    onAddMediaTarget: (SearchTarget.MediaStore.Collection) -> Unit = {},
    isSearching: Boolean = false,
) {
    var isExpanded by rememberSaveable { mutableStateOf(false) }
    var isAddMenuOpen by remember { mutableStateOf(false) }
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
                    ButlerChip(
                        label = target.displayText.asComposable(),
                        selected = target.enabled,
                        enabled = !isSearching,
                        onClick = { if (!isSearching) onPathToggle(target) },
                        onRemove = { onPathRemove(target) },
                        colors = ButlerChipDefaults.colors(
                            selectedContainerColor = MaterialTheme.colorScheme.secondaryContainer,
                            selectedContentColor = MaterialTheme.colorScheme.onSecondaryContainer,
                        ),
                    )
                }
                is SearchTarget.MediaStore -> {
                    ButlerChip(
                        label = target.displayText.asComposable(),
                        leadingIcon = target.collection.icon,
                        selected = target.enabled,
                        enabled = !isSearching,
                        onClick = { if (!isSearching) onPathToggle(target) },
                        onRemove = { onPathRemove(target) },
                        colors = ButlerChipDefaults.colors(
                            selectedContainerColor = MaterialTheme.colorScheme.tertiaryContainer,
                            selectedContentColor = MaterialTheme.colorScheme.onTertiaryContainer,
                        ),
                    )
                }
            }
        }

        // Show more/fewer button
        if (hasMore) {
            val remainingCount = paths.size - visibleSize
            ButlerChip(
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

        // Add button with target-type menu
        Box {
            ButlerChip(
                label = stringResource(R.string.searcher_add_path_action),
                leadingIcon = Icons.TwoTone.Add,
                enabled = !isSearching,
                onClick = { isAddMenuOpen = true },
            )
            DropdownMenu(
                // An auto-search can start while the menu is open; close it with the chip
                expanded = isAddMenuOpen && !isSearching,
                onDismissRequest = { isAddMenuOpen = false },
            ) {
                DropdownMenuItem(
                    text = { Text(stringResource(R.string.searcher_add_folder_action)) },
                    leadingIcon = { Icon(Icons.TwoTone.Folder, contentDescription = null) },
                    onClick = {
                        isAddMenuOpen = false
                        onAddPathClick()
                    },
                )
                addableMediaCollections.forEach { collection ->
                    DropdownMenuItem(
                        text = { Text(SearchTarget.MediaStore(collection).displayText.asComposable()) },
                        leadingIcon = { Icon(collection.icon, contentDescription = null) },
                        onClick = {
                            isAddMenuOpen = false
                            onAddMediaTarget(collection)
                        },
                    )
                }
            }
        }
    }
}

private val SearchTarget.MediaStore.Collection.icon: ImageVector
    get() = when (this) {
        SearchTarget.MediaStore.Collection.IMAGES -> Icons.TwoTone.Image
        SearchTarget.MediaStore.Collection.VIDEO -> Icons.TwoTone.Videocam
        SearchTarget.MediaStore.Collection.AUDIO -> Icons.TwoTone.MusicNote
        SearchTarget.MediaStore.Collection.DOWNLOADS -> Icons.TwoTone.Download
    }

@Preview2
@ComposePreviewWrapper(ButlerPreviewWrapper::class)
@Composable
private fun MultiPathChipBarMultiplePreview() {
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

@Preview2
@ComposePreviewWrapper(ButlerPreviewWrapper::class)
@Composable
private fun MultiPathChipBarEmptyPreview() {
    MultiPathChipBar(
        paths = emptyList(),
        onPathRemove = {},
        onPathToggle = {},
        onAddPathClick = {},
    )
}


@Preview2
@ComposePreviewWrapper(ButlerPreviewWrapper::class)
@Composable
private fun MultiPathChipBarSinglePreview() {
    MultiPathChipBar(
        paths = listOf(SearchTarget.Path.from(LocalPath.build("/storage/emulated/0"))),
        onPathRemove = {},
        onPathToggle = {},
        onAddPathClick = {},
    )
}

@Preview2
@ComposePreviewWrapper(ButlerPreviewWrapper::class)
@Composable
private fun MultiPathChipBarMediaTargetsPreview() {
    MultiPathChipBar(
        paths = listOf(
            SearchTarget.MediaStore(SearchTarget.MediaStore.Collection.IMAGES),
            SearchTarget.MediaStore(SearchTarget.MediaStore.Collection.AUDIO, enabled = false),
            SearchTarget.Path.from(LocalPath.build("/storage/emulated/0/Documents")),
        ),
        onPathRemove = {},
        onPathToggle = {},
        onAddPathClick = {},
        addableMediaCollections = listOf(
            SearchTarget.MediaStore.Collection.VIDEO,
            SearchTarget.MediaStore.Collection.DOWNLOADS,
        ),
    )
}

@Preview2
@ComposePreviewWrapper(ButlerPreviewWrapper::class)
@Composable
private fun MultiPathChipBarManyPathsPreview() {
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
