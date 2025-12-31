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
import eu.darken.butler.searcher.ui.search.util.SearcherPageAction
import eu.darken.butler.workspace.core.Workspace
import eu.darken.butler.workspace.ui.manager.WorkspaceActionHandler
import eu.darken.butler.workspace.ui.manager.WorkspaceButton
import eu.darken.butler.workspace.ui.manager.WorkspaceButtonViewModel
import eu.darken.butler.workspace.ui.manager.WorkspaceDesign

@Composable
fun SearchToolbarCard(
    modifier: Modifier = Modifier,
    workspaceId: Workspace.Id,
    state: SearcherWorkspaceViewModel.State.Ready?,
    design: WorkspaceDesign,
    collapsedFraction: Float = 0f,
    onAction: (SearcherPageAction) -> Unit,
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
                        val hasFilename = state?.filenameQuery?.isNotBlank() == true
                        val hasContent = state?.contentQuery?.isNotBlank() == true
                        when {
                            hasFilename && hasContent -> {
                                append(state?.filenameQuery.orEmpty())
                                append(" | ")
                                append(state?.contentQuery.orEmpty())
                            }

                            hasFilename -> append(state?.filenameQuery.orEmpty())
                            hasContent -> append(state?.contentQuery.orEmpty())
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
                        Spacer(
                            modifier = Modifier.padding(vertical = 4.dp),
                        )

                        FilterChipBar(
                            filter = state?.currentFilter ?: eu.darken.butler.searcher.core.SearchFilter(),
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
                            paths = state?.searchTargets.orEmpty(),
                            onPathRemove = { onAction(SearcherPageAction.Targets.Remove(it)) },
                            onPathToggle = { onAction(SearcherPageAction.Targets.ToggleEnabled(it)) },
                            onAddPathClick = { onAction(SearcherPageAction.Targets.OpenPicker) },
                            isSearching = state?.isSearching ?: false,
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
            modifier = Modifier.padding(16.dp),
            workspaceId = Workspace.Id(),
            state = SearcherWorkspaceViewModel.State.Ready(
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
            onAction = {},
        )
    }
}

@Preview2
@Composable
private fun SearchToolbarCardCollapsedPreview() {
    PreviewWrapper {
        SearchToolbarCard(
            modifier = Modifier.padding(16.dp),
            workspaceId = Workspace.Id(),
            state = SearcherWorkspaceViewModel.State.Ready(
                searchTargets = listOf(
                    SearchTarget.Path.from(LocalPath.build("/storage/emulated/0/Documents")),
                    SearchTarget.Path.from(LocalPath.build("/storage/emulated/0/Download")),
                ),
                filenameQuery = "*.kt",
                contentQuery = "TODO",
            ),
            design = WorkspaceDesign(),
            collapsedFraction = 1f,
            onAction = {},
        )
    }
}