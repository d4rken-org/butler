package eu.darken.butler.searcher.ui.search.input

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsFocusedAsState
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.twotone.Clear
import androidx.compose.material.icons.twotone.Close
import androidx.compose.material.icons.twotone.Search
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
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
import eu.darken.butler.workspace.ui.LocalWorkspaceFocused
import eu.darken.butler.workspace.ui.LocalWorkspaceFocusRequest
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.unit.dp
import eu.darken.butler.common.compose.Preview2
import eu.darken.butler.common.compose.PreviewWrapper
import eu.darken.butler.searcher.R

@Composable
fun SearchBar(
    modifier: Modifier = Modifier,
    query: TextFieldValue,
    onQueryChange: (TextFieldValue) -> Unit,
    onSearch: () -> Unit,
    isSearching: Boolean,
    onCancel: (() -> Unit)? = null,
) {
    val keyboardController = LocalSoftwareKeyboardController.current
    val isWorkspaceFocused = LocalWorkspaceFocused.current
    val requestWorkspaceFocus = LocalWorkspaceFocusRequest.current
    val colors = MaterialTheme.colorScheme
    val focusRequester = remember { FocusRequester() }
    val interactionSource = remember { MutableInteractionSource() }
    val isFocused by interactionSource.collectIsFocusedAsState()
    var hasInitialFocused by remember { mutableStateOf(false) }

    // Request workspace focus when text field gains focus (user tapped it)
    LaunchedEffect(isFocused) {
        if (isFocused) {
            requestWorkspaceFocus?.invoke()
        }
    }

    // Only request focus when workspace is focused, release when it loses focus
    // Use freeFocus() instead of clearFocus() to only release this component's focus,
    // not clear focus globally (which would break focus transfer to other workspaces)
    LaunchedEffect(isWorkspaceFocused) {
        if (isWorkspaceFocused && !hasInitialFocused) {
            focusRequester.requestFocus()
            hasInitialFocused = true
        } else if (!isWorkspaceFocused) {
            try { focusRequester.freeFocus() } catch (_: Exception) {}
        }
    }

    Surface(
        modifier = modifier,
        shape = RoundedCornerShape(12.dp),
        color = colors.surfaceVariant.copy(alpha = if (isFocused) 0.9f else 0.7f),
        border = BorderStroke(
            width = if (isFocused) 2.dp else 0.dp,
            color = if (isFocused) colors.primary else Color.Transparent
        )
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            // Leading icon - search icon
            Box(modifier = Modifier.padding(end = 8.dp)) {
                Icon(
                    imageVector = Icons.TwoTone.Search,
                    contentDescription = "Search",
                    tint = colors.onSurfaceVariant
                )
            }

            // Text field
            BasicTextField(
                value = query,
                onValueChange = onQueryChange,
                modifier = Modifier
                    .weight(1f)
                    .focusRequester(focusRequester),
                enabled = true,
                textStyle = MaterialTheme.typography.bodyMedium.copy(
                    color = colors.onSurface
                ),
                keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
                keyboardActions = KeyboardActions(onSearch = {
                    keyboardController?.hide()
                    onSearch()
                }),
                singleLine = true,
                interactionSource = interactionSource,
                decorationBox = { innerTextField ->
                    Box {
                        if (query.text.isEmpty()) {
                            Text(
                                text = stringResource(R.string.searcher_placeholder_search),
                                style = MaterialTheme.typography.bodyMedium,
                                color = colors.onSurfaceVariant.copy(alpha = 0.6f)
                            )
                        }
                        innerTextField()
                    }
                }
            )

            // Trailing icon - cancel/clear/nothing
            when {
                isSearching && onCancel != null -> {
                    Box(modifier = Modifier.padding(start = 8.dp)) {
                        Icon(
                            imageVector = Icons.TwoTone.Close,
                            contentDescription = stringResource(eu.darken.butler.common.R.string.general_cancel_action),
                            modifier = Modifier
                                .clickable { onCancel() }
                                .size(24.dp),
                            tint = colors.onSurfaceVariant
                        )
                    }
                }

                query.text.isNotEmpty() -> {
                    Box(modifier = Modifier.padding(start = 8.dp)) {
                        Icon(
                            imageVector = Icons.TwoTone.Clear,
                            contentDescription = "Clear",
                            modifier = Modifier
                                .clickable { onQueryChange(TextFieldValue("")) }
                                .size(24.dp),
                            tint = colors.onSurfaceVariant
                        )
                    }
                }
            }
        }
    }
}

@Preview2
@Composable
private fun SearchBarPreview() {
    PreviewWrapper {
        Column {
            SearchBar(
                query = TextFieldValue("example query"),
                onQueryChange = {},
                onSearch = {},
                isSearching = false,
                modifier = Modifier.padding(16.dp)
            )
        }
    }
}

@Preview2
@Composable
private fun SearchBarSearchingPreview() {
    PreviewWrapper {
        SearchBar(
            query = TextFieldValue("searching…"),
            onQueryChange = {},
            onSearch = {},
            isSearching = true,
            onCancel = {},
            modifier = Modifier.padding(16.dp)
        )
    }
}

@Preview2
@Composable
private fun SearchBarEmptyPreview() {
    PreviewWrapper {
        SearchBar(
            query = TextFieldValue(""),
            onQueryChange = {},
            onSearch = {},
            isSearching = false,
            modifier = Modifier.padding(16.dp)
        )
    }
}
