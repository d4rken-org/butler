package eu.darken.butler.editor.ui.editor.elements

import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.twotone.Close
import androidx.compose.material.icons.twotone.ExpandLess
import androidx.compose.material.icons.twotone.ExpandMore
import androidx.compose.material.icons.twotone.KeyboardArrowDown
import androidx.compose.material.icons.twotone.KeyboardArrowUp
import androidx.compose.material.icons.twotone.MoreVert
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Checkbox
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.tooling.preview.PreviewWrapper as ComposePreviewWrapper
import androidx.compose.ui.unit.dp
import eu.darken.butler.common.compose.ButlerPreviewWrapper
import eu.darken.butler.common.compose.Preview2
import eu.darken.butler.common.compose.PreviewWrapper
import eu.darken.butler.editor.R
import eu.darken.butler.editor.core.engine.SearchResult
import eu.darken.butler.editor.ui.editor.EditorSearchController
import eu.darken.butler.workspace.ui.LocalWorkspaceFocused
import eu.darken.butler.workspace.ui.modal.DismissWhenPaneUnfocused

/**
 * [searchTruncated] marks a result list capped by the engine: the counter shows "N of M+" and
 * next/previous navigate WITHIN the retained matches only - matches beyond the cap are reached
 * by narrowing the query. Replace buttons stay enabled; Replace All over the cap surfaces an
 * explanatory error dialog, which is more discoverable than a silently disabled button.
 */
@Composable
fun EditorSearchBar(
    modifier: Modifier = Modifier,
    searchQuery: TextFieldValue,
    searchResults: List<SearchResult>,
    currentIndex: Int,
    searchTruncated: Boolean = false,
    caseSensitive: Boolean,
    regexEnabled: Boolean,
    wholeWord: Boolean,
    replaceQuery: TextFieldValue = TextFieldValue(""),
    showReplaceRow: Boolean = false,
    replaceAllowed: Boolean = true,
    replaceNotice: EditorSearchController.ReplaceNotice? = null,
    onSearchQueryChange: (TextFieldValue) -> Unit,
    onCaseSensitiveToggle: () -> Unit,
    onRegexToggle: () -> Unit,
    onWholeWordToggle: () -> Unit,
    onPrevious: () -> Unit,
    onNext: () -> Unit,
    onClose: () -> Unit,
    onToggleReplaceRow: () -> Unit = {},
    onReplaceQueryChange: (TextFieldValue) -> Unit = {},
    onReplaceCurrent: () -> Unit = {},
    onReplaceAll: () -> Unit = {},
) {
    val hasResults = searchResults.isNotEmpty()
    val canNavigate = hasResults
    val hasActiveOptions = caseSensitive || regexEnabled || wholeWord
    var showOptionsMenu by remember { mutableStateOf(false) }
    val focusRequester = remember { FocusRequester() }
    val isWorkspaceFocused = LocalWorkspaceFocused.current

    // Auto-focus the search input when the search bar appears and workspace is focused
    LaunchedEffect(isWorkspaceFocused) {
        if (isWorkspaceFocused) focusRequester.requestFocus()
    }

    Card(
        modifier = modifier.fillMaxWidth(),
        elevation = CardDefaults.cardElevation(defaultElevation = 6.dp),
        shape = RoundedCornerShape(12.dp),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceContainerHigh
        )
    ) {
        Column {
            // Result counter (above input row)
            if (hasResults) {
                Text(
                    text = if (searchTruncated) {
                        // Index clamped: async state skew must never render "10001 of 10000+"
                        stringResource(
                            R.string.editor_search_results_x_of_capped,
                            (currentIndex + 1).coerceAtMost(searchResults.size),
                            searchResults.size,
                        )
                    } else {
                        pluralStringResource(
                            R.plurals.editor_search_results_x_of_y,
                            searchResults.size,
                            currentIndex + 1,
                            searchResults.size
                        )
                    },
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(start = 12.dp, top = 8.dp)
                )
            }

            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 8.dp, vertical = 8.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(4.dp)
            ) {
                // Search text field with overflow menu
                Row(
                    modifier = Modifier
                        .weight(1f)
                        .border(
                            width = 1.dp,
                            color = MaterialTheme.colorScheme.outlineVariant,
                            shape = RoundedCornerShape(8.dp)
                        )
                        .padding(start = 12.dp, end = 4.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    BasicTextField(
                        value = searchQuery,
                        onValueChange = onSearchQueryChange,
                        modifier = Modifier
                            .weight(1f)
                            .focusRequester(focusRequester),
                        textStyle = MaterialTheme.typography.bodyMedium.copy(
                            color = MaterialTheme.colorScheme.onSurface
                        ),
                        cursorBrush = SolidColor(MaterialTheme.colorScheme.primary),
                        singleLine = true,
                        keyboardOptions = KeyboardOptions(
                            imeAction = ImeAction.Search
                        ),
                        keyboardActions = KeyboardActions(
                            onSearch = {
                                if (hasResults) {
                                    onNext()
                                }
                            }
                        ),
                        decorationBox = { innerTextField ->
                            Box(modifier = Modifier.padding(vertical = 8.dp)) {
                                if (searchQuery.text.isEmpty()) {
                                    Text(
                                        text = stringResource(R.string.editor_search_placeholder),
                                        style = MaterialTheme.typography.bodyMedium,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant
                                    )
                                }
                                innerTextField()
                            }
                        }
                    )

                    // Options menu button
                    Box {
                        Icon(
                            imageVector = Icons.TwoTone.MoreVert,
                            contentDescription = stringResource(R.string.editor_search_options_label),
                            modifier = Modifier
                                .clickable { showOptionsMenu = true }
                                .padding(8.dp)
                                .size(20.dp),
                            tint = if (hasActiveOptions) {
                                MaterialTheme.colorScheme.primary
                            } else {
                                MaterialTheme.colorScheme.onSurfaceVariant
                            }
                        )

                        DismissWhenPaneUnfocused(expanded = showOptionsMenu) { showOptionsMenu = false }
                        DropdownMenu(
                            expanded = showOptionsMenu,
                            onDismissRequest = { showOptionsMenu = false }
                        ) {
                            DropdownMenuItem(
                                text = { Text(stringResource(R.string.editor_search_case_sensitive_label)) },
                                onClick = {
                                    onCaseSensitiveToggle()
                                },
                                leadingIcon = {
                                    Checkbox(
                                        checked = caseSensitive,
                                        onCheckedChange = null
                                    )
                                }
                            )
                            DropdownMenuItem(
                                text = { Text(stringResource(R.string.editor_search_whole_word_label)) },
                                onClick = {
                                    onWholeWordToggle()
                                },
                                leadingIcon = {
                                    Checkbox(
                                        checked = wholeWord,
                                        onCheckedChange = null
                                    )
                                }
                            )
                            DropdownMenuItem(
                                text = { Text(stringResource(R.string.editor_search_regex_label)) },
                                onClick = {
                                    onRegexToggle()
                                },
                                leadingIcon = {
                                    Checkbox(
                                        checked = regexEnabled,
                                        onCheckedChange = null
                                    )
                                }
                            )
                        }
                    }
                }

                // Previous result button
                IconButton(
                    onClick = onPrevious,
                    enabled = canNavigate,
                    modifier = Modifier.size(40.dp)
                ) {
                    Icon(
                        imageVector = Icons.TwoTone.KeyboardArrowUp,
                        contentDescription = stringResource(R.string.editor_search_previous),
                        tint = if (canNavigate) {
                            MaterialTheme.colorScheme.onSurface
                        } else {
                            MaterialTheme.colorScheme.onSurface.copy(alpha = 0.38f)
                        }
                    )
                }

                // Next result button
                IconButton(
                    onClick = onNext,
                    enabled = canNavigate,
                    modifier = Modifier.size(40.dp)
                ) {
                    Icon(
                        imageVector = Icons.TwoTone.KeyboardArrowDown,
                        contentDescription = stringResource(R.string.editor_search_next),
                        tint = if (canNavigate) {
                            MaterialTheme.colorScheme.onSurface
                        } else {
                            MaterialTheme.colorScheme.onSurface.copy(alpha = 0.38f)
                        }
                    )
                }

                // Close button
                IconButton(
                    onClick = onClose,
                    modifier = Modifier.size(40.dp)
                ) {
                    Icon(
                        imageVector = Icons.TwoTone.Close,
                        contentDescription = stringResource(eu.darken.butler.common.R.string.general_close_action),
                    )
                }
            }

            if (replaceAllowed) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 12.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Icon(
                        imageVector = if (showReplaceRow) Icons.TwoTone.ExpandLess else Icons.TwoTone.ExpandMore,
                        contentDescription = stringResource(R.string.editor_search_replace_toggle),
                        modifier = Modifier
                            .clickable { onToggleReplaceRow() }
                            .padding(4.dp)
                            .size(20.dp),
                        tint = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    Text(
                        text = stringResource(R.string.editor_search_replace_label),
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier
                            .clickable { onToggleReplaceRow() }
                            .padding(vertical = 4.dp),
                    )
                    replaceNotice?.let { notice ->
                        Spacer(Modifier.weight(1f))
                        Text(
                            text = if (notice.undoable) {
                                pluralStringResource(
                                    R.plurals.editor_search_replaced_count,
                                    notice.count,
                                    notice.count,
                                )
                            } else {
                                stringResource(R.string.editor_search_replaced_not_undoable, notice.count)
                            },
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.primary,
                        )
                    }
                }
            }

            if (replaceAllowed && showReplaceRow) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(start = 8.dp, end = 8.dp, bottom = 8.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(4.dp),
                ) {
                    Row(
                        modifier = Modifier
                            .weight(1f)
                            .border(
                                width = 1.dp,
                                color = MaterialTheme.colorScheme.outlineVariant,
                                shape = RoundedCornerShape(8.dp),
                            )
                            .padding(horizontal = 12.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        BasicTextField(
                            value = replaceQuery,
                            onValueChange = onReplaceQueryChange,
                            modifier = Modifier.weight(1f),
                            textStyle = MaterialTheme.typography.bodyMedium.copy(
                                color = MaterialTheme.colorScheme.onSurface,
                            ),
                            cursorBrush = SolidColor(MaterialTheme.colorScheme.primary),
                            singleLine = true,
                            decorationBox = { innerTextField ->
                                Box(modifier = Modifier.padding(vertical = 8.dp)) {
                                    if (replaceQuery.text.isEmpty()) {
                                        Text(
                                            text = stringResource(R.string.editor_search_replace_placeholder),
                                            style = MaterialTheme.typography.bodyMedium,
                                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                                        )
                                    }
                                    innerTextField()
                                }
                            },
                        )
                    }

                    TextButton(
                        onClick = onReplaceCurrent,
                        enabled = hasResults,
                    ) {
                        Text(stringResource(R.string.editor_search_replace_one_action))
                    }
                    TextButton(
                        onClick = onReplaceAll,
                        enabled = hasResults,
                    ) {
                        Text(stringResource(R.string.editor_search_replace_all_action))
                    }
                }
            }
        }
    }
}

@Preview2
@ComposePreviewWrapper(ButlerPreviewWrapper::class)
@Composable
private fun EditorSearchBarEmptyPreview() {
    EditorSearchBar(
        searchQuery = TextFieldValue(""),
        searchResults = emptyList(),
        currentIndex = 0,
        caseSensitive = false,
        regexEnabled = false,
        wholeWord = false,
        onSearchQueryChange = {},
        onCaseSensitiveToggle = {},
        onRegexToggle = {},
        onWholeWordToggle = {},
        onPrevious = {},
        onNext = {},
        onClose = {},
    )
}

@Preview2
@ComposePreviewWrapper(ButlerPreviewWrapper::class)
@Composable
private fun EditorSearchBarWithQueryPreview() {
    EditorSearchBar(
        searchQuery = TextFieldValue("test"),
        searchResults = emptyList(),
        currentIndex = 0,
        caseSensitive = false,
        regexEnabled = false,
        wholeWord = false,
        onSearchQueryChange = {},
        onCaseSensitiveToggle = {},
        onRegexToggle = {},
        onWholeWordToggle = {},
        onPrevious = {},
        onNext = {},
        onClose = {},
    )
}

@Preview2
@ComposePreviewWrapper(ButlerPreviewWrapper::class)
@Composable
private fun EditorSearchBarWithResultsPreview() {
    EditorSearchBar(
        searchQuery = TextFieldValue("test"),
        searchResults = List(10) {
            SearchResult(
                position = eu.darken.butler.editor.core.engine.TextPosition.ZERO,
                matchText = "test",
            )
        },
        currentIndex = 2,
        caseSensitive = false,
        regexEnabled = false,
        wholeWord = false,
        onSearchQueryChange = {},
        onCaseSensitiveToggle = {},
        onRegexToggle = {},
        onWholeWordToggle = {},
        onPrevious = {},
        onNext = {},
        onClose = {},
    )
}

@Preview2
@ComposePreviewWrapper(ButlerPreviewWrapper::class)
@Composable
private fun EditorSearchBarTruncatedPreview() {
    EditorSearchBar(
        searchQuery = TextFieldValue("e"),
        searchResults = List(100) {
            SearchResult(
                position = eu.darken.butler.editor.core.engine.TextPosition.ZERO,
                matchText = "e",
            )
        },
        currentIndex = 4,
        searchTruncated = true,
        caseSensitive = false,
        regexEnabled = false,
        wholeWord = false,
        onSearchQueryChange = {},
        onCaseSensitiveToggle = {},
        onRegexToggle = {},
        onWholeWordToggle = {},
        onPrevious = {},
        onNext = {},
        onClose = {},
    )
}

@Preview2
@ComposePreviewWrapper(ButlerPreviewWrapper::class)
@Composable
private fun EditorSearchBarWithOptionsPreview() {
    EditorSearchBar(
        searchQuery = TextFieldValue("Test"),
        searchResults = List(5) {
            SearchResult(
                position = eu.darken.butler.editor.core.engine.TextPosition.ZERO,
                matchText = "Test",
            )
        },
        currentIndex = 0,
        caseSensitive = true,
        regexEnabled = false,
        wholeWord = true,
        onSearchQueryChange = {},
        onCaseSensitiveToggle = {},
        onRegexToggle = {},
        onWholeWordToggle = {},
        onPrevious = {},
        onNext = {},
        onClose = {},
    )
}
