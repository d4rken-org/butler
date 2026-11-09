package eu.darken.butler.explorer.ui.settings.hidden

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.twotone.Close
import androidx.compose.material.icons.twotone.Search
import androidx.compose.material.icons.twotone.Visibility
import androidx.compose.material.icons.twotone.VisibilityOff
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TextField
import androidx.compose.material3.TextFieldDefaults
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.tooling.preview.PreviewWrapper as ComposePreviewWrapper
import androidx.compose.ui.unit.dp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import eu.darken.butler.common.compose.ButlerPreviewWrapper
import eu.darken.butler.common.compose.Preview2
import eu.darken.butler.common.compose.asPathStyle
import eu.darken.butler.common.error.ErrorEventHandler
import eu.darken.butler.common.files.LocalPath
import eu.darken.butler.common.files.extensions.shownPath
import eu.darken.butler.common.navigation.NavigationEventHandler
import eu.darken.butler.explorer.R
import eu.darken.butler.explorer.core.hidden.HiddenEntry
import eu.darken.butler.explorer.core.sorting.rules.sortPathKey
import eu.darken.butler.workspace.ui.modal.WorkspaceBackHandler

@Composable
fun ExplorerHiddenPathsScreenHost(vm: ExplorerHiddenPathsViewModel = hiltViewModel()) {
    ErrorEventHandler(vm)
    NavigationEventHandler(vm)

    val state by vm.state.collectAsState(initial = null)

    state?.let { vmState ->
        ExplorerHiddenPathsScreen(
            state = vmState,
            onNavigateUp = { vm.navUp() },
            onRemove = { vm.remove(it) },
            onOpenSearch = { vm.openSearch() },
            onCloseSearch = { vm.closeSearch() },
            onQueryChange = { vm.setQuery(it) },
            onUnhideAll = { vm.requestUnhideAll() },
            onConfirmUnhideAll = { vm.confirmUnhideAll() },
            onDismissUnhideAll = { vm.dismissUnhideAll() },
        )
    }
}

@Composable
fun ExplorerHiddenPathsScreen(
    modifier: Modifier = Modifier,
    state: ExplorerHiddenPathsViewModel.State,
    onNavigateUp: () -> Unit,
    onRemove: (HiddenEntry) -> Unit,
    onOpenSearch: () -> Unit,
    onCloseSearch: () -> Unit,
    onQueryChange: (String) -> Unit,
    onUnhideAll: () -> Unit,
    onConfirmUnhideAll: () -> Unit,
    onDismissUnhideAll: () -> Unit,
) {
    WorkspaceBackHandler(enabled = state.isSearching, onBack = onCloseSearch)

    Scaffold(
        modifier = modifier,
        topBar = {
            TopAppBar(
                title = {
                    if (state.isSearching) {
                        HiddenPathsSearchField(
                            query = state.query,
                            onQueryChange = onQueryChange,
                            onClose = onCloseSearch,
                        )
                    } else {
                        Text(stringResource(R.string.explorer_settings_hidden_paths_title))
                    }
                },
                navigationIcon = {
                    IconButton(onClick = onNavigateUp) {
                        Icon(
                            imageVector = Icons.AutoMirrored.Filled.ArrowBack,
                            contentDescription = stringResource(
                                eu.darken.butler.common.R.string.general_back_action
                            ),
                        )
                    }
                },
                actions = {
                    if (!state.isSearching && state.entries.isNotEmpty()) {
                        IconButton(onClick = onOpenSearch) {
                            Icon(
                                imageVector = Icons.TwoTone.Search,
                                contentDescription = stringResource(R.string.explorer_hidden_paths_search_action),
                            )
                        }
                    }
                    if (state.visibleEntries.isNotEmpty()) {
                        IconButton(onClick = onUnhideAll) {
                            Icon(
                                imageVector = Icons.TwoTone.Visibility,
                                contentDescription = stringResource(R.string.explorer_hidden_paths_unhide_all_action),
                            )
                        }
                    }
                },
            )
        },
    ) { paddingValues ->
        when {
            state.entries.isEmpty() -> HiddenPathsMessage(
                modifier = Modifier.padding(paddingValues),
                text = stringResource(R.string.explorer_hidden_paths_empty),
            )

            state.visibleEntries.isEmpty() -> HiddenPathsMessage(
                modifier = Modifier.padding(paddingValues),
                text = stringResource(R.string.explorer_hidden_paths_search_no_matches, state.query),
            )

            else -> LazyColumn(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(paddingValues),
            ) {
                items(state.visibleEntries, key = { it.key }) { entry ->
                    HiddenEntryRow(
                        entry = entry,
                        onRemove = { onRemove(entry) },
                    )
                }
            }
        }
    }

    state.confirmUnhideCount?.let { count ->
        UnhideAllDialog(
            count = count,
            onConfirm = onConfirmUnhideAll,
            onDismiss = onDismissUnhideAll,
        )
    }
}

@Composable
private fun HiddenPathsSearchField(
    modifier: Modifier = Modifier,
    query: String,
    onQueryChange: (String) -> Unit,
    onClose: () -> Unit,
) {
    val focusRequester = remember { FocusRequester() }
    val keyboard = LocalSoftwareKeyboardController.current
    LaunchedEffect(Unit) { focusRequester.requestFocus() }

    TextField(
        modifier = modifier
            .fillMaxWidth()
            .focusRequester(focusRequester),
        value = query,
        onValueChange = onQueryChange,
        singleLine = true,
        placeholder = { Text(stringResource(R.string.explorer_hidden_paths_search_hint)) },
        trailingIcon = {
            IconButton(onClick = onClose) {
                Icon(
                    imageVector = Icons.TwoTone.Close,
                    contentDescription = stringResource(R.string.explorer_hidden_paths_search_close_action),
                )
            }
        },
        keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
        keyboardActions = KeyboardActions(onSearch = { keyboard?.hide() }),
        colors = TextFieldDefaults.colors(
            focusedContainerColor = Color.Transparent,
            unfocusedContainerColor = Color.Transparent,
            focusedIndicatorColor = Color.Transparent,
            unfocusedIndicatorColor = Color.Transparent,
        ),
    )
}

@Composable
private fun HiddenPathsMessage(
    modifier: Modifier = Modifier,
    text: String,
) {
    Box(
        modifier = modifier
            .fillMaxSize()
            .padding(24.dp),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            text = text,
            style = MaterialTheme.typography.bodyLarge,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            textAlign = TextAlign.Center,
        )
    }
}

@Composable
private fun UnhideAllDialog(
    count: Int,
    onConfirm: () -> Unit,
    onDismiss: () -> Unit,
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = {
            Text(text = pluralStringResource(R.plurals.explorer_hidden_paths_unhide_all_confirm_title, count, count))
        },
        text = {
            Text(text = stringResource(R.string.explorer_hidden_paths_unhide_all_confirm_message))
        },
        confirmButton = {
            TextButton(onClick = onConfirm) {
                Text(text = stringResource(R.string.explorer_hidden_paths_unhide_all_confirm_action))
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text(text = stringResource(eu.darken.butler.common.R.string.general_cancel_action))
            }
        },
    )
}

@Composable
private fun HiddenEntryRow(
    modifier: Modifier = Modifier,
    entry: HiddenEntry,
    onRemove: () -> Unit,
) {
    val context = LocalContext.current
    val name = entry.path.userReadableName.get(context)
    ListItem(
        modifier = modifier,
        leadingContent = {
            Icon(
                imageVector = Icons.TwoTone.VisibilityOff,
                contentDescription = null,
            )
        },
        headlineContent = { Text(text = name) },
        supportingContent = {
            Text(
                text = entry.path.shownPath.get(context),
                style = MaterialTheme.typography.bodyMedium.asPathStyle(),
            )
        },
        trailingContent = {
            IconButton(onClick = onRemove) {
                Icon(
                    imageVector = Icons.TwoTone.Visibility,
                    contentDescription = stringResource(R.string.explorer_hidden_paths_remove_action, name),
                )
            }
        },
    )
}

private val previewEntries = listOf(
    "/storage/emulated/0/Download/invoice-2026.pdf",
    "/storage/emulated/0/DCIM/.thumbnails",
    "/storage/emulated/0/Android/media",
).map { LocalPath.build(it) }.map { HiddenEntry(key = it.sortPathKey(), path = it) }

@Composable
private fun PreviewScreen(state: ExplorerHiddenPathsViewModel.State) {
    ExplorerHiddenPathsScreen(
        state = state,
        onNavigateUp = {},
        onRemove = {},
        onOpenSearch = {},
        onCloseSearch = {},
        onQueryChange = {},
        onUnhideAll = {},
        onConfirmUnhideAll = {},
        onDismissUnhideAll = {},
    )
}

@Preview2
@ComposePreviewWrapper(ButlerPreviewWrapper::class)
@Composable
private fun ExplorerHiddenPathsScreenPreview() {
    PreviewScreen(state = ExplorerHiddenPathsViewModel.State(entries = previewEntries))
}

@Preview2
@ComposePreviewWrapper(ButlerPreviewWrapper::class)
@Composable
private fun ExplorerHiddenPathsScreenEmptyPreview() {
    PreviewScreen(state = ExplorerHiddenPathsViewModel.State(entries = emptyList()))
}

@Preview2
@ComposePreviewWrapper(ButlerPreviewWrapper::class)
@Composable
private fun ExplorerHiddenPathsScreenSearchPreview() {
    PreviewScreen(
        state = ExplorerHiddenPathsViewModel.State(
            entries = previewEntries,
            visibleEntries = previewEntries.filter { it.path.path.contains("dcim", ignoreCase = true) },
            query = "dcim",
            isSearching = true,
        ),
    )
}

@Preview2
@ComposePreviewWrapper(ButlerPreviewWrapper::class)
@Composable
private fun ExplorerHiddenPathsScreenNoMatchesPreview() {
    PreviewScreen(
        state = ExplorerHiddenPathsViewModel.State(
            entries = previewEntries,
            visibleEntries = emptyList(),
            query = "vacation",
            isSearching = true,
        ),
    )
}

@Preview2
@ComposePreviewWrapper(ButlerPreviewWrapper::class)
@Composable
private fun ExplorerHiddenPathsScreenConfirmUnhideAllPreview() {
    PreviewScreen(
        state = ExplorerHiddenPathsViewModel.State(
            entries = previewEntries,
            confirmUnhideCount = previewEntries.size,
        ),
    )
}
