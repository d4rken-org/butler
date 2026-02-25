package eu.darken.butler.screenshots

import androidx.compose.runtime.Composable
import androidx.compose.ui.tooling.preview.Preview
import eu.darken.butler.apps.core.SortSettings
import eu.darken.butler.apps.core.TagFilterConfig
import eu.darken.butler.apps.ui.apps.AppsWorkspacePage
import eu.darken.butler.apps.ui.apps.AppsWorkspaceViewModel
import eu.darken.butler.apps.ui.apps.preview.AppsMockDataProvider
import eu.darken.butler.common.ca.caString
import eu.darken.butler.common.ca.toCaString
import eu.darken.butler.common.compose.PreviewWrapper
import eu.darken.butler.editor.core.engine.ContentSource
import eu.darken.butler.editor.ui.editor.EditorWorkspacePage
import eu.darken.butler.editor.ui.editor.EditorWorkspaceViewModel
import eu.darken.butler.explorer.ui.explorer.ExplorerWorkspacePage
import eu.darken.butler.explorer.ui.explorer.ExplorerWorkspaceViewModel
import eu.darken.butler.explorer.ui.explorer.preview.MockDataProvider
import eu.darken.butler.searcher.ui.search.SearcherWorkspacePage
import eu.darken.butler.searcher.ui.search.SearcherWorkspaceViewModel
import eu.darken.butler.searcher.ui.search.preview.SearcherMockDataProvider
import eu.darken.butler.workspace.core.Workspace
import eu.darken.butler.workspace.ui.manager.WorkspaceDesign
import eu.darken.butler.workspace.ui.manager.WorkspaceManagerScreen
import eu.darken.butler.workspace.ui.manager.WorkspaceManagerViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import eu.darken.butler.workspace.R as WorkspaceR

internal const val DS = "spec:width=822px,height=1828px,dpi=320"

@Composable
internal fun ExplorerHomeContent() = PreviewWrapper {
    val homeLocation = MockDataProvider.createMockHomeLocation()
    ExplorerWorkspacePage(
        workspaceId = Workspace.Id(),
        mainStateSource = MutableStateFlow(
            ExplorerWorkspaceViewModel.State(
                currentLocation = homeLocation,
                breadcrumbs = listOf(MockDataProvider.createHomeBreadcrumb()),
                items = homeLocation.items,
            )
        ),
        operationsStateSource = MutableStateFlow(ExplorerWorkspaceViewModel.OperationsState()),
        clipboardStateSource = MutableStateFlow(ExplorerWorkspaceViewModel.ClipboardState()),
    )
}

@Composable
internal fun ExplorerDirectoryContent() = PreviewWrapper {
    ExplorerWorkspacePage(
        workspaceId = Workspace.Id(),
        mainStateSource = MutableStateFlow(MockDataProvider.createReadyState()),
        operationsStateSource = MutableStateFlow(ExplorerWorkspaceViewModel.OperationsState()),
        clipboardStateSource = MutableStateFlow(ExplorerWorkspaceViewModel.ClipboardState()),
    )
}

@Composable
internal fun SearcherResultsContent() = PreviewWrapper {
    SearcherWorkspacePage(
        workspaceId = Workspace.Id(),
        stateSource = MutableStateFlow(SearcherMockDataProvider.createMockResultsState()),
        clipboardStateSource = MutableStateFlow(SearcherWorkspaceViewModel.ClipboardState()),
        operationsStateSource = MutableStateFlow(SearcherWorkspaceViewModel.OperationsState()),
    )
}

@Composable
internal fun EditorViewContent() = PreviewWrapper {
    EditorWorkspacePage(
        workspaceId = Workspace.Id(),
        design = WorkspaceDesign(),
        mainStateSource = MutableStateFlow(
            EditorWorkspaceViewModel.State(
                id = Workspace.Id(),
                contentSource = ContentSource.Memory(size = 2048L),
                title = caString("build.gradle.kts"),
                subTitle = caString("/storage/emulated/0/Projects/myapp/build.gradle.kts"),
                totalLines = 42,
                isModified = false,
                currentContent = """plugins {
    id("com.android.application")
    id("kotlin-android")
    id("kotlin-parcelize")
}

android {
    namespace = "com.example.myapp"
    compileSdk = 35

    defaultConfig {
        applicationId = "com.example.myapp"
        minSdk = 26
        targetSdk = 35
        versionCode = 1
        versionName = "1.0.0"
    }

    buildFeatures {
        compose = true
    }
}

dependencies {
    implementation("androidx.core:core-ktx:1.15.0")
    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.material3:material3")
    implementation("androidx.lifecycle:lifecycle-viewmodel-compose")
    testImplementation("junit:junit:4.13.2")
}""",
                showLineNumbers = true,
            )
        ),
        onPageAction = {},
    )
}

@Composable
internal fun AppsManagerContent() = PreviewWrapper {
    AppsWorkspacePage(
        workspaceId = Workspace.Id(),
        design = WorkspaceDesign(),
        stateSource = MutableStateFlow(
            AppsWorkspaceViewModel.State.Ready(
                apps = listOf(
                    AppsMockDataProvider.Presets.chromeItem,
                    AppsMockDataProvider.Presets.settingsItem,
                    AppsMockDataProvider.Presets.notesItem,
                    AppsMockDataProvider.Presets.splitApkItem,
                    AppsMockDataProvider.Presets.systemUiItem,
                    AppsMockDataProvider.Presets.updatedSystemItem,
                    AppsMockDataProvider.Presets.workProfileAppItem,
                    AppsMockDataProvider.Presets.disabledAppItem,
                ),
                isLoading = false,
                filterConfig = TagFilterConfig(),
                sortSettings = SortSettings(),
            )
        ),
    )
}

@Composable
internal fun WorkspaceManagerContent() = PreviewWrapper {
    WorkspaceManagerScreen(
        state = WorkspaceManagerViewModel.State(
            workspaces = listOf(
                WorkspaceManagerViewModel.WorkspaceItem(
                    id = Workspace.Id(),
                    type = Workspace.Type.EXPLORER,
                    title = WorkspaceR.string.workspace_explorer_label.toCaString(),
                    subtitle = "/storage/emulated/0".toCaString(),
                    isFocused = true,
                    isSelected = true,
                    paneNumber = 1,
                ),
                WorkspaceManagerViewModel.WorkspaceItem(
                    id = Workspace.Id(),
                    type = Workspace.Type.SEARCHER,
                    title = WorkspaceR.string.workspace_searcher_label.toCaString(),
                    subtitle = "config".toCaString(),
                ),
                WorkspaceManagerViewModel.WorkspaceItem(
                    id = Workspace.Id(),
                    type = Workspace.Type.EDITOR,
                    title = WorkspaceR.string.workspace_editor_label.toCaString(),
                    subtitle = "build.gradle.kts".toCaString(),
                ),
                WorkspaceManagerViewModel.WorkspaceItem(
                    id = Workspace.Id(),
                    type = Workspace.Type.APPS,
                    title = WorkspaceR.string.workspace_apps_label.toCaString(),
                    subtitle = null,
                ),
            ),
            showBadgeExplanation = false,
            showLongPressHint = false,
        ),
        onCloseWorkspace = {},
        onReorderWorkspaces = {},
        onSelectWorkspace = {},
        onCreateWorkspace = {},
        onNavigateBack = {},
        onDismissBadgeExplanation = {},
        onDismissLongPressHint = {},
        onCloseAllWorkspaces = {},
    )
}

// IDE Previews

@Preview(name = "1 - Explorer Home", locale = "en", device = DS, showSystemUi = true)
@Composable
private fun PreviewExplorerHome() = ExplorerHomeContent()

@Preview(name = "2 - Explorer Directory", locale = "en", device = DS, showSystemUi = true)
@Composable
private fun PreviewExplorerDirectory() = ExplorerDirectoryContent()

@Preview(name = "3 - Searcher Results", locale = "en", device = DS, showSystemUi = true)
@Composable
private fun PreviewSearcherResults() = SearcherResultsContent()

@Preview(name = "4 - Editor", locale = "en", device = DS, showSystemUi = true)
@Composable
private fun PreviewEditorView() = EditorViewContent()

@Preview(name = "5 - Apps Manager", locale = "en", device = DS, showSystemUi = true)
@Composable
private fun PreviewAppsManager() = AppsManagerContent()
//
//@Preview(name = "6 - Workspace Manager", locale = "en", device = DS)
//@Composable
//private fun PreviewWorkspaceManager() = WorkspaceManagerContent()
