package eu.darken.butler.templates.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.twotone.Add
import androidx.compose.material.icons.twotone.Apps
import androidx.compose.material.icons.twotone.Close
import androidx.compose.material.icons.twotone.Edit
import androidx.compose.material.icons.twotone.Folder
import androidx.compose.material.icons.twotone.Search
import androidx.compose.material.icons.twotone.Settings
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ElevatedCard
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import eu.darken.butler.common.Slogans
import eu.darken.butler.common.ca.caString
import eu.darken.butler.common.ca.toCaString
import eu.darken.butler.common.compose.ButlerIcon
import eu.darken.butler.common.compose.ColoredTitleText
import eu.darken.butler.common.compose.Preview2
import eu.darken.butler.common.compose.PreviewWrapper
import eu.darken.butler.common.compose.asComposable
import eu.darken.butler.common.debug.logging.log
import eu.darken.butler.common.error.ErrorEventHandler
import eu.darken.butler.common.navigation.Nav
import eu.darken.butler.common.navigation.settings
import eu.darken.butler.common.ui.waitForState
import eu.darken.butler.editor.ui.EditorWorkspaceTemplate
import eu.darken.butler.explorer.ui.ExplorerWorkspaceTemplate
import eu.darken.butler.searcher.ui.search.SearcherWorkspaceTemplate
import eu.darken.butler.templates.R
import eu.darken.butler.workspace.core.Workspace
import eu.darken.butler.workspace.core.WorkspaceAction
import eu.darken.butler.workspace.ui.WorkspacePanelMode
import eu.darken.butler.workspace.ui.manager.WorkspaceButton
import eu.darken.butler.workspace.ui.manager.WorkspaceButtonViewModel
import eu.darken.butler.workspace.ui.manager.WorkspaceDesign
import eu.darken.butler.workspace.ui.template.WorkspaceTemplate

@Composable
fun TemplatesWorkspacePageHost(
    id: Workspace.Id,
    design: WorkspaceDesign,
    vm: TemplatesWorkspaceViewModel = hiltViewModel(
        key = id.longTag,
        creationCallback = { factory: TemplatesWorkspaceViewModel.Factory -> factory.create(id = id) }
    ),
    workspaceButtonVm: WorkspaceButtonViewModel = hiltViewModel(),
) {
    ErrorEventHandler(vm)

    val workspaceButtonState by workspaceButtonVm.state.collectAsState(null)

    val state by waitForState(vm.state)
    log(vm.tag) { "Compose state: $state" }

    state?.let { state ->
        TemplatesWorkspacePage(
            design = design,
            state = state,
            onNavToSettings = { vm.navTo(Nav.Main.settings()) },
            workspaceButtonState = workspaceButtonState,
            onWorkspaceAction = workspaceButtonVm::onWorkspaceAction,
            onNavToWorkspaceManager = workspaceButtonVm::onNavToWorkspaceManager,
        )
    }
}

@Composable
fun TemplatesWorkspacePage(
    design: WorkspaceDesign = WorkspaceDesign(),
    state: TemplatesWorkspaceViewModel.State,
    onNavToSettings: () -> Unit,
    workspaceButtonState: WorkspaceButtonViewModel.State?,
    onWorkspaceAction: (WorkspaceAction) -> Unit,
    onNavToWorkspaceManager: () -> Unit,
) {
    val randomSlogan = remember { Slogans.random }

    Box(modifier = Modifier.fillMaxSize()) {
        Column(modifier = Modifier.fillMaxSize()) {
            if (state.workspaceTabs.isNotEmpty() && design.isSingle) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 16.dp, vertical = 16.dp), // Match workspace button padding
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Column(
                        modifier = Modifier.weight(1f),
                        verticalArrangement = Arrangement.spacedBy(2.dp)
                    ) {
                        Text(
                            text = "Open workspaces",
                            style = MaterialTheme.typography.labelMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.padding(start = 4.dp),
                        )

                        CompactTabPillsRow(
                            tabs = state.workspaceTabs,
                            selectedTabId = state.selectedTabId,
                            onWorkspaceAction = onWorkspaceAction,
                            onNavToWorkspaceManager = onNavToWorkspaceManager
                        )
                    }

                    WorkspaceButton(
                        state = workspaceButtonState,
                        onAction = onWorkspaceAction,
                        onNavToWorkspaceManager = onNavToWorkspaceManager,
                    )
                }
            }

            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(horizontal = 24.dp)
                    .padding(top = 16.dp),
                horizontalAlignment = Alignment.Start,
                verticalArrangement = Arrangement.SpaceBetween
            ) {
                Column {
                    Text(
                        text = stringResource(R.string.workspace_templates_choose_title),
                        style = MaterialTheme.typography.headlineMedium,
                        color = MaterialTheme.colorScheme.onSurface,
                        modifier = Modifier.padding(bottom = 8.dp)
                    )
                    Text(
                        text = stringResource(R.string.workspace_templates_choose_subtitle),
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.7f),
                        modifier = Modifier.padding(bottom = 24.dp)
                    )

                    LazyColumn(
                        verticalArrangement = Arrangement.spacedBy(12.dp),
                        horizontalAlignment = Alignment.Start
                    ) {
                        items(state.templates.size) { index ->
                            val template = state.templates[index]
                            val isFirstItem = index == 0

                            TemplateCard(
                                template = template,
                                isFirstItem = isFirstItem,
                                onClick = {
                                    onWorkspaceAction(
                                        WorkspaceAction.Create(
                                            type = template.type,
                                            arguments = template.arguments,
                                            replace = state.id
                                        )
                                    )
                                })
                        }
                    }
                }

                Card(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(top = 16.dp)
                        .padding(bottom = 16.dp), colors = CardDefaults.cardColors(
                        containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f)
                    )
                ) {
                    Row(
                        modifier = Modifier
                            .clickable { onNavToSettings() }
                            .padding(12.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(12.dp)
                    ) {
                        ButlerIcon(
                            size = 40.dp
                        )

                        Column(modifier = Modifier.weight(1f)) {
                            if (state.isUpgraded) {
                                ColoredTitleText(
                                    fullTitle = stringResource(eu.darken.butler.common.R.string.app_name_upgraded),
                                    postfix = stringResource(eu.darken.butler.common.R.string.app_name_upgrade_postfix),
                                    style = MaterialTheme.typography.titleMedium,
                                )
                            } else {
                                Text(
                                    text = stringResource(eu.darken.butler.common.R.string.app_name),
                                    style = MaterialTheme.typography.titleMedium,
                                    color = MaterialTheme.colorScheme.primary,
                                )
                            }
                            Text(
                                text = randomSlogan.get(LocalContext.current),
                                style = MaterialTheme.typography.bodyMedium,
                                color = MaterialTheme.colorScheme.onSurface
                            )
                            Text(
                                text = state.versionDescription,
                                style = MaterialTheme.typography.labelSmall,
                                modifier = Modifier.padding(top = 2.dp),
                                color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f),
                            )
                        }

                        Icon(
                            imageVector = Icons.TwoTone.Settings,
                            contentDescription = null,
                            tint = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.7f)
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun TemplateCard(
    template: WorkspaceTemplate,
    isFirstItem: Boolean,
    onClick: () -> Unit
) {
    val cardContent = @Composable {
        Row(
            modifier = Modifier.padding(16.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Box(
                modifier = Modifier
                    .size(40.dp)
                    .background(
                        color = if (isFirstItem) {
                            MaterialTheme.colorScheme.primary.copy(alpha = 0.2f)
                        } else {
                            MaterialTheme.colorScheme.surfaceVariant
                        },
                        shape = CircleShape
                    ),
                contentAlignment = Alignment.Center
            ) {
                Icon(
                    imageVector = template.icon,
                    contentDescription = null,
                    tint = if (isFirstItem) {
                        MaterialTheme.colorScheme.onPrimaryContainer
                    } else {
                        MaterialTheme.colorScheme.onSurfaceVariant
                    },
                    modifier = Modifier.size(20.dp)
                )
            }

            Column(
                modifier = Modifier
                    .weight(1f)
                    .padding(horizontal = 16.dp)
            ) {
                Text(
                    text = template.title.asComposable(),
                    style = MaterialTheme.typography.titleMedium,
                    color = if (isFirstItem) {
                        MaterialTheme.colorScheme.onPrimaryContainer
                    } else {
                        MaterialTheme.colorScheme.onSurface
                    }
                )
                Text(
                    text = template.subtitle.asComposable(),
                    style = MaterialTheme.typography.bodySmall,
                    color = if (isFirstItem) {
                        MaterialTheme.colorScheme.onPrimaryContainer.copy(alpha = 0.8f)
                    } else {
                        MaterialTheme.colorScheme.onSurface.copy(alpha = 0.7f)
                    },
                    modifier = Modifier.padding(top = 2.dp)
                )
            }

            Icon(
                imageVector = Icons.TwoTone.Add,
                contentDescription = null,
                tint = if (isFirstItem) {
                    MaterialTheme.colorScheme.onPrimaryContainer
                } else {
                    MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f)
                },
                modifier = Modifier.size(20.dp)
            )
        }
    }

    if (isFirstItem) {
        ElevatedCard(
            modifier = Modifier
                .fillMaxWidth()
                .clickable { onClick() },
            colors = CardDefaults.elevatedCardColors(containerColor = MaterialTheme.colorScheme.primaryContainer),
            elevation = CardDefaults.elevatedCardElevation(defaultElevation = 4.dp)
        ) { cardContent() }
    } else {
        Card(
            modifier = Modifier
                .fillMaxWidth()
                .clickable { onClick() },
            colors = CardDefaults.cardColors()
        ) { cardContent() }
    }
}

@Composable
private fun CompactTabPillsRow(
    tabs: List<WorkspaceTab>,
    selectedTabId: Workspace.Id,
    onWorkspaceAction: (WorkspaceAction) -> Unit,
    onNavToWorkspaceManager: () -> Unit,
    modifier: Modifier = Modifier
) {
    val density = LocalDensity.current
    rememberTextMeasurer()
    LocalContext.current
    var availableWidth by remember { mutableStateOf(0) }

    // Simplified tab layout with basic overflow detection
    val tabLayout = remember(tabs, availableWidth, density) {
        if (tabs.isEmpty()) {
            TabLayout(emptyList(), emptyList(), 0)
        } else {
            // Use reasonable default width in dp converted to pixels
            val defaultWidthDp = 128.dp
            val defaultWidth = with(density) { defaultWidthDp.toPx() }
            val spaceBetween = with(density) { 6.dp.toPx() }
            val morePillWidth = with(density) { 40.dp.toPx() }

            // Basic overflow calculation
            if (availableWidth > 0) {
                val maxTabsWithoutOverflow = ((availableWidth + spaceBetween) / (defaultWidth + spaceBetween)).toInt()
                val maxTabsWithOverflow =
                    ((availableWidth - morePillWidth + spaceBetween) / (defaultWidth + spaceBetween)).toInt()

                when {
                    tabs.size <= maxTabsWithoutOverflow -> {
                        // Show all tabs
                        TabLayout(tabs, tabs.map { defaultWidth }, 0)
                    }

                    maxTabsWithOverflow >= 1 -> {
                        // Show some tabs with overflow
                        val visibleTabs = tabs.take(maxTabsWithOverflow)
                        TabLayout(visibleTabs, visibleTabs.map { defaultWidth }, tabs.size - visibleTabs.size)
                    }

                    else -> {
                        // Show at least one tab
                        TabLayout(tabs.take(1), listOf(defaultWidth), tabs.size - 1)
                    }
                }
            } else {
                // No width measurement yet - show all tabs
                TabLayout(tabs, tabs.map { defaultWidth }, 0)
            }
        }
    }

    Row(
        modifier = modifier
            .fillMaxWidth()
            .onSizeChanged { size ->
                availableWidth = size.width
            },
        horizontalArrangement = Arrangement.spacedBy(6.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        tabLayout.visibleTabs.forEachIndexed { index, tab ->
            CompactTabPill(
                tab = tab,
                isSelected = tab.id == selectedTabId,
                onSelect = { /* Tab selection is handled by parent UI */ },
                onClose = { onWorkspaceAction(WorkspaceAction.Close(tab.id)) },
                fixedWidth = if (index < tabLayout.tabWidths.size) {
                    with(density) { tabLayout.tabWidths[index].toDp() }
                } else null
            )
        }

        if (tabLayout.hiddenCount > 0) {
            MoreTabsPill(
                count = tabLayout.hiddenCount,
                onClick = onNavToWorkspaceManager
            )
        }
    }
}

private data class TabLayout(
    val visibleTabs: List<WorkspaceTab>,
    val tabWidths: List<Float>, // Width in pixels
    val hiddenCount: Int
)

private fun getWorkspaceTypeIcon(type: Workspace.Type): ImageVector {
    return when (type) {
        Workspace.Type.EXPLORER -> Icons.TwoTone.Folder
        Workspace.Type.SEARCHER -> Icons.TwoTone.Search
        Workspace.Type.EDITOR -> Icons.TwoTone.Edit
        Workspace.Type.TEMPLATES -> Icons.TwoTone.Apps
    }
}

@Composable
private fun CompactTabPill(
    tab: WorkspaceTab,
    isSelected: Boolean,
    onSelect: () -> Unit,
    onClose: (() -> Unit)? = null,
    fixedWidth: androidx.compose.ui.unit.Dp? = null
) {
    Card(
        shape = RoundedCornerShape(16.dp),
        colors = CardDefaults.cardColors(
            containerColor = if (isSelected) {
                MaterialTheme.colorScheme.primaryContainer
            } else {
                MaterialTheme.colorScheme.surfaceVariant
            }
        ),
        elevation = CardDefaults.cardElevation(
            defaultElevation = if (isSelected) 2.dp else 1.dp
        ),
        modifier = Modifier
            .let { modifier ->
                if (fixedWidth != null) {
                    modifier.width(fixedWidth)
                } else {
                    modifier.widthIn(max = 128.dp)
                }
            }
            .clickable { onSelect() }
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(6.dp)
        ) {
            // Workspace type icon
            Icon(
                imageVector = getWorkspaceTypeIcon(tab.type),
                contentDescription = null,
                modifier = Modifier.size(16.dp),
                tint = if (isSelected) {
                    MaterialTheme.colorScheme.onPrimaryContainer
                } else {
                    MaterialTheme.colorScheme.onSurfaceVariant
                }
            )

            // Tab title with ellipsizing
            Text(
                text = tab.title.asComposable(),
                style = MaterialTheme.typography.bodySmall,
                color = if (isSelected) {
                    MaterialTheme.colorScheme.onPrimaryContainer
                } else {
                    MaterialTheme.colorScheme.onSurfaceVariant
                },
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f)
            )

            // Close button (always show)
            IconButton(
                onClick = { onClose?.invoke() },
                modifier = Modifier.size(16.dp)
            ) {
                Icon(
                    imageVector = Icons.TwoTone.Close,
                    contentDescription = "Close workspace",
                    tint = if (isSelected) {
                        MaterialTheme.colorScheme.onPrimaryContainer
                    } else {
                        MaterialTheme.colorScheme.onSurfaceVariant
                    },
                    modifier = Modifier.size(12.dp)
                )
            }
        }
    }
}

@Composable
private fun MoreTabsPill(count: Int, onClick: () -> Unit) {
    Card(
        shape = RoundedCornerShape(16.dp),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.secondaryContainer.copy(alpha = 0.8f)
        ),
        elevation = CardDefaults.cardElevation(defaultElevation = 1.dp),
        modifier = Modifier.clickable { onClick() }
    ) {
        Text(
            text = "+$count",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSecondaryContainer,
            modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp)
        )
    }
}


@Preview2
@Composable
private fun TemplatesWorkspacePagePreview() {
    PreviewWrapper {
        val workspaceId = Workspace.Id()
        TemplatesWorkspacePage(
            state = TemplatesWorkspaceViewModel.State(
                id = workspaceId,
                templates = listOf(
                    ExplorerWorkspaceTemplate(),
                    SearcherWorkspaceTemplate(),
                    EditorWorkspaceTemplate(),
                ),
                workspaceTabs = listOf(
                    WorkspaceTab(
                        type = Workspace.Type.TEMPLATES,
                        id = workspaceId,
                        title = R.string.workspace_templates_tab_title.toCaString(),
                    ),
                    WorkspaceTab(
                        type = Workspace.Type.EXPLORER,
                        id = Workspace.Id(),
                        title = caString { "Explorer" },
                    ),
                ),
                selectedTabId = workspaceId,
                isUpgraded = true,
                panelMode = WorkspacePanelMode.AUTO,
                versionDescription = "1.0.0-preview",
            ),
            onNavToSettings = {},
            workspaceButtonState = null,
            onWorkspaceAction = {},
            onNavToWorkspaceManager = {},
        )
    }
}
