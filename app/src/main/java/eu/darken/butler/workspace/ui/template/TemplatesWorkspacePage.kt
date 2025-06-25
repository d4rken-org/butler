package eu.darken.butler.workspace.ui.template

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ElevatedCard
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import eu.darken.butler.R
import eu.darken.butler.common.BuildConfigWrap
import eu.darken.butler.common.Slogans
import eu.darken.butler.common.ca.toCaString
import eu.darken.butler.common.compose.ColoredTitleText
import eu.darken.butler.common.compose.Preview2
import eu.darken.butler.common.compose.PreviewWrapper
import eu.darken.butler.common.compose.asComposable
import eu.darken.butler.common.debug.logging.log
import eu.darken.butler.common.error.ErrorEventHandler
import eu.darken.butler.common.ui.waitForState
import eu.darken.butler.editor.ui.EditorWorkspaceTemplate
import eu.darken.butler.explorer.ui.ExplorerWorkspaceTemplate
import eu.darken.butler.main.ui.AppNav
import eu.darken.butler.searcher.ui.SearcherWorkspaceTemplate
import eu.darken.butler.workspace.core.Workspace
import eu.darken.butler.workspace.ui.WorkspaceButtonSpacer
import eu.darken.butler.workspace.ui.TabAction
import eu.darken.butler.workspace.ui.WorkspaceTab

@Composable
fun TemplatesWorkspacePageHost(
    id: Workspace.Id,
    onTabAction: (TabAction) -> Unit,
    vm: TemplatesWorkspaceViewModel = hiltViewModel(
        key = id.longTag,
        creationCallback = { factory: TemplatesWorkspaceViewModel.Factory -> factory.create(id = id) }
    ),
) {
    ErrorEventHandler(vm)

    val state by waitForState(vm.state)
    log(vm.tag) { "Compose state: $state" }
    state?.let { state ->
        TemplatesWorkspacePage(
            state = state,
            onTabAction = onTabAction,
            onNavToSettings = { vm.navTo(AppNav.Main.Settings) },
        )
    }
}

@Composable
fun TemplatesWorkspacePage(
    state: TemplatesWorkspaceViewModel.State,
    onTabAction: (TabAction) -> Unit,
    onNavToSettings: () -> Unit,
) {
    val randomSlogan = remember { Slogans.random }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(24.dp),
        horizontalAlignment = Alignment.Start,
        verticalArrangement = Arrangement.SpaceBetween
    ) {
        Column {
            // Workspace tabs section - always visible
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.Top
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = "Open Workspaces",
                        style = MaterialTheme.typography.titleMedium,
                        color = MaterialTheme.colorScheme.onSurface,
                        modifier = Modifier.padding(bottom = 8.dp)
                    )
                    
                    WorkspaceTabsSection(
                        tabs = state.workspaceTabs,
                        selectedTabId = state.selectedTabId,
                        onTabAction = onTabAction,
                        modifier = Modifier.padding(bottom = 24.dp)
                    )
                }
                
                WorkspaceButtonSpacer()
            }
            
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
                            onTabAction(
                                TabAction.Create(
                                    type = template.type,
                                    arguments = template.arguments,
                                    replace = state.id
                                )
                            )
                        })
                }
            }
        }

        Spacer(modifier = Modifier.weight(1f))

        Card(
            modifier = Modifier
                .fillMaxWidth()
                .padding(top = 16.dp), colors = CardDefaults.cardColors(
                containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f)
            )
        ) {
            Row(
                modifier = Modifier
                    .clickable { onNavToSettings() }
                    .padding(16.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(16.dp)
            ) {
                Image(
                    painter = painterResource(R.drawable.mascot),
                    contentDescription = null,
                    modifier = Modifier.size(48.dp)
                )

                Column(modifier = Modifier.weight(1f)) {
                    if (state.isUpgraded) {
                        ColoredTitleText(
                            fullTitle = stringResource(R.string.app_name_upgraded),
                            postfix = stringResource(R.string.app_name_upgrade_postfix),
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
                        text = BuildConfigWrap.VERSION_DESCRIPTION_SHORT,
                        style = MaterialTheme.typography.labelSmall,
                        modifier = Modifier.padding(top = 2.dp),
                        color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f),
                    )
                }

                Icon(
                    imageVector = Icons.Default.Settings,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.7f)
                )
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
                imageVector = Icons.Default.Add,
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
private fun WorkspaceTabsSection(
    tabs: List<WorkspaceTab>,
    selectedTabId: Workspace.Id,
    onTabAction: (TabAction) -> Unit,
    modifier: Modifier = Modifier
) {
    LazyRow(
        modifier = modifier,
        horizontalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        items(tabs) { tab ->
            WorkspaceTabItem(
                tab = tab,
                isSelected = tab.id == selectedTabId,
                onSelect = { onTabAction(TabAction.Select(tab.id)) },
                onClose = if (tabs.size > 1) {
                    { onTabAction(TabAction.Close(tab.id)) }
                } else null
            )
        }
    }
}

@Composable
private fun WorkspaceTabItem(
    tab: WorkspaceTab,
    isSelected: Boolean,
    onSelect: () -> Unit,
    onClose: (() -> Unit)? = null
) {
    Card(
        shape = RoundedCornerShape(8.dp),
        colors = CardDefaults.cardColors(
            containerColor = if (isSelected) {
                MaterialTheme.colorScheme.primaryContainer
            } else {
                MaterialTheme.colorScheme.surface
            }
        ),
        modifier = Modifier.clickable { onSelect() }
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(
                text = tab.title.asComposable(),
                style = MaterialTheme.typography.bodyMedium,
                color = if (isSelected) {
                    MaterialTheme.colorScheme.onPrimaryContainer
                } else {
                    MaterialTheme.colorScheme.onSurface
                },
                modifier = if (onClose != null) {
                    Modifier.padding(end = 8.dp)
                } else {
                    Modifier
                }
            )
            
            if (onClose != null) {
                IconButton(
                    onClick = onClose,
                    modifier = Modifier.size(20.dp)
                ) {
                    Icon(
                        imageVector = Icons.Default.Close,
                        contentDescription = "Close workspace",
                        tint = if (isSelected) {
                            MaterialTheme.colorScheme.onPrimaryContainer
                        } else {
                            MaterialTheme.colorScheme.onSurface
                        },
                        modifier = Modifier.size(14.dp)
                    )
                }
            }
        }
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
                        title = R.string.explorer_title.toCaString(),
                    ),
                ),
                selectedTabId = workspaceId,
                isUpgraded = true,
            ),
            onTabAction = {},
            onNavToSettings = {},
        )
    }
}
