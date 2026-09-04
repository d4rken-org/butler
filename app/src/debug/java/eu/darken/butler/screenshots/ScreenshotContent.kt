package eu.darken.butler.screenshots

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.sizeIn
import androidx.compose.foundation.layout.width
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.twotone.Add
import androidx.compose.material.icons.twotone.Close
import androidx.compose.material.icons.twotone.Settings
import androidx.compose.material.icons.twotone.Workspaces
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.MenuDefaults
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import eu.darken.butler.apps.R as AppsR
import eu.darken.butler.apps.ui.apps.AppsWorkspacePage
import eu.darken.butler.apps.ui.apps.AppsWorkspaceViewModel
import eu.darken.butler.apps.ui.apps.preview.AppsMockDataProvider
import eu.darken.butler.common.ca.caString
import eu.darken.butler.common.ca.toCaString
import eu.darken.butler.common.compose.LongClickableDropdownMenuItem
import eu.darken.butler.common.compose.asComposable
import eu.darken.butler.common.theming.ThemeMode
import eu.darken.butler.common.theming.ThemeState
import eu.darken.butler.common.theming.ThemeStyle
import eu.darken.butler.editor.core.engine.ContentSource
import eu.darken.butler.editor.ui.editor.EditorWorkspacePage
import eu.darken.butler.editor.ui.editor.EditorWorkspaceViewModel
import eu.darken.butler.explorer.ui.explorer.ExplorerWorkspacePage
import eu.darken.butler.explorer.ui.explorer.ExplorerWorkspaceViewModel
import eu.darken.butler.explorer.ui.explorer.preview.MockDataProvider
import eu.darken.butler.explorer.ui.explorer.util.ExplorerSelectionState
import eu.darken.butler.searcher.ui.search.SearcherWorkspacePage
import eu.darken.butler.searcher.ui.search.preview.SearcherMockDataProvider
import eu.darken.butler.templates.ui.TemplatesWorkspacePage
import eu.darken.butler.templates.ui.preview.TemplatesMockDataProvider
import eu.darken.butler.workspace.R as WorkspaceR
import eu.darken.butler.workspace.contracts.apps.AppTag
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
import eu.darken.butler.workspace.ui.manager.MenuCategoryHeader
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

// Play requires the long side to stay within 2x the short side, and only 9:16 portrait / 16:9
// landscape shots are eligible for the promotional surfaces. Every spec below is exactly 16:9.
// The dpi is not cosmetic: it sets the dp size, and the dp size picks the layout via
// WindowSizeInfo.recommendedPaneCount. A pane index the layout renders but ScreenshotPaneFrame
// has no `selected` entry for falls back to the empty-pane placeholder, so changing a spec
// without redoing that arithmetic silently ships a "Pane 3 is ready for content" screenshot.
internal const val DS_PHONE = "spec:width=1440px,height=2560px,dpi=560" // 411x731dp, SINGLE
internal const val DS_SEVEN = "spec:width=1080px,height=1920px,dpi=288" // 600x1066dp, DUAL_HORIZONTAL
internal const val DS_TEN = "spec:width=2560px,height=1440px,dpi=320" // 1280x720dp, TRIPLE_MAIN_LEFT

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
 * @param railExtras open tabs that no pane shows. The rail lists every tab, not only the visible
 *   ones; a workspace without a [selected] entry renders as an idle rail item. Their ids must not
 *   collide with any pane id.
 */
@Composable
internal fun ScreenshotPaneFrame(
    panes: List<ScreenshotPane>,
    layout: WorkspaceDesign.Layout? = null,
    railExtras: List<Workspace.Info> = emptyList(),
) {
    val resolvedLayout = layout ?: rememberWindowSizeInfo().recommendedLayout
    val design = remember(resolvedLayout) { WorkspaceDesign(layout = resolvedLayout) }

    // Drives the navigation rail; an inconsistent list leaves the rail without tabs.
    val workspaces = remember(panes, railExtras) {
        panes.map {
            Workspace.Info(
                id = it.id,
                type = it.type,
                title = it.type.label,
                lifecycleState = Workspace.LifecycleState.Ready,
            )
        } + railExtras
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
            bannerStates = emptyMap(),
            onDismissBanner = {},
            clickToFocus = true,
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
private val ID_EXPLORER_SDCARD = screenshotWorkspaceId("7")
private val ID_SEARCHER_MEDIA = screenshotWorkspaceId("8")
private val ID_EXPLORER_DEVICE = screenshotWorkspaceId("9")

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
private fun ExplorerDeviceBody(id: Workspace.Id, design: WorkspaceDesign) {
    val deviceLocation = remember { MockDataProvider.createMockDeviceLocation() }
    ExplorerWorkspacePage(
        workspaceId = id,
        design = design,
        mainStateSource = remember {
            MutableStateFlow(
                ExplorerWorkspaceViewModel.State(
                    currentLocation = deviceLocation,
                    breadcrumbs = MockDataProvider.createDeviceBreadcrumbs(),
                    items = deviceLocation.items,
                    availableActions = MockDataProvider.createDefaultDeviceActions(),
                )
            )
        },
        operationsStateSource = remember { MutableStateFlow(OperationsDisplayState()) },
        clipboardStateSource = remember { MutableStateFlow(ClipboardDisplayState()) },
    )
}

/**
 * @param selectedIndices positions in the listing to show as selected. A non-empty set puts the
 *   page into selection mode, which is what surfaces the checkboxes and the contextual action bar.
 * @param operations a populated state shows the running-operation bar over the listing.
 */
@Composable
private fun ExplorerDirectoryBody(
    id: Workspace.Id,
    design: WorkspaceDesign,
    selectedIndices: Set<Int> = emptySet(),
    operations: OperationsDisplayState = OperationsDisplayState(),
) {
    ExplorerWorkspacePage(
        workspaceId = id,
        design = design,
        mainStateSource = remember(selectedIndices) {
            val items = MockDataProvider.createAndroidDeviceListing()
            MutableStateFlow(
                MockDataProvider.createReadyState(
                    location = MockDataProvider.createMockDirectoryLocation(
                        path = "/storage/emulated/0",
                        items = items,
                        info = MockDataProvider.createAndroidDeviceInfo(items),
                    ),
                    breadcrumbs = MockDataProvider.createDeviceRootBreadcrumbs(),
                    // The action bar has to agree with the selection: browsing actions next to
                    // "3 selected" is a combination the app never shows.
                    actions = when {
                        selectedIndices.isEmpty() -> MockDataProvider.createDefaultDirectoryActions()
                        else -> MockDataProvider.createSelectionActions()
                    },
                    selectionState = ExplorerSelectionState(
                        selectableItems = items.toSet(),
                        selectedItems = selectedIndices.map { items[it] }.toSet(),
                    ),
                )
            )
        },
        operationsStateSource = remember(operations) { MutableStateFlow(operations) },
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
                    apps = AppsMockDataProvider.Presets.playStoreItems,
                    isLoading = false,
                    // True of every row in the list: none of them belongs to that profile. An
                    // active filter has to hold for everything the shot shows.
                    filterConfig = TagFilterConfig(
                        excludeTags = setOf(AppTag.User(handleId = 11, label = "Guest")),
                    ),
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
                    topId = ID_EXPLORER_DIRECTORY,
                    type = Workspace.Type.EXPLORER,
                    title = "/storage/emulated/0/Download".toCaString(),
                    autoTitle = "/storage/emulated/0/Download".toCaString(),
                    subtitle = null,
                    isFocused = true,
                    isVisibleInPane = true,
                    paneNumber = 1,
                ),
                WorkspaceManagerViewModel.WorkspaceItem(
                    id = ID_EXPLORER_HOME,
                    topId = ID_EXPLORER_HOME,
                    type = Workspace.Type.EXPLORER,
                    title = "Project files".toCaString(),
                    autoTitle = "/storage/emulated/0/Projects/butler".toCaString(),
                    subtitle = null,
                    customTitle = "Project files",
                ),
                WorkspaceManagerViewModel.WorkspaceItem(
                    id = ID_SEARCHER,
                    topId = ID_SEARCHER,
                    type = Workspace.Type.SEARCHER,
                    title = "*.log".toCaString(),
                    autoTitle = "*.log".toCaString(),
                    subtitle = "Device storage".toCaString(),
                    attentionCount = 1,
                ),
                WorkspaceManagerViewModel.WorkspaceItem(
                    id = ID_EDITOR,
                    topId = ID_EDITOR,
                    type = Workspace.Type.EDITOR,
                    title = "build.gradle.kts".toCaString(),
                    autoTitle = "build.gradle.kts".toCaString(),
                    subtitle = "/storage/emulated/0/Projects/butler".toCaString(),
                    operationCount = 2,
                ),
                WorkspaceManagerViewModel.WorkspaceItem(
                    id = ID_APPS,
                    topId = ID_APPS,
                    type = Workspace.Type.APPS,
                    title = WorkspaceR.string.workspace_apps_label.toCaString(),
                    autoTitle = WorkspaceR.string.workspace_apps_label.toCaString(),
                    subtitle = AppsR.string.apps_subtitle.toCaString(),
                ),
                WorkspaceManagerViewModel.WorkspaceItem(
                    id = ID_EXPLORER_SDCARD,
                    topId = ID_EXPLORER_SDCARD,
                    type = Workspace.Type.EXPLORER,
                    title = "/storage/1A2B-3C4D/Backups".toCaString(),
                    autoTitle = "/storage/1A2B-3C4D/Backups".toCaString(),
                    subtitle = null,
                    isPaused = true,
                    canPause = true,
                ),
                WorkspaceManagerViewModel.WorkspaceItem(
                    id = ID_SEARCHER_MEDIA,
                    topId = ID_SEARCHER_MEDIA,
                    type = Workspace.Type.SEARCHER,
                    title = "IMG_2026".toCaString(),
                    autoTitle = "IMG_2026".toCaString(),
                    subtitle = "Photos".toCaString(),
                ),
            ),
            showBadgeExplanation = false,
            operationsCount = 2,
            attentionCount = 1,
            // Derived from the items above by the real ViewModel; the mock has to state it itself,
            // and a mismatch would show a chip row that contradicts the cards under it.
            pausedCount = 1,
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
        onCloseAllWorkspaces = {},
    )
}

private val explorerHomePane = ScreenshotPane(
    id = ID_EXPLORER_HOME,
    type = Workspace.Type.EXPLORER,
) { id, design -> ExplorerHomeBody(id, design) }

private val explorerDevicePane = ScreenshotPane(
    id = ID_EXPLORER_DEVICE,
    type = Workspace.Type.EXPLORER,
) { id, design -> ExplorerDeviceBody(id, design) }

private val explorerDirectoryPane = ScreenshotPane(
    id = ID_EXPLORER_DIRECTORY,
    type = Workspace.Type.EXPLORER,
) { id, design -> ExplorerDirectoryBody(id, design) }

/**
 * Built once at class init, not per composition: [MockDataProvider.createMockOperationsState] mints
 * a fresh operation id on every call, which would make the `remember` key in
 * [ExplorerDirectoryBody] change on each recomposition.
 */
private val runningCopy = OperationsDisplayState(
    // createMockOperationsState's first running entry is a delete; a delete at 50/100 sitting next
    // to three selected files is an alarming thing to put in a store listing. A copy is not.
    operations = listOf(MockDataProvider.createMockRunningOperation()),
)

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

@Composable
private fun MenuEntry(text: String, icon: ImageVector, contentColor: Color? = null) = DropdownMenuItem(
    text = { Text(text) },
    onClick = {},
    leadingIcon = { Icon(imageVector = icon, contentDescription = null) },
    colors = if (contentColor != null) {
        MenuDefaults.itemColors(textColor = contentColor, leadingIconColor = contentColor)
    } else {
        MenuDefaults.itemColors()
    },
)

/**
 * The Butler button's menu, opened.
 *
 * A real `DropdownMenu` cannot be used here: its content lives in a separately attached popup
 * composition, and layoutlib paints the popup's surface but leaves that subtree undrawn in a
 * single-pass render, which yields an empty white slab. So the same entries — same composables,
 * same strings, same icons — are composed inline on a menu surface instead, placed where the real
 * menu drops down from the toolbar card's cutout button (top-aligned, 48dp tall, inset from the
 * pane edge).
 */
@Composable
private fun ExpandedWorkspaceButtonMenu(modifier: Modifier = Modifier) {
    Surface(
        modifier = modifier.sizeIn(minWidth = 112.dp, maxWidth = 280.dp),
        shape = MenuDefaults.shape,
        color = MenuDefaults.containerColor,
        tonalElevation = MenuDefaults.TonalElevation,
        shadowElevation = MenuDefaults.ShadowElevation,
    ) {
        Column(
            modifier = Modifier
                .width(IntrinsicSize.Max)
                .padding(vertical = 8.dp),
        ) {
            MenuEntry(
                text = stringResource(WorkspaceR.string.workspace_button_menu_new_tab_action),
                icon = Icons.TwoTone.Add,
                contentColor = Color(0xFF4CAF50),
            )

            MenuCategoryHeader(text = stringResource(WorkspaceR.string.workspace_button_menu_category_recent))
            listOf(Workspace.Type.EXPLORER, Workspace.Type.SEARCHER, Workspace.Type.EDITOR).forEach { type ->
                val item = quickCreateItem(type)
                MenuEntry(
                    text = stringResource(
                        WorkspaceR.string.workspace_button_menu_new_workspace_format,
                        item.title.asComposable(),
                    ),
                    icon = item.icon,
                )
            }

            MenuCategoryHeader(text = stringResource(WorkspaceR.string.workspace_button_menu_category_other))
            MenuEntry(
                text = stringResource(WorkspaceR.string.workspace_button_menu_manager_action),
                icon = Icons.TwoTone.Workspaces,
            )
            MenuEntry(
                text = stringResource(WorkspaceR.string.workspace_button_menu_settings_action),
                icon = Icons.TwoTone.Settings,
            )

            MenuCategoryHeader(text = stringResource(WorkspaceR.string.workspace_button_menu_category_current_tab))
            LongClickableDropdownMenuItem(
                text = stringResource(WorkspaceR.string.workspace_button_menu_close_current_action),
                onClick = {},
                onLongClick = {},
                contentColor = MaterialTheme.colorScheme.error,
                leadingIcon = {
                    Icon(
                        imageVector = Icons.TwoTone.Close,
                        contentDescription = null,
                    )
                },
            )
        }
    }
}

/**
 * A form factor this screen is not rendered on.
 *
 * The set of shots per form factor is decided in `PlayStoreScreenshots.kt`, not here, and the two
 * have to agree: the phone set drops the explorer home shot, so it has no variant to render.
 * Failing loudly beats rendering a shot nobody asked for and letting `copy_screenshots.sh` reject
 * it later on a count mismatch.
 */
private fun noVariant(formFactor: ScreenshotFormFactor): Nothing =
    error("No $formFactor variant for this screen - PlayStoreScreenshots.kt should not ask for one")

/** The theme the dark shots use, so the carousel is not eight light frames in a row. */
private val DarkScreenshotTheme = ThemeState(mode = ThemeMode.DARK, style = ThemeStyle.DEFAULT)

@Composable
internal fun ExplorerHomeContent(formFactor: ScreenshotFormFactor) = ScreenshotPreviewWrapper {
    when (formFactor) {
        // Home next to the device location, where the storages live. Two EXPLORER panes side by
        // side: the proof that pane content is dispatched by id.
        ScreenshotFormFactor.SEVEN -> ScreenshotPaneFrame(listOf(explorerHomePane, explorerDevicePane))
        ScreenshotFormFactor.TEN -> ScreenshotPaneFrame(listOf(explorerHomePane, explorerDevicePane, searcherPane))
        ScreenshotFormFactor.PHONE -> noVariant(formFactor)
    }
}

@Composable
internal fun ExplorerDirectoryContent(formFactor: ScreenshotFormFactor) = ScreenshotPreviewWrapper {
    when (formFactor) {
        // Selected rows put the page into selection mode, which is what brings up the checkboxes
        // and the contextual action bar - the file management story in one frame. The running copy
        // rides along here rather than on the hero, whose stacked panes are only ~365dp tall.
        ScreenshotFormFactor.PHONE -> ExplorerDirectoryBody(
            id = ID_EXPLORER_DIRECTORY,
            design = WorkspaceDesign(),
            selectedIndices = setOf(3, 4, 5),
            operations = runningCopy,
        )
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
internal fun EditorViewContent(formFactor: ScreenshotFormFactor) = ScreenshotPreviewWrapper(
    theme = DarkScreenshotTheme,
) {
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
        // The quick-create dropdown rides along here: the phone set no longer has an explorer home
        // shot to host it, and creating workspaces is what this screen is about anyway.
        ScreenshotFormFactor.PHONE -> Box(modifier = Modifier.fillMaxSize()) {
            TemplatesPickerBody(ID_TEMPLATES, WorkspaceDesign())
            ExpandedWorkspaceButtonMenu(
                modifier = Modifier
                    .align(Alignment.TopEnd)
                    .padding(top = ScreenshotStatusBarHeight + 54.dp, end = 16.dp),
            )
        }
        ScreenshotFormFactor.SEVEN -> ScreenshotPaneFrame(listOf(templatesPane, explorerDirectoryPane))
        ScreenshotFormFactor.TEN -> ScreenshotPaneFrame(listOf(templatesPane, explorerDirectoryPane, editorPane))
    }
}

/**
 * Open tabs the multi-pane shot's rail lists without giving them a pane.
 *
 * The tablet shots use all of these. The phone hero uses [phoneRailExtras] instead: six tabs plus
 * the create button overflow its rail and the last entries lose their labels.
 */
private val multiPaneRailExtras = listOf(
    Workspace.Info(
        id = ID_SEARCHER,
        type = Workspace.Type.SEARCHER,
        title = Workspace.Type.SEARCHER.label,
        lifecycleState = Workspace.LifecycleState.Ready,
    ),
    Workspace.Info(
        id = ID_APPS,
        type = Workspace.Type.APPS,
        title = Workspace.Type.APPS.label,
        lifecycleState = Workspace.LifecycleState.Ready,
    ),
    Workspace.Info(
        id = ID_TEMPLATES,
        type = Workspace.Type.TEMPLATES,
        title = Workspace.Type.TEMPLATES.label,
        lifecycleState = Workspace.LifecycleState.Ready,
    ),
    Workspace.Info(
        id = ID_EXPLORER_SDCARD,
        type = Workspace.Type.EXPLORER,
        title = Workspace.Type.EXPLORER.label,
        lifecycleState = Workspace.LifecycleState.Paused(),
    ),
)

/** The two the phone rail has room for: still more tabs open than panes shown, which is the point. */
private val phoneRailExtras = multiPaneRailExtras.take(2)

@Composable
internal fun MultiPaneContent(formFactor: ScreenshotFormFactor) = ScreenshotPreviewWrapper {
    when (formFactor) {
        // The lead phone shot, forced to two stacked panes. DUAL_HORIZONTAL splits with a
        // horizontal divider (a Column); DUAL_VERTICAL would put them side by side, which needs
        // width a 411dp phone does not have - the info chips and the editor's line-1 chip get cut.
        ScreenshotFormFactor.PHONE -> ScreenshotPaneFrame(
            panes = listOf(explorerDirectoryPane, editorPane),
            layout = WorkspaceDesign.Layout.DUAL_HORIZONTAL,
            railExtras = phoneRailExtras,
        )
        ScreenshotFormFactor.SEVEN, ScreenshotFormFactor.TEN -> ScreenshotPaneFrame(
            panes = listOf(explorerHomePane, searcherPane, editorPane, appsPane),
            layout = WorkspaceDesign.Layout.QUAD_GRID,
        )
    }
}

@Composable
internal fun WorkspaceManagerContent(formFactor: ScreenshotFormFactor) = ScreenshotPreviewWrapper(
    theme = DarkScreenshotTheme,
) {
    when (formFactor) {
        // The manager is a full-window screen on every form factor, it is never paneled - so it
        // insets itself through a Scaffold, and a Scaffold reads WindowInsets.systemBars directly,
        // which layoutlib pins to zero. LocalSystemBarInsetsOverride cannot reach it; pad explicitly.
        ScreenshotFormFactor.PHONE, ScreenshotFormFactor.SEVEN, ScreenshotFormFactor.TEN -> Box(
            modifier = Modifier.screenshotSystemBarPadding(),
        ) {
            WorkspaceManagerBody()
        }
    }
}

// IDE Previews
//
// One per rendered shot, named with the position that shot has in its own fastlane set - the phone
// set is a different list to the tablet ones. No showSystemUi: the screenshot renderer ignores it
// (neither a navigation=/cutout= spec nor a parent= device makes layoutlib paint the bars), while
// the IDE panel honours it, which would draw a second set on top of ScreenshotSystemBars.

@Preview(name = "Phone 1 - Multi Pane", locale = "en", device = DS_PHONE)
@Composable
private fun PreviewMultiPanePhone() = MultiPaneContent(ScreenshotFormFactor.PHONE)

@Preview(name = "Phone 2 - Explorer Directory", locale = "en", device = DS_PHONE)
@Composable
private fun PreviewExplorerDirectoryPhone() = ExplorerDirectoryContent(ScreenshotFormFactor.PHONE)

@Preview(name = "Phone 3 - Searcher Results", locale = "en", device = DS_PHONE)
@Composable
private fun PreviewSearcherResultsPhone() = SearcherResultsContent(ScreenshotFormFactor.PHONE)

@Preview(name = "Phone 4 - Editor", locale = "en", device = DS_PHONE)
@Composable
private fun PreviewEditorViewPhone() = EditorViewContent(ScreenshotFormFactor.PHONE)

@Preview(name = "Phone 5 - Apps Manager", locale = "en", device = DS_PHONE)
@Composable
private fun PreviewAppsManagerPhone() = AppsManagerContent(ScreenshotFormFactor.PHONE)

@Preview(name = "Phone 6 - Workspace Manager", locale = "en", device = DS_PHONE)
@Composable
private fun PreviewWorkspaceManagerPhone() = WorkspaceManagerContent(ScreenshotFormFactor.PHONE)

@Preview(name = "Phone 7 - Templates Picker", locale = "en", device = DS_PHONE)
@Composable
private fun PreviewTemplatesPickerPhone() = TemplatesPickerContent(ScreenshotFormFactor.PHONE)

@Preview(name = "Seven 1 - Explorer Directory", locale = "en", device = DS_SEVEN)
@Composable
private fun PreviewExplorerDirectorySeven() = ExplorerDirectoryContent(ScreenshotFormFactor.SEVEN)

@Preview(name = "Seven 2 - Explorer Home", locale = "en", device = DS_SEVEN)
@Composable
private fun PreviewExplorerHomeSeven() = ExplorerHomeContent(ScreenshotFormFactor.SEVEN)

@Preview(name = "Seven 3 - Searcher Results", locale = "en", device = DS_SEVEN)
@Composable
private fun PreviewSearcherResultsSeven() = SearcherResultsContent(ScreenshotFormFactor.SEVEN)

@Preview(name = "Seven 4 - Editor", locale = "en", device = DS_SEVEN)
@Composable
private fun PreviewEditorViewSeven() = EditorViewContent(ScreenshotFormFactor.SEVEN)

@Preview(name = "Seven 5 - Apps Manager", locale = "en", device = DS_SEVEN)
@Composable
private fun PreviewAppsManagerSeven() = AppsManagerContent(ScreenshotFormFactor.SEVEN)

@Preview(name = "Seven 6 - Workspace Manager", locale = "en", device = DS_SEVEN)
@Composable
private fun PreviewWorkspaceManagerSeven() = WorkspaceManagerContent(ScreenshotFormFactor.SEVEN)

@Preview(name = "Seven 7 - Multi Pane", locale = "en", device = DS_SEVEN)
@Composable
private fun PreviewMultiPaneSeven() = MultiPaneContent(ScreenshotFormFactor.SEVEN)

@Preview(name = "Seven 8 - Templates Picker", locale = "en", device = DS_SEVEN)
@Composable
private fun PreviewTemplatesPickerSeven() = TemplatesPickerContent(ScreenshotFormFactor.SEVEN)

@Preview(name = "Ten 1 - Explorer Directory", locale = "en", device = DS_TEN)
@Composable
private fun PreviewExplorerDirectoryTen() = ExplorerDirectoryContent(ScreenshotFormFactor.TEN)

@Preview(name = "Ten 2 - Explorer Home", locale = "en", device = DS_TEN)
@Composable
private fun PreviewExplorerHomeTen() = ExplorerHomeContent(ScreenshotFormFactor.TEN)

@Preview(name = "Ten 3 - Searcher Results", locale = "en", device = DS_TEN)
@Composable
private fun PreviewSearcherResultsTen() = SearcherResultsContent(ScreenshotFormFactor.TEN)

@Preview(name = "Ten 4 - Editor", locale = "en", device = DS_TEN)
@Composable
private fun PreviewEditorViewTen() = EditorViewContent(ScreenshotFormFactor.TEN)

@Preview(name = "Ten 5 - Apps Manager", locale = "en", device = DS_TEN)
@Composable
private fun PreviewAppsManagerTen() = AppsManagerContent(ScreenshotFormFactor.TEN)

@Preview(name = "Ten 6 - Workspace Manager", locale = "en", device = DS_TEN)
@Composable
private fun PreviewWorkspaceManagerTen() = WorkspaceManagerContent(ScreenshotFormFactor.TEN)

@Preview(name = "Ten 7 - Multi Pane", locale = "en", device = DS_TEN)
@Composable
private fun PreviewMultiPaneTen() = MultiPaneContent(ScreenshotFormFactor.TEN)

@Preview(name = "Ten 8 - Templates Picker", locale = "en", device = DS_TEN)
@Composable
private fun PreviewTemplatesPickerTen() = TemplatesPickerContent(ScreenshotFormFactor.TEN)
