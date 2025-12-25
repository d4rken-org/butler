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
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.twotone.InsertDriveFile
import androidx.compose.material.icons.twotone.Description
import androidx.compose.material.icons.twotone.Search
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Checkbox
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import eu.darken.butler.common.compose.Preview2
import eu.darken.butler.common.compose.PreviewWrapper
import eu.darken.butler.common.files.LocalPath
import eu.darken.butler.searcher.R
import eu.darken.butler.searcher.core.ContentQuery
import eu.darken.butler.searcher.core.FilenameQuery
import eu.darken.butler.searcher.core.FilterCondition
import eu.darken.butler.searcher.core.SearchTarget
import eu.darken.butler.searcher.ui.search.SearcherWorkspaceViewModel
import eu.darken.butler.workspace.core.Workspace
import eu.darken.butler.workspace.ui.manager.WorkspaceActionHandler
import eu.darken.butler.workspace.ui.manager.WorkspaceButton
import eu.darken.butler.workspace.ui.manager.WorkspaceButtonViewModel
import eu.darken.butler.workspace.ui.manager.WorkspaceDesign

@Composable
fun SearchToolbarCard(
    workspaceId: Workspace.Id,
    modifier: Modifier = Modifier,
    state: SearcherWorkspaceViewModel.State,
    design: WorkspaceDesign,
    collapsedFraction: Float = 0f,
    onUpdateFilenameQuery: (String) -> Unit,
    onUpdateContentQuery: (String) -> Unit,
    onRemoveSearchPath: (SearchTarget) -> Unit,
    onTogglePathEnabled: (SearchTarget) -> Unit,
    onPerformSearch: () -> Unit,
    onExplicitSearch: () -> Unit = onPerformSearch,
    onCancelSearch: () -> Unit,
    onToggleFilenameCaseSensitive: () -> Unit,
    onToggleFilenameWholeWord: () -> Unit,
    onToggleFilenameRegex: () -> Unit,
    onToggleContentCaseSensitive: () -> Unit,
    onToggleContentWholeWord: () -> Unit,
    onToggleContentRegex: () -> Unit,
    onToggleContentSearch: () -> Unit,
    onOpenPathPicker: (() -> Unit)? = null,
    onConditionClick: ((FilterCondition) -> Unit)? = null,
    onAddSizeCondition: (() -> Unit)? = null,
    onAddDateCondition: (() -> Unit)? = null,
    onAddTypeCondition: (() -> Unit)? = null,
    onRemoveCondition: ((FilterCondition) -> Unit)? = null,
    workspaceButtonState: WorkspaceButtonViewModel.State? = null,
    workspaceActionHandler: WorkspaceActionHandler? = null,
) {
    val isCollapsed = collapsedFraction > 0.5f
    val cardPadding by animateDpAsState(
        targetValue = if (isCollapsed) 8.dp else 16.dp,
        label = "cardPadding"
    )

    Card(
        modifier = modifier.fillMaxWidth(),
        elevation = CardDefaults.cardElevation(defaultElevation = 6.dp),
        shape = RoundedCornerShape(12.dp),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceContainerHigh
        )
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(cardPadding),
            verticalArrangement = Arrangement.spacedBy(4.dp)
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
                        val hasFilename = state.filenameQuery.isNotBlank()
                        val hasContent = state.contentQuery.isNotBlank()
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

                    if (design.isSingle) {
                        WorkspaceButton(
                            buttonSize = 32.dp,
                            state = workspaceButtonState,
                            workspaceActionHandler = workspaceActionHandler,
                        )
                    }
                }
            } else {
                // Expanded state - full interactive card with dual pattern fields
                Row(
                    modifier = Modifier.fillMaxWidth(),
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
                                text = state.filenameQuery,
                                onTextChange = onUpdateFilenameQuery,
                                onSearch = onExplicitSearch,
                                placeholder = stringResource(R.string.searcher_placeholder_filename),
                                leadingIcon = Icons.AutoMirrored.TwoTone.InsertDriveFile,
                                caseSensitive = state.filenameOptions.caseSensitive,
                                wholeWord = state.filenameOptions.wholeWord,
                                useRegex = state.filenameOptions.useRegex,
                                isSearching = state.isSearching,
                                onToggleCaseSensitive = onToggleFilenameCaseSensitive,
                                onToggleWholeWord = onToggleFilenameWholeWord,
                                onToggleRegex = onToggleFilenameRegex,
                                extraMenuItems = {
                                    DropdownMenuItem(
                                        text = { Text(stringResource(R.string.searcher_option_search_content_label)) },
                                        onClick = onToggleContentSearch,
                                        leadingIcon = {
                                            Checkbox(
                                                checked = state.contentSearchEnabled,
                                                onCheckedChange = null,
                                            )
                                        },
                                    )
                                    HorizontalDivider(modifier = Modifier.padding(vertical = 4.dp))
                                },
                            )

                            // Content pattern field (conditionally visible)
                            AnimatedVisibility(
                                visible = state.contentSearchEnabled,
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
                                        text = state.contentQuery,
                                        onTextChange = onUpdateContentQuery,
                                        onSearch = onExplicitSearch,
                                        placeholder = stringResource(R.string.searcher_placeholder_content),
                                        leadingIcon = Icons.TwoTone.Description,
                                        caseSensitive = state.contentOptions.caseSensitive,
                                        wholeWord = state.contentOptions.wholeWord,
                                        useRegex = state.contentOptions.useRegex,
                                        isSearching = state.isSearching,
                                        onToggleCaseSensitive = onToggleContentCaseSensitive,
                                        onToggleWholeWord = onToggleContentWholeWord,
                                        onToggleRegex = onToggleContentRegex,
                                    )
                                }
                            }
                        }
                    }

                    if (design.isSingle) {
                        Spacer(modifier = Modifier.width(8.dp))

                        WorkspaceButton(
                            state = workspaceButtonState,
                            currentWorkspaceId = workspaceId,
                            workspaceActionHandler = workspaceActionHandler,
                        )
                    }
                }


                    Column {
                        HorizontalDivider(
                            modifier = Modifier.padding(vertical = 8.dp),
                            color = MaterialTheme.colorScheme.outline.copy(alpha = 0.12f),
                        )

                        FilterChipBar(
                            filter = state.currentFilter,
                            onConditionClick = { onConditionClick?.invoke(it) },
                            onAddSizeCondition = { onAddSizeCondition?.invoke() },
                            onAddDateCondition = { onAddDateCondition?.invoke() },
                            onAddTypeCondition = { onAddTypeCondition?.invoke() },
                            onRemoveCondition = { onRemoveCondition?.invoke(it) },
                        )
                    }



                    Column {
                        HorizontalDivider(
                            modifier = Modifier.padding(vertical = 8.dp),
                            color = MaterialTheme.colorScheme.outline.copy(alpha = 0.12f),
                        )

                        MultiPathChipBar(
                            paths = state.searchTargets,
                            onPathRemove = onRemoveSearchPath,
                            onPathToggle = onTogglePathEnabled,
                            onAddPathClick = { onOpenPathPicker?.invoke() },
                            isSearching = state.isSearching,
                        )
                    }

            }
        }
    }
}

@Preview2
@Composable
private fun SearchToolbarCardPreview() {
    PreviewWrapper {
        SearchToolbarCard(
            workspaceId = Workspace.Id(),
            state = SearcherWorkspaceViewModel.State(
                id = Workspace.Id(),
                searchTargets = listOf(
                    SearchTarget.Path.from(LocalPath.build("/storage/emulated/0/Documents")),
                    SearchTarget.Path.from(LocalPath.build("/storage/emulated/0/Download")),
                ),
                filenameQuery = "*.kt",
                contentQuery = "TODO",
                filenameOptions = FilenameQuery(useRegex = true),
                contentOptions = ContentQuery(wholeWord = true),
                contentSearchEnabled = true,
            ),
            design = WorkspaceDesign(),
            onUpdateFilenameQuery = {},
            onUpdateContentQuery = {},
            onRemoveSearchPath = {},
            onTogglePathEnabled = {},
            onPerformSearch = {},
            onCancelSearch = {},
            onToggleFilenameCaseSensitive = {},
            onToggleFilenameWholeWord = {},
            onToggleFilenameRegex = {},
            onToggleContentCaseSensitive = {},
            onToggleContentWholeWord = {},
            onToggleContentRegex = {},
            onToggleContentSearch = {},
            modifier = Modifier.padding(16.dp),
        )
    }
}

@Preview2
@Composable
private fun SearchToolbarCardCollapsedPreview() {
    PreviewWrapper {
        SearchToolbarCard(
            workspaceId = Workspace.Id(),
            state = SearcherWorkspaceViewModel.State(
                id = Workspace.Id(),
                searchTargets = listOf(
                    SearchTarget.Path.from(LocalPath.build("/storage/emulated/0/Documents")),
                    SearchTarget.Path.from(LocalPath.build("/storage/emulated/0/Download")),
                ),
                filenameQuery = "*.kt",
                contentQuery = "TODO",
            ),
            design = WorkspaceDesign(),
            onUpdateFilenameQuery = {},
            onUpdateContentQuery = {},
            onRemoveSearchPath = {},
            onTogglePathEnabled = {},
            onPerformSearch = {},
            onCancelSearch = {},
            onToggleFilenameCaseSensitive = {},
            onToggleFilenameWholeWord = {},
            onToggleFilenameRegex = {},
            onToggleContentCaseSensitive = {},
            onToggleContentWholeWord = {},
            onToggleContentRegex = {},
            onToggleContentSearch = {},
            modifier = Modifier.padding(16.dp),
            collapsedFraction = 1f,
        )
    }
}