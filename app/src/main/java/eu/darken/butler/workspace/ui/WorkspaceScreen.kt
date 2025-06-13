package eu.darken.butler.workspace.ui

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
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Settings
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
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import eu.darken.butler.R
import eu.darken.butler.common.ca.toCaString
import eu.darken.butler.common.compose.Preview2
import eu.darken.butler.common.compose.PreviewWrapper
import eu.darken.butler.common.compose.asComposable
import eu.darken.butler.common.error.ErrorEventHandler
import eu.darken.butler.common.ui.waitForState
import eu.darken.butler.main.ui.AppNav
import eu.darken.butler.workspace.core.Workspace
import eu.darken.butler.workspace.ui.sample.DocumentsTabContent
import eu.darken.butler.workspace.ui.sample.DownloadsTabContent
import eu.darken.butler.workspace.ui.sample.HomeTabContent

@Composable
fun WorkspaceScreenHost(vm: WorkspaceViewModel = hiltViewModel()) {
    ErrorEventHandler(vm)

    val state by waitForState(vm.state)

    state?.let { state ->
        WorkspaceScreen(
            state = state,
            onNavToSettings = { vm.goTo(AppNav.Settings) },
            onAddTab = { vm.addTab() },
            onTransformTab = { tabId, title -> vm.transformTab(tabId, title) },
            onCloseTab = { vm.closeTab(it) },
            onSelectTab = { vm.selectTab(it) }
        )
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun WorkspaceScreen(
    state: WorkspaceViewModel.State,
    onNavToSettings: () -> Unit,
    onAddTab: () -> Unit,
    onTransformTab: (Workspace.Id, String) -> Unit,
    onCloseTab: (Workspace.Id) -> Unit,
    onSelectTab: (Workspace.Id) -> Unit,
) {
    Scaffold(
        topBar = {
            TabBar(
                tabs = state.tabs,
                selectedTabId = state.selectedTabId,
                onAddTab = onAddTab,
                onCloseTab = onCloseTab,
                onSelectTab = onSelectTab
            )
        }
    ) { paddingValues ->
        if (state.tabs.isEmpty()) {
            EmptyWorkspaceContent(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(paddingValues),
                onNavToSettings = onNavToSettings,
                onAddTab = onAddTab
            )
        } else {
            TabContent(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(paddingValues),
                tabs = state.tabs,
                selectedTabId = state.selectedTabId,
                onTransformTab = onTransformTab
            )
        }
    }
}

@Composable
private fun TabBar(
    tabs: List<Workspace.Tab>,
    selectedTabId: Workspace.Id?,
    onAddTab: () -> Unit,
    onCloseTab: (Workspace.Id) -> Unit,
    onSelectTab: (Workspace.Id) -> Unit
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
                    onSelect = { onSelectTab(tab.id) },
                    onClose = { onCloseTab(tab.id) }
                )
            }
        }
        if (tabs.isNotEmpty()) {
            IconButton(onClick = onAddTab) {
                Icon(
                    imageVector = Icons.Default.Add,
                    contentDescription = stringResource(R.string.workspace_tab_add)
                )
            }
        }
    }
}

@Composable
private fun TabItem(
    tab: Workspace.Tab,
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
                    contentDescription = stringResource(R.string.workspace_tab_close),
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
    tabs: List<Workspace.Tab>,
    selectedTabId: Workspace.Id?,
    onTransformTab: (Workspace.Id, String) -> Unit
) {
    val selectedTab = tabs.find { it.id == selectedTabId }

    Box(modifier = modifier) {
        when (selectedTab?.type) {
            Workspace.Type.EXPLORER -> {
                when (selectedTab.title.asComposable()) {
                    "Home" -> HomeTabContent()
                    "Documents" -> DocumentsTabContent()
                    "Downloads" -> DownloadsTabContent()
                    else -> HomeTabContent() // Default to home content for explorer type
                }
            }

            Workspace.Type.SEARCH -> {
                // Future implementation for search type
                HomeTabContent() // Placeholder until search is implemented
            }

            Workspace.Type.NEW, null -> {
                NewWorkspacePage(tabId = selectedTab?.id ?: Workspace.Id(), onTransformTab = onTransformTab)
            }
        }
    }
}

@Composable
internal fun EmptyWorkspaceContent(
    modifier: Modifier = Modifier,
    onNavToSettings: () -> Unit,
    onAddTab: () -> Unit
) {
    Column(
        modifier = modifier.padding(24.dp),
        horizontalAlignment = Alignment.Start,
        verticalArrangement = Arrangement.Center
    ) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(16.dp)
        ) {
            Box(
                modifier = Modifier
                    .size(72.dp)
                    .background(
                        color = MaterialTheme.colorScheme.secondaryContainer,
                        shape = RoundedCornerShape(16.dp)
                    ),
                contentAlignment = Alignment.Center
            ) {
                Image(
                    painter = painterResource(R.drawable.mascot),
                    contentDescription = null,
                    modifier = Modifier.size(56.dp)
                )
            }

            Column {
                Text(
                    text = stringResource(eu.darken.butler.common.R.string.app_name),
                    style = MaterialTheme.typography.headlineSmall,
                    color = MaterialTheme.colorScheme.primary
                )
                Text(
                    text = stringResource(eu.darken.butler.common.R.string.app_name_subtitle),
                    style = MaterialTheme.typography.titleMedium,
                    color = MaterialTheme.colorScheme.onSurface
                )
            }
        }
        Spacer(modifier = Modifier.height(32.dp))

        LazyColumn(
            verticalArrangement = Arrangement.spacedBy(16.dp),
            horizontalAlignment = Alignment.Start
        ) {
            item {
                Card(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clickable { onAddTab() },
                    colors =
                        CardDefaults.cardColors(
                            containerColor = MaterialTheme.colorScheme.primaryContainer
                        )
                ) {
                    Row(
                        modifier = Modifier.padding(20.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text(
                            text = stringResource(R.string.workspace_tab_add),
                            style = MaterialTheme.typography.titleMedium,
                            modifier = Modifier.weight(1f)
                        )
                        Icon(imageVector = Icons.Default.Add, contentDescription = null)
                    }
                }
            }

            item {
                Card(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clickable { onNavToSettings() }) {
                    Row(
                        modifier = Modifier.padding(20.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text(
                            text = stringResource(R.string.settings_label),
                            style = MaterialTheme.typography.titleMedium,
                            modifier = Modifier.weight(1f)
                        )
                        Icon(
                            imageVector = Icons.Default.Settings,
                            contentDescription = stringResource(R.string.settings_label)
                        )
                    }
                }
            }
        }
    }
}

@Preview2
@Composable
private fun WorkspaceScreenPreview() {
    PreviewWrapper {
        val tabs = listOf(
            Workspace.WorkspaceTab(title = "New Tab".toCaString(), type = Workspace.Type.NEW),
        )
        WorkspaceScreen(
            state = WorkspaceViewModel.State(tabs = tabs, selectedTabId = tabs.first().id),
            onNavToSettings = {},
            onAddTab = {},
            onTransformTab = { _, _ -> },
            onCloseTab = {},
            onSelectTab = {}
        )
    }
}

@Preview2
@Composable
private fun EmptyWorkspaceScreenPreview() {
    PreviewWrapper {
        WorkspaceScreen(
            state = WorkspaceViewModel.State(tabs = emptyList(), selectedTabId = null),
            onNavToSettings = {},
            onAddTab = {},
            onTransformTab = { _, _ -> },
            onCloseTab = {},
            onSelectTab = {}
        )
    }
}
