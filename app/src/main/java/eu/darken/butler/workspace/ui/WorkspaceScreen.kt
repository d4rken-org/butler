package eu.darken.butler.workspace.ui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.twotone.AddCircle
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import eu.darken.butler.R
import eu.darken.butler.common.compose.Preview2
import eu.darken.butler.common.compose.PreviewWrapper
import eu.darken.butler.common.compose.asComposable
import eu.darken.butler.common.debug.logging.log
import eu.darken.butler.common.error.ErrorEventHandler
import eu.darken.butler.common.ui.waitForState
import eu.darken.butler.editor.ui.EditorPage
import eu.darken.butler.explorer.ui.ExplorerPage
import eu.darken.butler.main.ui.AppNav
import eu.darken.butler.searcher.ui.SearchPage
import eu.darken.butler.workspace.core.Workspace
import eu.darken.butler.workspace.core.WorkspaceTab

@Composable
fun WorkspaceScreenHost(vm: WorkspaceViewModel = hiltViewModel()) {
    ErrorEventHandler(vm)

    val state by waitForState(vm.state)
    log(vm.tag) { "Workspace state: $state" }
    state?.let { state ->
        WorkspaceScreen(
            state = state,
            onNavToSettings = { vm.navTo(AppNav.Main.Settings) },
            onTabAction = { vm.modifyTab(it) },
            onUpgradeButler = { vm.upgradeButler() }
        )
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun WorkspaceScreen(
    state: WorkspaceViewModel.State,
    onNavToSettings: () -> Unit,
    onTabAction: (TabAction) -> Unit,
    onUpgradeButler: () -> Unit,
) {
    Scaffold(
        topBar = {
            TabBar(
                tabs = state.tabs,
                selectedTabId = state.selected,
                onTabAction = onTabAction,
            )
        }
    ) { paddingValues ->
        val current = state.current
        if (current != null) {
            TabContent(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(paddingValues),
                selected = current,
                onNavToSettings = onNavToSettings,
                onTabAction = onTabAction,
            )
        } else {
            EmptyWorkspaceContent(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(paddingValues),
                onNavToSettings = onNavToSettings,
                onTabAction = onTabAction,
                showUpgradePrompt = state.showUpgradePrompt,
                onUpgradeButler = onUpgradeButler,
            )
        }
    }
}

@Composable
private fun TabBar(
    tabs: List<WorkspaceTab>,
    selectedTabId: Workspace.Id?,
    onTabAction: (TabAction) -> Unit,
) {
    Row(
        modifier =
            Modifier
                .fillMaxWidth()
                .statusBarsPadding()
                .padding(horizontal = 8.dp, vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        LazyRow(
            modifier = Modifier.weight(1f),
            horizontalArrangement = Arrangement.spacedBy(6.dp)
        ) {
            items(tabs) { tab ->
                TabItem(
                    tab = tab,
                    isSelected = tab.id == selectedTabId,
                    onSelect = { onTabAction(TabAction.Select(tab.id)) },
                    onClose = { onTabAction(TabAction.Close(tab.id)) },
                )
            }
        }
        if (tabs.isNotEmpty()) {
            IconButton(onClick = { onTabAction(TabAction.Create()) }) {
                Icon(
                    imageVector = Icons.TwoTone.AddCircle,
                    contentDescription = stringResource(R.string.workspace_tab_add_action)
                )
            }
        }
    }
}

@Composable
private fun TabItem(
    tab: WorkspaceTab,
    isSelected: Boolean,
    onSelect: () -> Unit,
    onClose: () -> Unit
) {
    Card(
        shape = RoundedCornerShape(8.dp),
        colors =
            CardDefaults.cardColors(
                containerColor =
                    if (isSelected) {
                        MaterialTheme.colorScheme.primaryContainer
                    } else {
                        MaterialTheme.colorScheme.surface
                    }
            ),
        modifier = Modifier.clickable { onSelect() }
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 8.dp, vertical = 6.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(
                text = tab.title.asComposable(),
                style = MaterialTheme.typography.bodyMedium,
                color =
                    if (isSelected) {
                        MaterialTheme.colorScheme.onPrimaryContainer
                    } else {
                        MaterialTheme.colorScheme.onSurface
                    },
                modifier = Modifier.padding(end = 6.dp)
            )
            IconButton(onClick = onClose, modifier = Modifier.size(16.dp)) {
                Icon(
                    imageVector = Icons.Default.Close,
                    contentDescription = stringResource(R.string.workspace_tab_close_action),
                    tint =
                        if (isSelected) {
                            MaterialTheme.colorScheme.onPrimaryContainer
                        } else {
                            MaterialTheme.colorScheme.onSurface
                        },
                    modifier = Modifier.size(12.dp)
                )
            }
        }
    }
}

@Composable
private fun TabContent(
    modifier: Modifier = Modifier,
    selected: WorkspaceTab,
    onTabAction: (TabAction) -> Unit,
    onNavToSettings: () -> Unit,
) {
    Box(modifier = modifier) {
        when (selected.type) {
            Workspace.Type.NEW -> {
                NewWorkspacePage(
                    id = selected.id,
                    onTabAction = onTabAction,
                    onNavToSettings = onNavToSettings
                )
            }

            Workspace.Type.EXPLORER -> {
                ExplorerPage()
            }

            Workspace.Type.SEARCH -> {
                SearchPage()
            }

            Workspace.Type.EDITOR -> {
                EditorPage()
            }
        }
    }
}

@Preview2
@Composable
private fun WorkspaceScreenPreview() {
    PreviewWrapper {
        val tabs = listOf(
            WorkspaceTab(),
            WorkspaceTab(),
        )
        WorkspaceScreen(
            state = WorkspaceViewModel.State(
                tabs = tabs,
                selected = tabs.last().id,
                showUpgradePrompt = true,
            ),
            onNavToSettings = {},
            onTabAction = {},
            onUpgradeButler = {}
        )
    }
}

@Preview2
@Composable
private fun EmptyWorkspaceScreenPreview() {
    PreviewWrapper {
        WorkspaceScreen(
            state = WorkspaceViewModel.State(
                tabs = emptyList(),
                selected = null,
                showUpgradePrompt = true,
            ),
            onNavToSettings = {},
            onTabAction = {},
            onUpgradeButler = {}
        )
    }
}
