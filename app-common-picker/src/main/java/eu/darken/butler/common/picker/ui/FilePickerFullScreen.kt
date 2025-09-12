package eu.darken.butler.common.picker.ui

import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.Visibility
import androidx.compose.material.icons.filled.VisibilityOff
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.hilt.navigation.compose.hiltViewModel
import eu.darken.butler.common.picker.core.FilePickerConfig
import eu.darken.butler.common.picker.core.FilePickerResult
import eu.darken.butler.common.picker.core.FilePickerState
import eu.darken.butler.common.picker.core.FilePickerViewModel

@Composable
fun FilePickerFullScreen(
    config: FilePickerConfig,
    resultKey: String = "file_picker_result",
    onResult: (FilePickerResult) -> Unit,
    onBack: () -> Unit,
    viewModel: FilePickerViewModel = hiltViewModel(
        creationCallback = { factory: FilePickerViewModel.Factory ->
            factory.create(config, resultKey)
        }
    ),
) {
    val state by viewModel.state.collectAsState(initial = FilePickerState())
    var showMenu by remember { mutableStateOf(false) }
    var showCreateFolderDialog by remember { mutableStateOf(false) }
    
    Scaffold(
        topBar = {
            FilePickerTopBar(
                title = config.title ?: "Select Files",
                subtitle = config.subtitle,
                showHiddenFiles = state.showHiddenFiles,
                onNavigateUp = {
                    // Try to navigate up in the file hierarchy first
                    if (state.currentPath != null) {
                        viewModel.navigateUp()
                    } else {
                        onBack()
                    }
                },
                onToggleHiddenFiles = viewModel::toggleHiddenFiles,
                showMenu = showMenu,
                onMenuToggle = { showMenu = it }
            )
        }
    ) { paddingValues ->
        FilePickerCore(
            config = config,
            state = state,
            modifier = Modifier.padding(paddingValues),
            onNavigate = viewModel::navigateTo,
            onItemClick = viewModel::onItemClick,
            onItemLongClick = viewModel::onItemLongClick,
            onConfirm = {
                val selected = state.selectedItems.toList()
                if (selected.isNotEmpty()) {
                    onResult(FilePickerResult.Selected(selected))
                    onBack()
                }
            },
            onCancel = {
                onResult(FilePickerResult.Cancelled)
                onBack()
            },
            onCreateFolder = if (config.allowCreateFolder) {
                { showCreateFolderDialog = true }
            } else null
        )
    }
    
    // Create folder dialog
    if (showCreateFolderDialog) {
        CreateFolderDialog(
            onConfirm = { name ->
                viewModel.createFolder(name)
                showCreateFolderDialog = false
            },
            onDismiss = { showCreateFolderDialog = false }
        )
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun FilePickerTopBar(
    title: String,
    subtitle: String?,
    showHiddenFiles: Boolean,
    onNavigateUp: () -> Unit,
    onToggleHiddenFiles: () -> Unit,
    showMenu: Boolean,
    onMenuToggle: (Boolean) -> Unit,
) {
    TopAppBar(
        title = {
            Text(text = title)
        },
        navigationIcon = {
            IconButton(onClick = onNavigateUp) {
                Icon(
                    imageVector = Icons.AutoMirrored.Default.ArrowBack,
                    contentDescription = "Navigate up"
                )
            }
        },
        actions = {
            IconButton(onClick = { onMenuToggle(true) }) {
                Icon(
                    imageVector = Icons.Default.MoreVert,
                    contentDescription = "More options"
                )
            }
            
            DropdownMenu(
                expanded = showMenu,
                onDismissRequest = { onMenuToggle(false) }
            ) {
                DropdownMenuItem(
                    text = {
                        Text(
                            if (showHiddenFiles) "Hide hidden files" 
                            else "Show hidden files"
                        )
                    },
                    leadingIcon = {
                        Icon(
                            imageVector = if (showHiddenFiles) {
                                Icons.Default.VisibilityOff
                            } else {
                                Icons.Default.Visibility
                            },
                            contentDescription = null
                        )
                    },
                    onClick = {
                        onToggleHiddenFiles()
                        onMenuToggle(false)
                    }
                )
            }
        }
    )
}