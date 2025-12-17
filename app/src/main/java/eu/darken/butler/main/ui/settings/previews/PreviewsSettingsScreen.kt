package eu.darken.butler.main.ui.settings.previews

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.twotone.DataUsage
import androidx.compose.material.icons.twotone.DeleteSweep
import androidx.compose.material.icons.twotone.Memory
import androidx.compose.material.icons.twotone.Storage
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.hilt.navigation.compose.hiltViewModel
import eu.darken.butler.R
import eu.darken.butler.common.compose.Preview2
import eu.darken.butler.common.compose.PreviewWrapper
import eu.darken.butler.common.error.ErrorEventHandler
import eu.darken.butler.common.formatFileSize
import eu.darken.butler.common.navigation.NavigationEventHandler
import eu.darken.butler.common.settings.SettingsBaseItem
import eu.darken.butler.common.settings.SettingsDivider
import eu.darken.butler.common.settings.SettingsPreferenceItem
import eu.darken.butler.common.ui.waitForState
import kotlinx.coroutines.launch

@Composable
fun PreviewsSettingsScreenHost(vm: PreviewsSettingsViewModel = hiltViewModel()) {
    ErrorEventHandler(vm)
    NavigationEventHandler(vm)

    val state by waitForState(vm.state)

    state?.let { state ->
        PreviewsSettingsScreen(
            state = state,
            onNavigateUp = { vm.navUp() },
            onClearDiskCache = { vm.clearPreviewDiskCache() },
            onClearMemoryCache = { vm.clearPreviewMemoryCache() },
            onClearAllCaches = { vm.clearAllPreviewCaches() },
            onRefreshStats = { vm.refreshCacheStats() },
        )
    }
}

@Composable
fun PreviewsSettingsScreen(
    state: PreviewsSettingsViewModel.State,
    onNavigateUp: () -> Unit,
    onClearDiskCache: () -> Unit,
    onClearMemoryCache: () -> Unit,
    onClearAllCaches: () -> Unit,
    onRefreshStats: () -> Unit,
) {
    val scope = rememberCoroutineScope()
    val snackbarHostState = remember { SnackbarHostState() }
    var showClearDiskDialog by remember { mutableStateOf(false) }
    var showClearMemoryDialog by remember { mutableStateOf(false) }
    var showClearAllDialog by remember { mutableStateOf(false) }

    val diskSizeText = formatFileSize(state.previewDiskCacheSize, shortFormat = false)
    val memorySizeText = formatFileSize(state.previewMemoryCacheSize)

    val cacheCleared = stringResource(R.string.storage_cache_cleared_message)

    Scaffold(
        snackbarHost = { SnackbarHost(snackbarHostState) },
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.previews_settings_title)) },
                navigationIcon = {
                    IconButton(onClick = onNavigateUp) {
                        Icon(
                            imageVector = Icons.AutoMirrored.Filled.ArrowBack,
                            contentDescription = stringResource(eu.darken.butler.common.R.string.general_back_action)
                        )
                    }
                }
            )
        }
    ) { paddingValues ->
        LazyColumn(
            modifier = Modifier
                .fillMaxSize()
                .padding(paddingValues),
            verticalArrangement = Arrangement.Top
        ) {
            item {
                SettingsPreferenceItem(
                    icon = Icons.TwoTone.Storage,
                    title = stringResource(R.string.storage_previews_disk_cache_title),
                    subtitle = diskSizeText,
                    onClick = { showClearDiskDialog = true }
                )
                SettingsDivider()
            }

            item {
                SettingsPreferenceItem(
                    icon = Icons.TwoTone.Memory,
                    title = stringResource(R.string.storage_previews_memory_cache_title),
                    subtitle = memorySizeText,
                    onClick = { showClearMemoryDialog = true }
                )
                SettingsDivider()
            }

            item {
                SettingsBaseItem(
                    icon = Icons.TwoTone.DeleteSweep,
                    title = stringResource(R.string.storage_clear_all_previews_action),
                    subtitle = stringResource(R.string.storage_clear_all_subtitle),
                    onClick = { showClearAllDialog = true }
                )
            }
        }
    }

    // Clear Disk Cache Confirmation Dialog
    if (showClearDiskDialog) {
        AlertDialog(
            onDismissRequest = { showClearDiskDialog = false },
            icon = { Icon(Icons.TwoTone.Storage, contentDescription = null) },
            title = { Text(stringResource(R.string.storage_clear_disk_confirm_title)) },
            text = { Text(stringResource(R.string.storage_clear_disk_confirm_message)) },
            confirmButton = {
                TextButton(onClick = {
                    showClearDiskDialog = false
                    onClearDiskCache()
                    scope.launch {
                        snackbarHostState.showSnackbar(message = cacheCleared)
                    }
                }) {
                    Text(stringResource(eu.darken.butler.common.R.string.general_delete_action))
                }
            },
            dismissButton = {
                TextButton(onClick = { showClearDiskDialog = false }) {
                    Text(stringResource(eu.darken.butler.common.R.string.general_cancel_action))
                }
            }
        )
    }

    // Clear Memory Cache Confirmation Dialog
    if (showClearMemoryDialog) {
        AlertDialog(
            onDismissRequest = { showClearMemoryDialog = false },
            icon = { Icon(Icons.TwoTone.Memory, contentDescription = null) },
            title = { Text(stringResource(R.string.storage_clear_memory_confirm_title)) },
            text = { Text(stringResource(R.string.storage_clear_memory_confirm_message)) },
            confirmButton = {
                TextButton(onClick = {
                    showClearMemoryDialog = false
                    onClearMemoryCache()
                    scope.launch {
                        snackbarHostState.showSnackbar(message = cacheCleared)
                    }
                }) {
                    Text(stringResource(eu.darken.butler.common.R.string.general_delete_action))
                }
            },
            dismissButton = {
                TextButton(onClick = { showClearMemoryDialog = false }) {
                    Text(stringResource(eu.darken.butler.common.R.string.general_cancel_action))
                }
            }
        )
    }

    // Clear All Caches Confirmation Dialog
    if (showClearAllDialog) {
        AlertDialog(
            onDismissRequest = { showClearAllDialog = false },
            icon = { Icon(Icons.TwoTone.DeleteSweep, contentDescription = null) },
            title = { Text(stringResource(R.string.storage_clear_all_confirm_title)) },
            text = { Text(stringResource(R.string.storage_clear_all_confirm_message)) },
            confirmButton = {
                TextButton(
                    onClick = {
                        showClearAllDialog = false
                        onClearAllCaches()
                        scope.launch {
                            snackbarHostState.showSnackbar(message = cacheCleared)
                        }
                    }
                ) {
                    Text(
                        text = stringResource(eu.darken.butler.common.R.string.general_delete_action),
                        color = MaterialTheme.colorScheme.error
                    )
                }
            },
            dismissButton = {
                TextButton(onClick = { showClearAllDialog = false }) {
                    Text(stringResource(eu.darken.butler.common.R.string.general_cancel_action))
                }
            }
        )
    }
}

@Composable
private fun CacheSizeSelectionDialog(
    currentSize: Long,
    onSizeSelected: (Long) -> Unit,
    onDismiss: () -> Unit,
) {
    val sizes = remember {
        listOf(
            50L * 1024 * 1024 to R.string.storage_size_50mb,
            250L * 1024 * 1024 to R.string.storage_size_250mb,
            512L * 1024 * 1024 to R.string.storage_size_512mb,
            1024L * 1024 * 1024 to R.string.storage_size_1gb,
        )
    }

    AlertDialog(
        onDismissRequest = onDismiss,
        icon = { Icon(Icons.TwoTone.DataUsage, contentDescription = null) },
        title = { Text(stringResource(R.string.storage_previews_max_size_title)) },
        text = {
            LazyColumn {
                items(sizes.size) { index ->
                    val (sizeBytes, labelRes) = sizes[index]
                    val isCurrent = sizeBytes == currentSize
                    SettingsBaseItem(
                        title = stringResource(labelRes),
                        subtitle = if (isCurrent) "Current" else null,
                        onClick = { onSizeSelected(sizeBytes) }
                    )
                    if (index < sizes.size - 1) {
                        SettingsDivider()
                    }
                }
            }
        },
        confirmButton = {},
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text(stringResource(eu.darken.butler.common.R.string.general_cancel_action))
            }
        }
    )
}

// Helper extension to get string in composable scope
@Composable
private fun getString(resId: Int): String = stringResource(resId)

@Preview2
@Composable
private fun PreviewsSettingsScreenPreview() {
    PreviewWrapper {
        PreviewsSettingsScreen(
            state = PreviewsSettingsViewModel.State(
                previewDiskCacheSize = 125_300_100,
                previewMemoryCacheSize = 45_200_000,
            ),
            onNavigateUp = {},
            onClearDiskCache = {},
            onClearMemoryCache = {},
            onClearAllCaches = {},
            onRefreshStats = {},
        )
    }
}
