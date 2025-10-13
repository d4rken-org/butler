package eu.darken.butler.searcher.ui.search

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsFocusedAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.twotone.Clear
import androidx.compose.material.icons.twotone.Close
import androidx.compose.material.icons.twotone.Folder
import androidx.compose.material.icons.twotone.FolderOpen
import androidx.compose.material.icons.twotone.Search
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
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
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.unit.dp
import eu.darken.butler.common.compose.Preview2
import eu.darken.butler.common.compose.PreviewWrapper
import eu.darken.butler.common.files.APath
import eu.darken.butler.common.files.LocalPath
import eu.darken.butler.searcher.R

@Composable
fun SearchInputCard(
    query: TextFieldValue,
    onQueryChange: (TextFieldValue) -> Unit,
    onSearch: () -> Unit,
    path: APath<*>,
    onPathChange: (APath<*>) -> Unit,
    isSearching: Boolean,
    onCancel: (() -> Unit)? = null,
    modifier: Modifier = Modifier
) {
    Card(
        modifier = modifier.fillMaxWidth(),
        elevation = CardDefaults.cardElevation(defaultElevation = 1.dp),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surface
        )
    ) {
        Column(
            modifier = Modifier.padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp)
        ) {
            SearchBar(
                query = query,
                onQueryChange = onQueryChange,
                onSearch = onSearch,
                isSearching = isSearching,
                onCancel = onCancel
            )

            SearchPathBar(
                path = path,
                onPathChange = onPathChange,
                isSearching = isSearching
            )
        }
    }
}

@Composable
fun CustomSearchField(
    value: TextFieldValue,
    onValueChange: (TextFieldValue) -> Unit,
    placeholder: String,
    leadingIcon: @Composable (() -> Unit)? = null,
    trailingIcon: @Composable (() -> Unit)? = null,
    enabled: Boolean = true,
    isError: Boolean = false,
    keyboardOptions: KeyboardOptions = KeyboardOptions.Default,
    keyboardActions: KeyboardActions = KeyboardActions.Default,
    modifier: Modifier = Modifier,
    interactionSource: MutableInteractionSource = remember { MutableInteractionSource() }
) {
    val colors = MaterialTheme.colorScheme
    val focusRequester = remember { FocusRequester() }
    val isFocused by interactionSource.collectIsFocusedAsState()

    Surface(
        modifier = modifier,
        shape = RoundedCornerShape(12.dp),
        color = colors.surfaceVariant.copy(alpha = if (isFocused) 0.9f else 0.7f),
        border = BorderStroke(
            width = if (isFocused) 2.dp else 0.dp,
            color = when {
                isError -> colors.error
                isFocused -> colors.primary
                else -> Color.Transparent
            }
        )
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            leadingIcon?.let {
                Box(modifier = Modifier.padding(end = 8.dp)) {
                    it()
                }
            }

            BasicTextField(
                value = value,
                onValueChange = onValueChange,
                modifier = Modifier
                    .weight(1f)
                    .focusRequester(focusRequester),
                enabled = enabled,
                textStyle = MaterialTheme.typography.bodyMedium.copy(
                    color = colors.onSurface
                ),
                keyboardOptions = keyboardOptions,
                keyboardActions = keyboardActions,
                singleLine = true,
                interactionSource = interactionSource,
                decorationBox = { innerTextField ->
                    Box {
                        if (value.text.isEmpty()) {
                            Text(
                                text = placeholder,
                                style = MaterialTheme.typography.bodyMedium,
                                color = colors.onSurfaceVariant.copy(alpha = 0.6f)
                            )
                        }
                        innerTextField()
                    }
                }
            )

            trailingIcon?.let {
                Box(modifier = Modifier.padding(start = 8.dp)) {
                    it()
                }
            }
        }
    }
}

@Composable
fun SearchBar(
    query: TextFieldValue,
    onQueryChange: (TextFieldValue) -> Unit,
    onSearch: () -> Unit,
    isSearching: Boolean,
    onCancel: (() -> Unit)? = null,
    modifier: Modifier = Modifier
) {
    CustomSearchField(
        value = query,
        onValueChange = onQueryChange,
        placeholder = stringResource(R.string.searcher_placeholder_search),
        leadingIcon = {
            Icon(
                imageVector = Icons.TwoTone.Search,
                contentDescription = "Search",
                tint = MaterialTheme.colorScheme.onSurfaceVariant
            )
        },
        trailingIcon = {
            when {
                isSearching && onCancel != null -> {
                    Icon(
                        imageVector = Icons.TwoTone.Close,
                        contentDescription = stringResource(R.string.general_cancel_action),
                        modifier = Modifier
                            .clickable { onCancel() }
                            .size(24.dp),
                        tint = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }

                query.text.isNotEmpty() -> {
                    Icon(
                        imageVector = Icons.TwoTone.Clear,
                        contentDescription = "Clear",
                        modifier = Modifier
                            .clickable { onQueryChange(TextFieldValue("")) }
                            .size(24.dp),
                        tint = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }

                else -> null
            }
        },
        keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
        keyboardActions = KeyboardActions(onSearch = { onSearch() }),
        modifier = modifier
    )
}

@Composable
fun SearchPathBar(
    path: APath<*>,
    onPathChange: (APath<*>) -> Unit,
    onPerformSearch: () -> Unit = {},
    isSearching: Boolean,
    modifier: Modifier = Modifier
) {
    var pathText by remember { mutableStateOf(TextFieldValue(path.path)) }
    var showPathPicker by remember { mutableStateOf(false) }
    val focusRequester = remember { FocusRequester() }
    val interactionSource = remember { MutableInteractionSource() }
    val isFocused by interactionSource.collectIsFocusedAsState()
    val focusManager = LocalFocusManager.current
    val keyboardController = LocalSoftwareKeyboardController.current

    // Update pathText only when path changes from external source and field is not focused
    LaunchedEffect(path, isFocused) {
        if (!isFocused && pathText.text != path.path) {
            pathText = TextFieldValue(path.path)
        }
    }

    // Validate and update path when user finishes editing (loses focus)
    LaunchedEffect(isFocused) {
        if (!isFocused) {
            try {
                val newPath = LocalPath.build(pathText.text)
                onPathChange(newPath)
            } catch (e: Exception) {
                // Invalid path, revert to current valid path
                pathText = TextFieldValue(path.path)
            }
        }
    }

    CustomSearchField(
        value = pathText,
        onValueChange = { newPath ->
            pathText = newPath
            // Don't validate path on every keystroke - wait for focus loss or Done action
        },
        placeholder = stringResource(R.string.searcher_placeholder_path),
        leadingIcon = {
            Icon(
                imageVector = Icons.TwoTone.Folder,
                contentDescription = "Search Path",
                tint = MaterialTheme.colorScheme.onSurfaceVariant
            )
        },
        trailingIcon = {
            Icon(
                imageVector = Icons.TwoTone.FolderOpen,
                contentDescription = "Browse",
                modifier = Modifier
                    .clickable { showPathPicker = true }
                    .size(24.dp),
                tint = MaterialTheme.colorScheme.onSurfaceVariant
            )
        },
        keyboardOptions = KeyboardOptions(imeAction = ImeAction.Done),
        keyboardActions = KeyboardActions(onDone = {
            try {
                val newPath = LocalPath.build(pathText.text)
                onPathChange(newPath)
                keyboardController?.hide() // Dismiss soft keyboard (no-op for external keyboards)
                onPerformSearch() // Trigger search with new path
            } catch (e: Exception) {
                // Invalid path, revert to current valid path
                pathText = TextFieldValue(path.path)
            }
        }),
        enabled = !isSearching,
        modifier = modifier.focusRequester(focusRequester),
        interactionSource = interactionSource
    )

    if (showPathPicker) {
        PathPickerDialog(
            onPathSelected = { selectedPath ->
                pathText = TextFieldValue(selectedPath.path)
                onPathChange(selectedPath)
                showPathPicker = false
            },
            onDismiss = { showPathPicker = false }
        )
    }
}

@Composable
fun PathPickerDialog(
    onPathSelected: (APath<*>) -> Unit,
    onDismiss: () -> Unit
) {
    val commonPaths = listOf(
        "/storage/emulated/0/Android/data/eu.darken.butler" to "Butler App Data (Default)",
        "/" to "Root",
        "/sdcard" to "Internal Storage",
        "/storage/emulated/0" to "Internal Storage (Alt)",
        "/storage/emulated/0/Download" to "Downloads",
        "/storage/emulated/0/Pictures" to "Pictures",
        "/storage/emulated/0/Documents" to "Documents",
        "/storage/emulated/0/Music" to "Music",
        "/storage/emulated/0/Movies" to "Movies",
        "/system" to "System",
        "/data" to "Data",
        "/cache" to "Cache"
    )

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.searcher_select_path_title)) },
        text = {
            LazyColumn {
                items(commonPaths) { (path, name) ->
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clickable {
                                onPathSelected(LocalPath.build(path))
                            }
                            .padding(12.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Icon(
                            imageVector = Icons.TwoTone.Folder,
                            contentDescription = null,
                            modifier = Modifier.size(20.dp),
                            tint = MaterialTheme.colorScheme.primary
                        )
                        Spacer(modifier = Modifier.width(12.dp))
                        Column {
                            Text(
                                text = name,
                                style = MaterialTheme.typography.bodyMedium
                            )
                            Text(
                                text = path,
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                    }
                }
            }
        },
        confirmButton = {
            TextButton(onClick = onDismiss) {
                Text(stringResource(R.string.general_cancel_action))
            }
        }
    )
}

@Preview2
@Composable
private fun SearchInputCardPreview() {
    PreviewWrapper {
        SearchInputCard(
            query = TextFieldValue("test search"),
            onQueryChange = {},
            onSearch = {},
            path = LocalPath.build("/storage/emulated/0/Android/data/eu.darken.butler"),
            onPathChange = {},
            isSearching = false,
            modifier = Modifier.padding(16.dp)
        )
    }
}