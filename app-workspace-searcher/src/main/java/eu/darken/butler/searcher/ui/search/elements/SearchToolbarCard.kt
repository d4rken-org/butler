package eu.darken.butler.searcher.ui.search.elements

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.expandVertically
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.twotone.InsertDriveFile
import androidx.compose.material.icons.twotone.Description
import androidx.compose.material.icons.twotone.Search
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Checkbox
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.tooling.preview.PreviewWrapper as ComposePreviewWrapper
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import eu.darken.butler.common.compose.ButlerPreviewWrapper
import eu.darken.butler.common.compose.Preview2
import eu.darken.butler.common.compose.PreviewWrapper
import eu.darken.butler.common.files.LocalPath
import eu.darken.butler.searcher.R
import eu.darken.butler.searcher.core.SearcherWorkspace
import eu.darken.butler.searcher.ui.search.SearcherWorkspaceViewModel
import eu.darken.butler.searcher.ui.search.util.SearcherPageAction
import eu.darken.butler.workspace.contracts.searcher.ContentQuery
import eu.darken.butler.workspace.contracts.searcher.FilenameQuery
import eu.darken.butler.workspace.contracts.searcher.SearchTarget
import eu.darken.butler.workspace.core.Workspace
import eu.darken.butler.workspace.ui.common.CutoutCard
import eu.darken.butler.workspace.ui.common.CutoutCardDefaults
import eu.darken.butler.workspace.ui.common.CutoutMode
import eu.darken.butler.workspace.ui.manager.WorkspaceButton
import eu.darken.butler.workspace.ui.manager.WorkspaceButtonDefaults
import eu.darken.butler.workspace.ui.manager.WorkspaceDesign

@Composable
fun SearchToolbarCard(
    modifier: Modifier = Modifier,
    workspaceId: Workspace.Id,
    state: SearcherWorkspaceViewModel.State.Ready?,
    design: WorkspaceDesign,
    collapsedFraction: Float = 0f,
    onAction: (SearcherPageAction) -> Unit,
) {
    val isCollapsed = collapsedFraction > 0.5f
    val cardPadding by animateDpAsState(
        targetValue = if (isCollapsed) CutoutCardDefaults.ContentPaddingCollapsed else CutoutCardDefaults.ContentPaddingExpanded,
        label = "cardPadding"
    )

    CutoutCard(
        modifier = modifier.fillMaxWidth(),
        cutoutContent = if (design.isSingle) {
            {
                WorkspaceButton(
                    currentWorkspaceId = workspaceId,
                    buttonSize = if (isCollapsed) WorkspaceButtonDefaults.sizeCompact else WorkspaceButtonDefaults.sizeDefault,
                )
            }
        } else null,
        cutoutMode = if (isCollapsed) CutoutMode.FullHeight else CutoutMode.Auto,
        gapDistance = if (isCollapsed) CutoutCardDefaults.GapDistanceCollapsed else CutoutCardDefaults.GapDistanceExpanded,
        contentPadding = CutoutCardDefaults.contentPadding(cardPadding),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerHigh),
    ) {
        if (isCollapsed) {
            // Collapsed state - compact display
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(start = 8.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                Icon(
                    modifier = Modifier.size(24.dp),
                    imageVector = Icons.TwoTone.Search,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.primary
                )

                val displayText = buildString {
                    val hasFilename = state?.filenameQuery?.isNotBlank() == true
                    val hasContent = state?.contentQuery?.isNotBlank() == true
                    when {
                        hasFilename && hasContent -> {
                            append(state.filenameQuery)
                            append(" | ")
                            append(state.contentQuery)
                        }

                        hasFilename -> append(state.filenameQuery)
                        hasContent -> append(state.contentQuery)
                    }
                }
                Text(
                    text = displayText.ifBlank { stringResource(R.string.searcher_placeholder_search) },
                    style = MaterialTheme.typography.bodyMedium,
                    color = if (displayText.isBlank()) {
                        MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.6f)
                    } else {
                        MaterialTheme.colorScheme.onSurface
                    },
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f),
                )
            }
        } else {
            // Expanded state - full interactive card with dual pattern fields
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(end = cutoutWidth),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.Top,
            ) {
                // Unified surface for both pattern fields
                Surface(
                    modifier = Modifier.weight(1f),
                    shape = RoundedCornerShape(12.dp),
                    color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.7f),
                ) {
                    Column {
                        // Filename pattern field
                        PatternField(
                            text = state?.filenameQuery.orEmpty(),
                            onTextChange = { onAction(SearcherPageAction.Search.UpdateFilenameQuery(it)) },
                            onSearch = { onAction(SearcherPageAction.Search.Explicit) },
                            placeholder = stringResource(R.string.searcher_placeholder_filename),
                            leadingIcon = Icons.AutoMirrored.TwoTone.InsertDriveFile,
                            caseSensitive = state?.filenameOptions?.caseSensitive ?: false,
                            wholeWord = state?.filenameOptions?.wholeWord ?: false,
                            useRegex = state?.filenameOptions?.useRegex ?: false,
                            isSearching = state?.isSearching ?: false,
                            onToggleCaseSensitive = { onAction(SearcherPageAction.Options.ToggleFilenameCaseSensitive) },
                            onToggleWholeWord = { onAction(SearcherPageAction.Options.ToggleFilenameWholeWord) },
                            onToggleRegex = { onAction(SearcherPageAction.Options.ToggleFilenameRegex) },
                            extraMenuItems = {
                                DropdownMenuItem(
                                    text = { Text(stringResource(R.string.searcher_option_search_content_label)) },
                                    onClick = { onAction(SearcherPageAction.Options.ToggleContentSearch) },
                                    leadingIcon = {
                                        Checkbox(
                                            checked = state?.contentSearchEnabled ?: false,
                                            onCheckedChange = null,
                                        )
                                    },
                                )
                                HorizontalDivider(modifier = Modifier.padding(vertical = 4.dp))
                            },
                        )

                        // Content pattern field (conditionally visible)
                        AnimatedVisibility(
                            visible = state?.contentSearchEnabled ?: false,
                            enter = expandVertically(),
                            exit = shrinkVertically(),
                        ) {
                            Column {
                                // Divider between fields
                                HorizontalDivider(
                                    modifier = Modifier.padding(horizontal = 12.dp),
                                    color = MaterialTheme.colorScheme.outline.copy(alpha = 0.2f),
                                )

                                // Content pattern field
                                PatternField(
                                    text = state?.contentQuery.orEmpty(),
                                    onTextChange = { onAction(SearcherPageAction.Search.UpdateContentQuery(it)) },
                                    onSearch = { onAction(SearcherPageAction.Search.Explicit) },
                                    placeholder = stringResource(R.string.searcher_placeholder_content),
                                    leadingIcon = Icons.TwoTone.Description,
                                    caseSensitive = state?.contentOptions?.caseSensitive ?: false,
                                    wholeWord = state?.contentOptions?.wholeWord ?: false,
                                    useRegex = state?.contentOptions?.useRegex ?: false,
                                    isSearching = state?.isSearching ?: false,
                                    onToggleCaseSensitive = { onAction(SearcherPageAction.Options.ToggleContentCaseSensitive) },
                                    onToggleWholeWord = { onAction(SearcherPageAction.Options.ToggleContentWholeWord) },
                                    onToggleRegex = { onAction(SearcherPageAction.Options.ToggleContentRegex) },
                                )
                            }
                        }
                    }
                }
            }


            Column {
                Spacer(
                    modifier = Modifier.padding(vertical = 4.dp),
                )

                FilterChipBar(
                    filter = state?.currentFilter ?: eu.darken.butler.workspace.contracts.searcher.SearchFilter(),
                    onConditionClick = { onAction(SearcherPageAction.Filter.EditCondition(it)) },
                    onAddSizeCondition = { onAction(SearcherPageAction.Filter.OpenSizeConditionEditor) },
                    onAddDateCondition = { onAction(SearcherPageAction.Filter.OpenDateConditionEditor) },
                    onAddTypeCondition = { onAction(SearcherPageAction.Filter.OpenTypeConditionEditor) },
                    onRemoveCondition = { onAction(SearcherPageAction.Filter.RemoveCondition(it)) },
                )
            }



            Column {
                Spacer(
                    modifier = Modifier.padding(vertical = 4.dp),
                )

                MultiPathChipBar(
                    paths = state?.workspaceState?.searchTargets.orEmpty(),
                    onPathRemove = { onAction(SearcherPageAction.Targets.Remove(it)) },
                    onPathToggle = { onAction(SearcherPageAction.Targets.ToggleEnabled(it)) },
                    onAddPathClick = { onAction(SearcherPageAction.Targets.OpenPicker) },
                    isSearching = state?.isSearching ?: false,
                )
            }

        }
    }
}

@Preview2
@ComposePreviewWrapper(ButlerPreviewWrapper::class)
@Composable
private fun SearchToolbarCardPreview() {
    SearchToolbarCard(
        modifier = Modifier.padding(16.dp),
        workspaceId = Workspace.Id(),
        state = SearcherWorkspaceViewModel.State.Ready(
            workspaceState = SearcherWorkspace.State(
                searchTargets = listOf(
                    SearchTarget.Path.from(LocalPath.build("/storage/emulated/0/Documents")),
                    SearchTarget.Path.from(LocalPath.build("/storage/emulated/0/Download")),
                ),
            ),
            filenameQuery = "*.kt",
            contentQuery = "fun main",
            filenameOptions = FilenameQuery(useRegex = true),
            contentOptions = ContentQuery(wholeWord = true),
            contentSearchEnabled = true,
        ),
        design = WorkspaceDesign(),
        onAction = {},
    )
}

@Preview2
@ComposePreviewWrapper(ButlerPreviewWrapper::class)
@Composable
private fun SearchToolbarCardCollapsedPreview() {
    SearchToolbarCard(
        modifier = Modifier.padding(16.dp),
        workspaceId = Workspace.Id(),
        state = SearcherWorkspaceViewModel.State.Ready(
            workspaceState = SearcherWorkspace.State(
                searchTargets = listOf(
                    SearchTarget.Path.from(LocalPath.build("/storage/emulated/0/Documents")),
                    SearchTarget.Path.from(LocalPath.build("/storage/emulated/0/Download")),
                ),
            ),
            filenameQuery = "*.kt",
            contentQuery = "fun main",
        ),
        design = WorkspaceDesign(),
        collapsedFraction = 1f,
        onAction = {},
    )
}

@Preview2
@ComposePreviewWrapper(ButlerPreviewWrapper::class)
@Composable
private fun SearchToolbarCardRtlPreview() {
    CompositionLocalProvider(LocalLayoutDirection provides LayoutDirection.Rtl) {
        SearchToolbarCard(
            modifier = Modifier.padding(16.dp),
            workspaceId = Workspace.Id(),
            state = SearcherWorkspaceViewModel.State.Ready(
                workspaceState = SearcherWorkspace.State(
                    searchTargets = listOf(
                        SearchTarget.Path.from(LocalPath.build("/storage/emulated/0/Documents")),
                        SearchTarget.Path.from(LocalPath.build("/storage/emulated/0/Download")),
                    ),
                ),
                filenameQuery = "*.kt",
                contentQuery = "fun main",
                filenameOptions = FilenameQuery(useRegex = true),
                contentOptions = ContentQuery(wholeWord = true),
                contentSearchEnabled = true,
            ),
            design = WorkspaceDesign(),
            onAction = {},
        )
    }
}

@Preview2
@ComposePreviewWrapper(ButlerPreviewWrapper::class)
@Composable
private fun SearchToolbarCardCollapsedRtlPreview() {
    CompositionLocalProvider(LocalLayoutDirection provides LayoutDirection.Rtl) {
        SearchToolbarCard(
            modifier = Modifier.padding(16.dp),
            workspaceId = Workspace.Id(),
            state = SearcherWorkspaceViewModel.State.Ready(
                workspaceState = SearcherWorkspace.State(
                    searchTargets = listOf(
                        SearchTarget.Path.from(LocalPath.build("/storage/emulated/0/Documents")),
                        SearchTarget.Path.from(LocalPath.build("/storage/emulated/0/Download")),
                    ),
                ),
                filenameQuery = "*.kt",
                contentQuery = "fun main",
            ),
            design = WorkspaceDesign(),
            collapsedFraction = 1f,
            onAction = {},
        )
    }
}