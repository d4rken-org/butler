package eu.darken.butler.explorer.ui.settings.hidden

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.twotone.Visibility
import androidx.compose.material.icons.twotone.VisibilityOff
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
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
        )
    }
}

@Composable
fun ExplorerHiddenPathsScreen(
    modifier: Modifier = Modifier,
    state: ExplorerHiddenPathsViewModel.State,
    onNavigateUp: () -> Unit,
    onRemove: (HiddenEntry) -> Unit,
) {
    Scaffold(
        modifier = modifier,
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.explorer_settings_hidden_paths_title)) },
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
            )
        },
    ) { paddingValues ->
        if (state.entries.isEmpty()) {
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(paddingValues)
                    .padding(24.dp),
                contentAlignment = Alignment.Center,
            ) {
                Text(
                    text = stringResource(R.string.explorer_hidden_paths_empty),
                    style = MaterialTheme.typography.bodyLarge,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    textAlign = TextAlign.Center,
                )
            }
        } else {
            LazyColumn(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(paddingValues),
            ) {
                items(state.entries, key = { it.key }) { entry ->
                    HiddenEntryRow(
                        entry = entry,
                        onRemove = { onRemove(entry) },
                    )
                }
            }
        }
    }
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

@Preview2
@ComposePreviewWrapper(ButlerPreviewWrapper::class)
@Composable
private fun ExplorerHiddenPathsScreenPreview() {
    ExplorerHiddenPathsScreen(
        state = ExplorerHiddenPathsViewModel.State(
            entries = listOf(
                "/storage/emulated/0/Download/invoice-2026.pdf",
                "/storage/emulated/0/DCIM/.thumbnails",
                "/storage/emulated/0/Android/media",
            ).map { LocalPath.build(it) }.map { HiddenEntry(key = it.sortPathKey(), path = it) },
        ),
        onNavigateUp = {},
        onRemove = {},
    )
}

@Preview2
@ComposePreviewWrapper(ButlerPreviewWrapper::class)
@Composable
private fun ExplorerHiddenPathsScreenEmptyPreview() {
    ExplorerHiddenPathsScreen(
        state = ExplorerHiddenPathsViewModel.State(entries = emptyList()),
        onNavigateUp = {},
        onRemove = {},
    )
}
