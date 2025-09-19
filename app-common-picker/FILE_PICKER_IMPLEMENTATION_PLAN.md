# Butler File Picker Implementation Plan

## Overview
This document outlines the implementation plan for a flexible, reusable file picker component for Butler that supports multiple presentation modes while leveraging the existing file system infrastructure.

## Architecture Decisions

### Module Structure
- **Module Name**: `app-common-picker`
- **Type**: Android Library Module
- **Location**: `/app-common-picker`
- **Purpose**: Provide a reusable file/folder selection component for the entire Butler application

### Design Philosophy
- **Not a Workspace**: The picker is a transient UI component, not a persistent workspace
- **Single Core, Multiple Presentations**: One implementation with different presentation wrappers
- **Reuse Existing Infrastructure**: Leverage `ExplorerEngine`, `APath`, and gateway patterns

## Component Architecture

### Presentation Modes

1. **Fullscreen Mode**
   - Navigation-based implementation
   - Full screen takeover with TopAppBar
   - Used for critical file operations (e.g., opening files in editor)
   - Returns results via SavedStateHandle

2. **BottomSheet Mode**
   - Overlay presentation
   - Takes up 70-90% of screen height
   - Quick selection workflows
   - Returns results via callbacks

3. **Adaptive Mode**
   - Automatically selects presentation based on:
     - Device type (phone/tablet)
     - Screen orientation
     - Context requirements

### Core Components

```
app-common-picker/
├── src/main/java/eu/darken/butler/common/picker/
│   ├── core/
│   │   ├── FilePickerConfig.kt          # Configuration options
│   │   ├── FilePickerState.kt           # UI state management
│   │   ├── FilePickerViewModel.kt       # Business logic
│   │   ├── FilePickerEngine.kt          # File system operations
│   │   └── FilePickerResult.kt          # Result types
│   ├── ui/
│   │   ├── FilePickerCore.kt            # Core UI component
│   │   ├── FilePickerFullScreen.kt      # Fullscreen wrapper
│   │   ├── FilePickerBottomSheet.kt     # BottomSheet wrapper
│   │   ├── AdaptiveFilePicker.kt        # Adaptive wrapper
│   │   └── components/
│   │       ├── FilePickerBreadcrumbs.kt # Navigation breadcrumbs
│   │       ├── FilePickerItem.kt        # File/folder list item
│   │       ├── FilePickerActions.kt     # Action buttons
│   │       └── FilePickerQuickAccess.kt # Quick access sidebar
│   └── navigation/
│       ├── FilePickerDestination.kt     # Navigation destination
│       └── FilePickerNavigation.kt      # Navigation helpers
```

## Implementation Phases

### Phase 1: Module Setup (Week 1)

#### 1.1 Create Module Structure
```bash
# Create module directory
mkdir -p app-common-picker/src/main/java/eu/darken/butler/common/picker
mkdir -p app-common-picker/src/main/res/values
```

#### 1.2 Configure Gradle
```kotlin
// app-common-picker/build.gradle.kts
dependencies {
    implementation(project(":app-common"))
    implementation(project(":app-common-io"))
    
    // Compose
    implementation(libs.androidx.compose.ui)
    implementation(libs.androidx.compose.material3)
    
    // Hilt
    implementation(libs.hilt.android)
    kapt(libs.hilt.compiler)
    
    // Navigation
    implementation(libs.androidx.navigation3.compose)
}
```

#### 1.3 Update Settings
```kotlin
// settings.gradle.kts
include(":app-common-picker")
```

### Phase 2: Core Implementation (Week 1-2)

#### 2.1 Configuration
```kotlin
data class FilePickerConfig(
    val mode: SelectionMode = SelectionMode.SingleFile,
    val initialPath: APath? = null,
    val filters: List<String> = emptyList(),
    val showHiddenFiles: Boolean = false,
    val allowCreateFolder: Boolean = true,
    val quickAccessPaths: List<APath> = defaultQuickAccessPaths(),
    val title: String? = null,
    val subtitle: String? = null,
)

enum class SelectionMode {
    SingleFile,
    MultipleFiles,
    SingleFolder,
    MultipleFolders,
    Mixed
}
```

#### 2.2 State Management
```kotlin
data class FilePickerState(
    val currentPath: APath,
    val items: List<FileItem> = emptyList(),
    val selectedItems: Set<APath> = emptySet(),
    val isLoading: Boolean = false,
    val error: String? = null,
    val searchQuery: String = "",
    val sortMode: SortMode = SortMode.NAME_ASC,
)
```

#### 2.3 File System Integration
- Reuse `ExplorerEngine` from `app-workspace-explorer`
- Simplify for picker-specific needs
- Support all gateway types (normal, root, ADB)

### Phase 3: UI Components (Week 2)

#### 3.1 Core Component
```kotlin
@Composable
fun FilePickerCore(
    config: FilePickerConfig,
    state: FilePickerState,
    modifier: Modifier = Modifier,
    onNavigate: (APath) -> Unit,
    onSelect: (List<APath>) -> Unit,
    onCancel: () -> Unit,
    onCreateFolder: () -> Unit,
) {
    Column(modifier = modifier) {
        // Breadcrumbs
        FilePickerBreadcrumbs(
            currentPath = state.currentPath,
            onNavigate = onNavigate
        )
        
        // Search bar (optional)
        if (config.showSearch) {
            FilePickerSearchBar(
                query = state.searchQuery,
                onQueryChange = { /* ... */ }
            )
        }
        
        // File list
        LazyColumn(
            modifier = Modifier.weight(1f)
        ) {
            items(state.items) { item ->
                FilePickerItem(
                    item = item,
                    selected = item.path in state.selectedItems,
                    onToggle = { /* ... */ },
                    onClick = { /* ... */ }
                )
            }
        }
        
        // Actions
        FilePickerActions(
            canConfirm = state.canConfirm(),
            onConfirm = { onSelect(state.selectedItems.toList()) },
            onCancel = onCancel,
            onCreateFolder = if (config.allowCreateFolder) onCreateFolder else null
        )
    }
}
```

#### 3.2 Presentation Wrappers
- **FilePickerFullScreen**: With navigation integration
- **FilePickerBottomSheet**: Modal bottom sheet
- **AdaptiveFilePicker**: Auto-selects based on context

### Phase 4: Integration (Week 3)

#### 4.1 Navigation Setup
```kotlin
@Serializable
data class FilePickerDestination(
    val config: FilePickerConfig,
    val resultKey: String = "picker_result_${UUID.randomUUID()}"
) : NavigationDestination

// In FilePickerViewModel
fun confirmSelection(paths: List<APath>) {
    savedStateHandle[destination.resultKey] = FilePickerResult.Selected(paths)
    navController.up()
}
```

#### 4.2 Result Handling
```kotlin
sealed class FilePickerResult : Parcelable {
    @Parcelize
    data class Selected(val paths: List<APath>) : FilePickerResult()
    
    @Parcelize
    object Cancelled : FilePickerResult()
}
```

#### 4.3 Helper Functions
```kotlin
// Launcher for easy integration
@Composable
fun rememberFilePickerLauncher(
    onResult: (FilePickerResult) -> Unit
): FilePickerLauncher {
    val navController = LocalNavController.current
    return remember {
        FilePickerLauncher(navController, onResult)
    }
}

// Usage
val pickerLauncher = rememberFilePickerLauncher { result ->
    when (result) {
        is FilePickerResult.Selected -> handlePaths(result.paths)
        FilePickerResult.Cancelled -> { }
    }
}

// Launch picker
pickerLauncher.launch(
    FilePickerConfig(
        mode = SelectionMode.SingleFile,
        filters = listOf("*.txt", "*.md")
    )
)
```

### Phase 5: Integration Points (Week 3-4)

#### 5.1 Editor Integration
Replace current system file picker:
```kotlin
// Before (using system picker)
val launcher = rememberLauncherForActivityResult(
    contract = ActivityResultContracts.OpenDocument()
) { uri -> /* ... */ }

// After (using Butler picker)
val pickerLauncher = rememberFilePickerLauncher { result ->
    if (result is FilePickerResult.Selected) {
        openFile(result.paths.first())
    }
}
```

#### 5.2 Searcher Integration
Add path selection for search scope:
```kotlin
// In SearcherWorkspacePage
Button(onClick = {
    pickerLauncher.launch(
        FilePickerConfig(
            mode = SelectionMode.MultipleFolders,
            title = "Select Search Locations"
        )
    )
}) {
    Text("Add Search Path")
}
```

#### 5.3 Future Integrations
- Settings: Backup/restore location selection
- Explorer: Jump to path functionality
- Templates: Import/export location

### Phase 6: Testing & Polish (Week 4)

#### 6.1 Testing Scenarios
- [ ] Normal file system navigation
- [ ] Root-required paths
- [ ] ADB-based paths
- [ ] Large directories (1000+ files)
- [ ] Configuration changes
- [ ] Different screen sizes/orientations
- [ ] Selection mode transitions
- [ ] Error handling

#### 6.2 Performance Optimizations
- Lazy loading for large directories
- Thumbnail caching for images
- Debounced search
- Virtual scrolling

#### 6.3 UI Polish
- Material 3 theming
- Smooth animations
- Loading states
- Error states with retry
- Empty states

## Usage Examples

### Example 1: Editor Opening File
```kotlin
@Composable
fun EditorWorkspacePage() {
    val pickerLauncher = rememberFilePickerLauncher { result ->
        when (result) {
            is FilePickerResult.Selected -> {
                viewModel.openFile(result.paths.first())
            }
            FilePickerResult.Cancelled -> { }
        }
    }
    
    IconButton(onClick = {
        pickerLauncher.launch(
            FilePickerConfig(
                mode = SelectionMode.SingleFile,
                filters = listOf("*.txt", "*.md", "*.json"),
                title = "Open File"
            )
        )
    }) {
        Icon(Icons.Default.FileOpen, "Open")
    }
}
```

### Example 2: Quick Folder Selection
```kotlin
var showPicker by remember { mutableStateOf(false) }

if (showPicker) {
    ButlerFilePicker(
        mode = FilePickerMode.BOTTOM_SHEET,
        config = FilePickerConfig(
            mode = SelectionMode.SingleFolder,
            title = "Select Backup Location"
        ),
        onResult = { paths ->
            settings.backupPath = paths.first()
            showPicker = false
        },
        onDismiss = { showPicker = false }
    )
}
```

### Example 3: Adaptive Mode
```kotlin
ButlerFilePicker(
    mode = FilePickerMode.ADAPTIVE, // Auto-selects based on device
    config = FilePickerConfig(
        mode = SelectionMode.MultipleFiles,
        filters = listOf("*.jpg", "*.png"),
        title = "Select Images"
    ),
    onResult = { paths ->
        importImages(paths)
    },
    onDismiss = { }
)
```

## Configuration Options

### Basic Configuration
```kotlin
FilePickerConfig(
    mode = SelectionMode.SingleFile,
    initialPath = APath.of("/storage/emulated/0/Download"),
    title = "Select File"
)
```

### Advanced Configuration
```kotlin
FilePickerConfig(
    mode = SelectionMode.MultipleFiles,
    initialPath = lastUsedPath,
    filters = listOf("*.pdf", "*.doc", "*.docx"),
    showHiddenFiles = true,
    allowCreateFolder = true,
    quickAccessPaths = listOf(
        APath.of("/storage/emulated/0/Download"),
        APath.of("/storage/emulated/0/Documents"),
        APath.of("/storage/emulated/0/DCIM"),
    ),
    title = "Select Documents",
    subtitle = "Choose one or more documents to import"
)
```

## Benefits Over Current Approach

1. **Unified Experience**: Consistent file picking across the app
2. **Root/ADB Support**: Access system files not available to system picker
3. **Rich Features**: Search, filter, sort, multi-select
4. **Flexible Presentation**: Adapt to different use cases
5. **Better Integration**: Native Butler UI/UX
6. **Performance**: Optimized for large directories
7. **Customization**: Extensive configuration options

## Success Metrics

- [ ] Replace system file picker in Editor
- [ ] Support all Butler file access methods (normal, root, ADB)
- [ ] Handle 1000+ files without lag
- [ ] Configuration changes don't lose state
- [ ] Clear result handling pattern
- [ ] Reusable across all modules
- [ ] Maintains Butler's design language

## Migration Path

### Phase 1: Initial Release
- Implement core functionality
- Test with Editor workspace
- Gather feedback

### Phase 2: Expand Usage
- Add to Searcher workspace
- Implement in Settings
- Add advanced features

### Phase 3: Deprecate Old Methods
- Remove system file picker usage
- Standardize on Butler picker
- Document best practices

## Risks & Mitigations

| Risk | Mitigation |
|------|------------|
| Performance with large dirs | Implement virtual scrolling, pagination |
| Complex state management | Use ViewModel with SavedStateHandle |
| Navigation complexity | Clear documentation, helper functions |
| User confusion | Consistent UX, clear visual hierarchy |
| Memory leaks | Proper lifecycle management, testing |

## Open Questions

1. Should we support cloud storage providers?
2. Do we need file preview functionality?
3. Should selection persist across picker instances?
4. How to handle permissions for restricted paths?
5. Should we add recent files/folders section?

## References

- [Material Design File Browsers](https://m3.material.io/)
- [Android Storage Access Framework](https://developer.android.com/guide/topics/providers/document-provider)
- Butler's existing Explorer implementation
- Navigation3 documentation

## Appendix: File Picker State Flow

```
User Opens Picker
    ↓
Initialize with Config
    ↓
Load Initial Path
    ↓
Display Files/Folders
    ↓
User Interacts
    ├─→ Navigate: Update Path → Load New Items
    ├─→ Select: Toggle Selection
    ├─→ Search: Filter Items
    └─→ Create: Show Create Dialog
    ↓
User Confirms/Cancels
    ↓
Return Result
    ↓
Close Picker
```

## Timeline

- **Week 1**: Module setup, core components
- **Week 2**: UI implementation
- **Week 3**: Integration, navigation
- **Week 4**: Testing, polish, documentation

Total estimated time: 4 weeks for complete implementation