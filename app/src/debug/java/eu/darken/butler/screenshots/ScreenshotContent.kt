package eu.darken.butler.screenshots

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import eu.darken.butler.apps.R as AppsR
import eu.darken.butler.apps.ui.apps.AppsWorkspacePage
import eu.darken.butler.apps.ui.apps.AppsWorkspaceViewModel
import eu.darken.butler.apps.ui.apps.preview.AppsMockDataProvider
import eu.darken.butler.common.ca.caString
import eu.darken.butler.common.ca.toCaString
import eu.darken.butler.editor.core.engine.ContentSource
import eu.darken.butler.editor.ui.editor.EditorWorkspacePage
import eu.darken.butler.editor.ui.editor.EditorWorkspaceViewModel
import eu.darken.butler.explorer.ui.explorer.ExplorerWorkspacePage
import eu.darken.butler.explorer.ui.explorer.ExplorerWorkspaceViewModel
import eu.darken.butler.explorer.ui.explorer.preview.MockDataProvider
import eu.darken.butler.searcher.ui.search.SearcherWorkspacePage
import eu.darken.butler.searcher.ui.search.preview.SearcherMockDataProvider
import eu.darken.butler.templates.ui.TemplatesWorkspacePage
import eu.darken.butler.templates.ui.preview.TemplatesMockDataProvider
import eu.darken.butler.workspace.R as WorkspaceR
import eu.darken.butler.workspace.contracts.apps.SortSettings
import eu.darken.butler.workspace.contracts.apps.TagFilterConfig
import eu.darken.butler.workspace.core.Workspace
import eu.darken.butler.workspace.core.defaultArguments
import eu.darken.butler.workspace.core.icon
import eu.darken.butler.workspace.core.label
import eu.darken.butler.workspace.ui.LocalWorkspaceFocused
import eu.darken.butler.workspace.ui.LocalWorkspacePageHosts
import eu.darken.butler.workspace.ui.WorkspacePageHostEntry
import eu.darken.butler.workspace.ui.clipboard.ClipboardDisplayState
import eu.darken.butler.workspace.ui.floatingbar.LocalWorkspaceBarCollapseStates
import eu.darken.butler.workspace.ui.floatingbar.WorkspaceBarCollapseStates
import eu.darken.butler.workspace.ui.manager.FakeWorkspaceButtonProvider
import eu.darken.butler.workspace.ui.manager.WorkspaceButtonMenu
import eu.darken.butler.workspace.ui.manager.WorkspaceButtonViewModel
import eu.darken.butler.workspace.ui.manager.WorkspaceDesign
import eu.darken.butler.workspace.ui.manager.WorkspaceManagerScreen
import eu.darken.butler.workspace.ui.manager.WorkspaceManagerViewModel
import eu.darken.butler.workspace.ui.manager.rememberWindowSizeInfo
import eu.darken.butler.workspace.ui.operations.OperationsDisplayState
import eu.darken.butler.workspace.ui.scroll.LocalWorkspaceScrollPositions
import eu.darken.butler.workspace.ui.scroll.WorkspaceScrollPositions
import eu.darken.butler.workspace.ui.template.QuickCreateItem
import eu.darken.butler.workspace.ui.workspaces.AdaptiveWorkspaceLayout
import eu.darken.butler.workspace.ui.workspaces.WorkspacePaneInfo
import eu.darken.butler.workspace.ui.workspaces.adaptive.DividerPositions
import kotlinx.coroutines.flow.MutableStateFlow
import kotlin.uuid.Uuid

internal const val DS_PHONE = "spec:width=1080px,height=2400px,dpi=428"
internal const val DS_SEVEN = "spec:width=1200px,height=1920px,dpi=320"
internal const val DS_TEN = "spec:width=2560px,height=1600px,dpi=320"

/**
 * Which device spec a screenshot renders on, and therefore how many panes it composes.
 *
 * Screenshot content calls the page composables directly, bypassing `WorkspacesScreen`. The
 * adaptive chain that normally derives a multi-pane layout from the window size never runs for a
 * bare page, so a larger device spec alone would only stretch a single pane. Multi-pane shots
 * compose [ScreenshotPaneFrame] explicitly instead.
 */
internal enum class ScreenshotFormFactor {
    PHONE,
    SEVEN,
    TEN,
    ;
}

/**
 * One pane of a multi-pane screenshot.
 *
 * [id] must be distinct per pane even when two panes share a [type]: the page host map is keyed by
 * type alone, so pane content is dispatched by id inside [ScreenshotPaneFrame].
 */
internal data class ScreenshotPane(
    val id: Workspace.Id,
    val type: Workspace.Type,
    val body: @Composable (Workspace.Id, WorkspaceDesign) -> Unit,
)

/**
 * Composes [panes] through the real adaptive layout, including the navigation rail.
 *
 * @param layout pass null to use the layout the window size would recommend.
 */
@Composable
internal fun ScreenshotPaneFrame(
    panes: List<ScreenshotPane>,
    layout: WorkspaceDesign.Layout? = null,
) {
    val resolvedLayout = layout ?: rememberWindowSizeInfo().recommendedLayout
    val design = remember(resolvedLayout) { WorkspaceDesign(layout = resolvedLayout) }

    // Drives the navigation rail; an inconsistent list leaves the rail without tabs.
    val workspaces = remember(panes) {
        panes.map {
            Workspace.Info(
                id = it.id,
                type = it.type,
                title = it.type.label,
                lifecycleState = Workspace.LifecycleState.Ready,
            )
        }
    }

    // Zero-based: the layouts read selected[paneNumber - 1], a one-based map would blank every pane.
    // Ready for every pane, otherwise WorkspaceMapper hides the page instead of composing it.
    val selected = remember(panes) {
        panes.mapIndexed { index, pane ->
            index to WorkspacePaneInfo(
                id = pane.id,
                type = pane.type,
                lifecycleState = Workspace.LifecycleState.Ready,
                title = pane.type.label,
            )
        }.toMap()
    }

    // One fake host per distinct type, resolving the body by pane id: the host map is keyed by type
    // only, so a type-keyed body would render the same content in both panes of a duplicated type.
    val pageHosts = remember(panes) {
        panes.distinctBy { it.type }.associate { pane ->
            pane.type to object : WorkspacePageHostEntry {
                @Composable
                override fun Content(id: Workspace.Id, design: WorkspaceDesign) {
                    panes.first { it.id == id }.body(id, design)
                }

                @Composable
                override fun Overlays(id: Workspace.Id, design: WorkspaceDesign) = Unit
            }
        }
    }

    val scrollPositions = remember { WorkspaceScrollPositions() }
    val barCollapseStates = remember { WorkspaceBarCollapseStates() }
    val dividerPositions = remember { DividerPositions() }

    CompositionLocalProvider(
        LocalWorkspaceFocused provides false,
        LocalWorkspacePageHosts provides pageHosts,
        LocalWorkspaceScrollPositions provides scrollPositions,
        LocalWorkspaceBarCollapseStates provides barCollapseStates,
    ) {
        AdaptiveWorkspaceLayout(
            design = design,
            workspaces = workspaces,
            selected = selected,
            focusedId = panes.firstOrNull()?.id,
            dividerPositions = dividerPositions,
            onDividerPositionsChange = {},
            showPaneNumbers = false,
            showPaneOverlay = false,
            onPaneMenuToggle = {},
            onScreenAction = {},
            managerDialogStates = emptyMap(),
            onDismissManagerDialog = {},
            onConfirmManagerDialog = {},
            bannerStates = emptyMap(),
            onDismissBanner = {},
            onShareError = { _, _ -> },
        )
    }
}

private fun screenshotWorkspaceId(marker: String) =
    Workspace.Id(Uuid.parse("00000000-0000-4000-8000-00000000000$marker"))

private val ID_EXPLORER_HOME = screenshotWorkspaceId("1")
private val ID_EXPLORER_DIRECTORY = screenshotWorkspaceId("2")
private val ID_SEARCHER = screenshotWorkspaceId("3")
private val ID_EDITOR = screenshotWorkspaceId("4")
private val ID_APPS = screenshotWorkspaceId("5")
private val ID_TEMPLATES = screenshotWorkspaceId("6")

@Composable
private fun ExplorerHomeBody(id: Workspace.Id, design: WorkspaceDesign) {
    val homeLocation = remember { MockDataProvider.createMockHomeLocation() }
    ExplorerWorkspacePage(
        workspaceId = id,
        design = design,
        mainStateSource = remember {
            MutableStateFlow(
                ExplorerWorkspaceViewModel.State(
                    currentLocation = homeLocation,
                    breadcrumbs = listOf(MockDataProvider.createHomeBreadcrumb()),
                    items = homeLocation.items,
                    availableActions = MockDataProvider.createDefaultHomeActions(),
                    favorites = MockDataProvider.createMockFavorites(),
                    showHomeFavoritesSection = true,
                )
            )
        },
        operationsStateSource = remember { MutableStateFlow(OperationsDisplayState()) },
        clipboardStateSource = remember { MutableStateFlow(ClipboardDisplayState()) },
    )
}

@Composable
private fun ExplorerDirectoryBody(id: Workspace.Id, design: WorkspaceDesign) {
    ExplorerWorkspacePage(
        workspaceId = id,
        design = design,
        mainStateSource = remember {
            val items = MockDataProvider.createAndroidDeviceListing()
            MutableStateFlow(
                MockDataProvider.createReadyState(
                    location = MockDataProvider.createMockDirectoryLocation(
                        path = "/storage/emulated/0",
                        items = items,
                        info = MockDataProvider.createAndroidDeviceInfo(items),
                    ),
                    breadcrumbs = MockDataProvider.createDeviceRootBreadcrumbs(),
                    actions = MockDataProvider.createDefaultDirectoryActions(),
                )
            )
        },
        operationsStateSource = remember { MutableStateFlow(OperationsDisplayState()) },
        clipboardStateSource = remember { MutableStateFlow(ClipboardDisplayState()) },
    )
}

@Composable
private fun SearcherResultsBody(id: Workspace.Id, design: WorkspaceDesign) {
    SearcherWorkspacePage(
        workspaceId = id,
        design = design,
        stateSource = remember { MutableStateFlow(SearcherMockDataProvider.createMockResultsState()) },
        clipboardStateSource = remember { MutableStateFlow(ClipboardDisplayState()) },
        operationsStateSource = remember { MutableStateFlow(OperationsDisplayState()) },
    )
}

@Composable
private fun EditorViewBody(id: Workspace.Id, design: WorkspaceDesign) {
    EditorWorkspacePage(
        workspaceId = id,
        design = design,
        mainStateSource = remember {
            MutableStateFlow(
                EditorWorkspaceViewModel.State(
                    id = id,
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
            )
        },
        onPageAction = {},
    )
}

@Composable
private fun AppsManagerBody(id: Workspace.Id, design: WorkspaceDesign) {
    AppsWorkspacePage(
        workspaceId = id,
        design = design,
        stateSource = remember {
            MutableStateFlow(
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
            )
        },
    )
}

@Composable
private fun TemplatesPickerBody(id: Workspace.Id, design: WorkspaceDesign) {
    TemplatesWorkspacePage(
        workspaceId = id,
        design = design,
        state = remember { TemplatesMockDataProvider.createMockState(id) },
        onNavToSettings = {},
    )
}

@Composable
private fun WorkspaceManagerBody() {
    WorkspaceManagerScreen(
        state = WorkspaceManagerViewModel.State(
            workspaces = listOf(
                WorkspaceManagerViewModel.WorkspaceItem(
                    id = ID_EXPLORER_DIRECTORY,
                    type = Workspace.Type.EXPLORER,
                    title = "/storage/emulated/0/Download".toCaString(),
                    autoTitle = "/storage/emulated/0/Download".toCaString(),
                    subtitle = null,
                    isFocused = true,
                    isSelected = true,
                    paneNumber = 1,
                ),
                WorkspaceManagerViewModel.WorkspaceItem(
                    id = ID_SEARCHER,
                    type = Workspace.Type.SEARCHER,
                    title = "*.log".toCaString(),
                    autoTitle = "*.log".toCaString(),
                    subtitle = "Device storage".toCaString(),
                ),
                WorkspaceManagerViewModel.WorkspaceItem(
                    id = ID_EDITOR,
                    type = Workspace.Type.EDITOR,
                    title = "build.gradle.kts".toCaString(),
                    autoTitle = "build.gradle.kts".toCaString(),
                    subtitle = "/storage/emulated/0/Projects/butler".toCaString(),
                ),
                WorkspaceManagerViewModel.WorkspaceItem(
                    id = ID_APPS,
                    type = Workspace.Type.APPS,
                    title = WorkspaceR.string.workspace_apps_label.toCaString(),
                    autoTitle = WorkspaceR.string.workspace_apps_label.toCaString(),
                    subtitle = AppsR.string.apps_subtitle.toCaString(),
                ),
            ),
            showBadgeExplanation = false,
            showLongPressHint = false,
        ),
        onCloseWorkspace = {},
        onReorderWorkspaces = {},
        onSelectWorkspace = {},
        onPauseWorkspace = {},
        onResumeWorkspace = {},
        onCreateWorkspace = {},
        onQuickCreate = {},
        onNavigateBack = {},
        onDismissBadgeExplanation = {},
        onDismissLongPressHint = {},
        onCloseAllWorkspaces = {},
    )
}

private val explorerHomePane = ScreenshotPane(
    id = ID_EXPLORER_HOME,
    type = Workspace.Type.EXPLORER,
) { id, design -> ExplorerHomeBody(id, design) }

private val explorerDirectoryPane = ScreenshotPane(
    id = ID_EXPLORER_DIRECTORY,
    type = Workspace.Type.EXPLORER,
) { id, design -> ExplorerDirectoryBody(id, design) }

private val searcherPane = ScreenshotPane(
    id = ID_SEARCHER,
    type = Workspace.Type.SEARCHER,
) { id, design -> SearcherResultsBody(id, design) }

private val editorPane = ScreenshotPane(
    id = ID_EDITOR,
    type = Workspace.Type.EDITOR,
) { id, design -> EditorViewBody(id, design) }

private val appsPane = ScreenshotPane(
    id = ID_APPS,
    type = Workspace.Type.APPS,
) { id, design -> AppsManagerBody(id, design) }

private val templatesPane = ScreenshotPane(
    id = ID_TEMPLATES,
    type = Workspace.Type.TEMPLATES,
) { id, design -> TemplatesPickerBody(id, design) }

private fun quickCreateItem(type: Workspace.Type) = QuickCreateItem(
    type = type,
    icon = type.icon,
    title = type.label,
    arguments = type.defaultArguments!!,
)

/**
 * The Butler button's menu, opened.
 *
 * It cannot be opened by clicking in a single-frame render, so it is composed next to the page and
 * anchored on a zero-size box that sits where the real button's bottom edge is: the toolbar card's
 * cutout is top-aligned, 48dp tall and inset from the pane edge.
 */
@Composable
private fun ExpandedWorkspaceButtonMenu(currentWorkspaceId: Workspace.Id) {
    Box(
        modifier = Modifier
            .padding(top = 54.dp, end = 16.dp)
            .size(0.dp),
    ) {
        WorkspaceButtonMenu(
            expanded = true,
            onDismissRequest = {},
            state = WorkspaceButtonViewModel.State(
                workspaceCount = 5,
                operationsCount = 1,
                attentionCount = 1,
                recentItems = listOf(
                    quickCreateItem(Workspace.Type.EXPLORER),
                    quickCreateItem(Workspace.Type.SEARCHER),
                    quickCreateItem(Workspace.Type.EDITOR),
                ),
            ),
            currentWorkspaceId = currentWorkspaceId,
            provider = FakeWorkspaceButtonProvider(),
            onCloseAllRequested = {},
        )
    }
}

@Composable
internal fun ExplorerHomeContent(formFactor: ScreenshotFormFactor) = ScreenshotPreviewWrapper {
    when (formFactor) {
        ScreenshotFormFactor.PHONE -> Box(modifier = Modifier.fillMaxSize()) {
            ExplorerHomeBody(ID_EXPLORER_HOME, WorkspaceDesign())
            Box(modifier = Modifier.align(Alignment.TopEnd)) {
                ExpandedWorkspaceButtonMenu(ID_EXPLORER_HOME)
            }
        }
        // Two EXPLORER panes side by side: the proof that pane content is dispatched by id.
        ScreenshotFormFactor.SEVEN -> ScreenshotPaneFrame(listOf(explorerHomePane, explorerDirectoryPane))
        ScreenshotFormFactor.TEN -> ScreenshotPaneFrame(listOf(explorerHomePane, explorerDirectoryPane, searcherPane))
    }
}

@Composable
internal fun ExplorerDirectoryContent(formFactor: ScreenshotFormFactor) = ScreenshotPreviewWrapper {
    when (formFactor) {
        ScreenshotFormFactor.PHONE -> ExplorerDirectoryBody(ID_EXPLORER_DIRECTORY, WorkspaceDesign())
        ScreenshotFormFactor.SEVEN -> ScreenshotPaneFrame(listOf(explorerDirectoryPane, editorPane))
        ScreenshotFormFactor.TEN -> ScreenshotPaneFrame(listOf(explorerDirectoryPane, editorPane, appsPane))
    }
}

@Composable
internal fun SearcherResultsContent(formFactor: ScreenshotFormFactor) = ScreenshotPreviewWrapper {
    when (formFactor) {
        ScreenshotFormFactor.PHONE -> SearcherResultsBody(ID_SEARCHER, WorkspaceDesign())
        ScreenshotFormFactor.SEVEN -> ScreenshotPaneFrame(listOf(searcherPane, explorerDirectoryPane))
        ScreenshotFormFactor.TEN -> ScreenshotPaneFrame(listOf(searcherPane, explorerDirectoryPane, editorPane))
    }
}

@Composable
internal fun EditorViewContent(formFactor: ScreenshotFormFactor) = ScreenshotPreviewWrapper {
    when (formFactor) {
        ScreenshotFormFactor.PHONE -> EditorViewBody(ID_EDITOR, WorkspaceDesign())
        ScreenshotFormFactor.SEVEN -> ScreenshotPaneFrame(listOf(editorPane, explorerDirectoryPane))
        ScreenshotFormFactor.TEN -> ScreenshotPaneFrame(listOf(editorPane, explorerDirectoryPane, searcherPane))
    }
}

@Composable
internal fun AppsManagerContent(formFactor: ScreenshotFormFactor) = ScreenshotPreviewWrapper {
    when (formFactor) {
        ScreenshotFormFactor.PHONE -> AppsManagerBody(ID_APPS, WorkspaceDesign())
        ScreenshotFormFactor.SEVEN -> ScreenshotPaneFrame(listOf(appsPane, explorerDirectoryPane))
        ScreenshotFormFactor.TEN -> ScreenshotPaneFrame(listOf(appsPane, explorerDirectoryPane, editorPane))
    }
}

@Composable
internal fun TemplatesPickerContent(formFactor: ScreenshotFormFactor) = ScreenshotPreviewWrapper {
    when (formFactor) {
        ScreenshotFormFactor.PHONE -> TemplatesPickerBody(ID_TEMPLATES, WorkspaceDesign())
        ScreenshotFormFactor.SEVEN -> ScreenshotPaneFrame(listOf(templatesPane, explorerDirectoryPane))
        ScreenshotFormFactor.TEN -> ScreenshotPaneFrame(listOf(templatesPane, explorerDirectoryPane, editorPane))
    }
}

@Composable
internal fun MultiPaneContent(formFactor: ScreenshotFormFactor) = ScreenshotPreviewWrapper {
    when (formFactor) {
        ScreenshotFormFactor.PHONE -> ScreenshotPaneFrame(
            panes = listOf(explorerDirectoryPane, editorPane),
            layout = WorkspaceDesign.Layout.DUAL_HORIZONTAL,
        )
        ScreenshotFormFactor.SEVEN, ScreenshotFormFactor.TEN -> ScreenshotPaneFrame(
            panes = listOf(explorerHomePane, searcherPane, editorPane, appsPane),
            layout = WorkspaceDesign.Layout.QUAD_GRID,
        )
    }
}

@Composable
internal fun WorkspaceManagerContent(formFactor: ScreenshotFormFactor) = ScreenshotPreviewWrapper {
    when (formFactor) {
        // The manager is a full-window screen on every form factor, it is never paneled.
        ScreenshotFormFactor.PHONE, ScreenshotFormFactor.SEVEN, ScreenshotFormFactor.TEN -> WorkspaceManagerBody()
    }
}

// IDE Previews

@Preview(name = "2 - Explorer Home - Phone", locale = "en", device = DS_PHONE, showSystemUi = true)
@Composable
private fun PreviewExplorerHomePhone() = ExplorerHomeContent(ScreenshotFormFactor.PHONE)

@Preview(name = "2 - Explorer Home - 7\"", locale = "en", device = DS_SEVEN, showSystemUi = true)
@Composable
private fun PreviewExplorerHomeSeven() = ExplorerHomeContent(ScreenshotFormFactor.SEVEN)

@Preview(name = "2 - Explorer Home - 10\"", locale = "en", device = DS_TEN, showSystemUi = true)
@Composable
private fun PreviewExplorerHomeTen() = ExplorerHomeContent(ScreenshotFormFactor.TEN)

@Preview(name = "1 - Explorer Directory - Phone", locale = "en", device = DS_PHONE, showSystemUi = true)
@Composable
private fun PreviewExplorerDirectoryPhone() = ExplorerDirectoryContent(ScreenshotFormFactor.PHONE)

@Preview(name = "1 - Explorer Directory - 7\"", locale = "en", device = DS_SEVEN, showSystemUi = true)
@Composable
private fun PreviewExplorerDirectorySeven() = ExplorerDirectoryContent(ScreenshotFormFactor.SEVEN)

@Preview(name = "1 - Explorer Directory - 10\"", locale = "en", device = DS_TEN, showSystemUi = true)
@Composable
private fun PreviewExplorerDirectoryTen() = ExplorerDirectoryContent(ScreenshotFormFactor.TEN)

@Preview(name = "3 - Searcher Results - Phone", locale = "en", device = DS_PHONE, showSystemUi = true)
@Composable
private fun PreviewSearcherResultsPhone() = SearcherResultsContent(ScreenshotFormFactor.PHONE)

@Preview(name = "3 - Searcher Results - 7\"", locale = "en", device = DS_SEVEN, showSystemUi = true)
@Composable
private fun PreviewSearcherResultsSeven() = SearcherResultsContent(ScreenshotFormFactor.SEVEN)

@Preview(name = "3 - Searcher Results - 10\"", locale = "en", device = DS_TEN, showSystemUi = true)
@Composable
private fun PreviewSearcherResultsTen() = SearcherResultsContent(ScreenshotFormFactor.TEN)

@Preview(name = "4 - Editor - Phone", locale = "en", device = DS_PHONE, showSystemUi = true)
@Composable
private fun PreviewEditorViewPhone() = EditorViewContent(ScreenshotFormFactor.PHONE)

@Preview(name = "4 - Editor - 7\"", locale = "en", device = DS_SEVEN, showSystemUi = true)
@Composable
private fun PreviewEditorViewSeven() = EditorViewContent(ScreenshotFormFactor.SEVEN)

@Preview(name = "4 - Editor - 10\"", locale = "en", device = DS_TEN, showSystemUi = true)
@Composable
private fun PreviewEditorViewTen() = EditorViewContent(ScreenshotFormFactor.TEN)

@Preview(name = "5 - Apps Manager - Phone", locale = "en", device = DS_PHONE, showSystemUi = true)
@Composable
private fun PreviewAppsManagerPhone() = AppsManagerContent(ScreenshotFormFactor.PHONE)

@Preview(name = "5 - Apps Manager - 7\"", locale = "en", device = DS_SEVEN, showSystemUi = true)
@Composable
private fun PreviewAppsManagerSeven() = AppsManagerContent(ScreenshotFormFactor.SEVEN)

@Preview(name = "5 - Apps Manager - 10\"", locale = "en", device = DS_TEN, showSystemUi = true)
@Composable
private fun PreviewAppsManagerTen() = AppsManagerContent(ScreenshotFormFactor.TEN)

@Preview(name = "6 - Workspace Manager - Phone", locale = "en", device = DS_PHONE, showSystemUi = true)
@Composable
private fun PreviewWorkspaceManagerPhone() = WorkspaceManagerContent(ScreenshotFormFactor.PHONE)

@Preview(name = "6 - Workspace Manager - 7\"", locale = "en", device = DS_SEVEN, showSystemUi = true)
@Composable
private fun PreviewWorkspaceManagerSeven() = WorkspaceManagerContent(ScreenshotFormFactor.SEVEN)

@Preview(name = "6 - Workspace Manager - 10\"", locale = "en", device = DS_TEN, showSystemUi = true)
@Composable
private fun PreviewWorkspaceManagerTen() = WorkspaceManagerContent(ScreenshotFormFactor.TEN)

@Preview(name = "7 - Multi Pane - Phone", locale = "en", device = DS_PHONE, showSystemUi = true)
@Composable
private fun PreviewMultiPanePhone() = MultiPaneContent(ScreenshotFormFactor.PHONE)

@Preview(name = "7 - Multi Pane - 7\"", locale = "en", device = DS_SEVEN, showSystemUi = true)
@Composable
private fun PreviewMultiPaneSeven() = MultiPaneContent(ScreenshotFormFactor.SEVEN)

@Preview(name = "7 - Multi Pane - 10\"", locale = "en", device = DS_TEN, showSystemUi = true)
@Composable
private fun PreviewMultiPaneTen() = MultiPaneContent(ScreenshotFormFactor.TEN)

@Preview(name = "8 - Templates Picker - Phone", locale = "en", device = DS_PHONE, showSystemUi = true)
@Composable
private fun PreviewTemplatesPickerPhone() = TemplatesPickerContent(ScreenshotFormFactor.PHONE)

@Preview(name = "8 - Templates Picker - 7\"", locale = "en", device = DS_SEVEN, showSystemUi = true)
@Composable
private fun PreviewTemplatesPickerSeven() = TemplatesPickerContent(ScreenshotFormFactor.SEVEN)

@Preview(name = "8 - Templates Picker - 10\"", locale = "en", device = DS_TEN, showSystemUi = true)
@Composable
private fun PreviewTemplatesPickerTen() = TemplatesPickerContent(ScreenshotFormFactor.TEN)
