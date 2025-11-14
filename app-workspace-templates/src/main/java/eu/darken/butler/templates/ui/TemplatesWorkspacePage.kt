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
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.twotone.Add
import androidx.compose.material.icons.twotone.Settings
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ElevatedCard
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import eu.darken.butler.common.Slogans
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
import eu.darken.butler.workspace.core.icon
import eu.darken.butler.workspace.ui.WorkspacePanelMode
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
) {
    ErrorEventHandler(vm)

    val state by waitForState(vm.state)
    log(vm.tag) { "Compose state: $state" }

    state?.let { state ->
        TemplatesWorkspacePage(
            workspaceId = id,
            design = design,
            state = state,
            onNavToSettings = { vm.navTo(Nav.Main.settings()) },
            onCreateWorkspace = { vm.createWorkspace(it) },
        )
    }
}

@Composable
fun TemplatesWorkspacePage(
    workspaceId: Workspace.Id,
    design: WorkspaceDesign = WorkspaceDesign(),
    state: TemplatesWorkspaceViewModel.State,
    onNavToSettings: () -> Unit,
    onCreateWorkspace: (WorkspaceAction.Create) -> Unit = {},
) {
    val randomSlogan = remember { Slogans.random }

    Box(modifier = Modifier.fillMaxSize()) {
        Column(modifier = Modifier.fillMaxSize()) {
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(horizontal = 24.dp)
                    .padding(top = 8.dp),
                horizontalAlignment = Alignment.Start,
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = stringResource(R.string.workspace_templates_choose_title),
                        style = MaterialTheme.typography.headlineMedium,
                        color = MaterialTheme.colorScheme.onSurface,
                        modifier = Modifier.padding(bottom = 4.dp)
                    )
                    Text(
                        text = stringResource(R.string.workspace_templates_choose_subtitle),
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.7f),
                        modifier = Modifier.padding(bottom = 12.dp)
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
                                    onCreateWorkspace(
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
                        .padding(vertical = 16.dp),
                    colors = CardDefaults.cardColors(
                        containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f)
                    )
                ) {
                    Row(
                        modifier = Modifier
                            .clickable { onNavToSettings() }
                            .padding(horizontal = 16.dp, vertical = 8.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(12.dp)
                    ) {
                        ButlerIcon(
                            modifier = Modifier.size(40.dp)
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
                                style = MaterialTheme.typography.labelMedium,
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

@Preview2
@Composable
private fun TemplatesWorkspacePagePreview() {
    PreviewWrapper {
        val workspaceId = Workspace.Id()
        TemplatesWorkspacePage(
            workspaceId = workspaceId,
            state = TemplatesWorkspaceViewModel.State(
                id = workspaceId,
                templates = listOf(
                    ExplorerWorkspaceTemplate(),
                    SearcherWorkspaceTemplate(),
                    EditorWorkspaceTemplate(),
                ),
                isUpgraded = true,
                panelMode = WorkspacePanelMode.AUTO,
                versionDescription = "1.0.0-preview",
            ),
            onNavToSettings = {},
        )
    }
}
