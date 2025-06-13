package eu.darken.butler.workspace.ui

import androidx.compose.foundation.Image
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
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
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import eu.darken.butler.R
import eu.darken.butler.common.Slogans
import eu.darken.butler.common.compose.Preview2
import eu.darken.butler.common.compose.PreviewWrapper
import eu.darken.butler.common.compose.SampleContent
import eu.darken.butler.common.compose.asComposable
import eu.darken.butler.common.error.ErrorEventHandler
import eu.darken.butler.common.ui.waitForState
import eu.darken.butler.main.ui.AppNav
import eu.darken.butler.workspace.core.Workspace

@Composable
fun WorkspaceScreenHost(vm: WorkspaceViewModel = hiltViewModel()) {
    ErrorEventHandler(vm)

    val state by waitForState(vm.state)

    state?.let { state ->
        WorkspaceScreen(
            state = state,
            onNavToSettings = { vm.goTo(AppNav.Settings) },
            onAddTab = { vm.addTab() },
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
    onCloseTab: (Workspace.Id) -> Unit,
    onSelectTab: (Workspace.Id) -> Unit,
) {
    Scaffold(
        topBar = {
            Column {
                TopAppBar(
                    title = {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Image(
                                painter = painterResource(R.drawable.mascot),
                                contentDescription = null,
                                modifier = Modifier.size(32.dp)
                            )
                            Spacer(modifier = Modifier.width(8.dp))
                            Text(stringResource(eu.darken.butler.common.R.string.app_name))
                        }
                    },
                    actions = {
                        IconButton(onClick = onNavToSettings) {
                            Icon(
                                imageVector = Icons.Default.Settings,
                                contentDescription =
                                    stringResource(R.string.settings_label)
                            )
                        }
                    }
                )
                TabBar(
                    tabs = state.tabs,
                    selectedTabId = state.selectedTabId,
                    onAddTab = onAddTab,
                    onCloseTab = onCloseTab,
                    onSelectTab = onSelectTab
                )
            }
        }
    ) { paddingValues ->
        if (state.tabs.isEmpty()) {
            EmptyWorkspaceContent(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(paddingValues)
            )
        } else {
            TabContent(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(paddingValues),
                tabs = state.tabs,
                selectedTabId = state.selectedTabId
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
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 8.dp, vertical = 4.dp)
    ) {
        LazyRow(
            modifier = Modifier.weight(1f),
            horizontalArrangement = Arrangement.spacedBy(8.dp)
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
        IconButton(onClick = onAddTab) {
            Icon(
                imageVector = Icons.Default.Add,
                contentDescription = stringResource(R.string.workspace_tab_add)
            )
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
            modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(
                text = tab.title,
                style = MaterialTheme.typography.bodyMedium,
                color =
                    if (isSelected) {
                        MaterialTheme.colorScheme.onPrimaryContainer
                    } else {
                        MaterialTheme.colorScheme.onSurface
                    },
                modifier = Modifier.padding(end = 8.dp)
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
    selectedTabId: Workspace.Id?
) {
    val selectedTab = tabs.find { it.id == selectedTabId }

    Box(modifier = modifier) {
        when (selectedTab?.title) {
            "Home" -> HomeTabContent()
            "Documents" -> DocumentsTabContent()
            "Downloads" -> DownloadsTabContent()
            else -> {
                if (selectedTab?.title?.startsWith("New Tab") == true) {
                    NewTabContent(selectedTab.title)
                } else {
                    EmptyTabContent()
                }
            }
        }
    }
}

@Composable
private fun HomeTabContent() {
    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        verticalArrangement = Arrangement.spacedBy(16.dp),
        contentPadding = PaddingValues(16.dp)
    ) {
        item {
            Text(
                text = "Home",
                style = MaterialTheme.typography.headlineMedium,
                color = MaterialTheme.colorScheme.primary
            )
        }
        item {
            Card {
                Column(modifier = Modifier.padding(16.dp)) {
                    Text(text = "Quick Actions", style = MaterialTheme.typography.titleMedium)
                    Spacer(modifier = Modifier.width(8.dp))
                    Text("Browse files, search, or create new documents")
                }
            }
        }
        items(3) { index -> SampleContent {} }
    }
}

@Composable
private fun DocumentsTabContent() {
    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        verticalArrangement = Arrangement.spacedBy(8.dp),
        contentPadding = PaddingValues(16.dp)
    ) {
        item {
            Text(
                text = "Documents",
                style = MaterialTheme.typography.headlineMedium,
                color = MaterialTheme.colorScheme.primary
            )
        }
        item { Text("Your document files will appear here") }
        items(5) { index ->
            Card {
                Row(
                    modifier = Modifier.padding(16.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) { Text("Document ${index + 1}.pdf") }
            }
        }
    }
}

@Composable
private fun DownloadsTabContent() {
    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        verticalArrangement = Arrangement.spacedBy(8.dp),
        contentPadding = PaddingValues(16.dp)
    ) {
        item {
            Text(
                text = "Downloads",
                style = MaterialTheme.typography.headlineMedium,
                color = MaterialTheme.colorScheme.primary
            )
        }
        item { Text("Your downloaded files will appear here") }
        items(4) { index ->
            Card {
                Row(
                    modifier = Modifier.padding(16.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) { Text("Downloaded file ${index + 1}") }
            }
        }
    }
}

@Composable
private fun NewTabContent(tabTitle: String) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(16.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center
    ) {
        Text(text = tabTitle, style = MaterialTheme.typography.headlineMedium)
        Spacer(modifier = Modifier.width(16.dp))
        Text("This is a new tab. Content can be added here.")
    }
}

@Composable
private fun EmptyTabContent() {
    Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        Text("No content available")
    }
}

@Composable
private fun EmptyWorkspaceContent(modifier: Modifier = Modifier) {
    val randomSlogan = remember { Slogans.random }

    Column(
        modifier = modifier,
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center
    ) {
        Image(
            painter = painterResource(R.drawable.mascot),
            contentDescription = null,
            modifier = Modifier.size(128.dp)
        )
        Spacer(modifier = Modifier.height(24.dp))
        Text(
            text = randomSlogan.asComposable(),
            style = MaterialTheme.typography.headlineSmall,
            textAlign = TextAlign.Center,
            color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.7f)
        )
    }
}

@Preview2
@Composable
private fun WorkspaceScreenPreview() {
    PreviewWrapper {
        val tabs = listOf(
            Workspace.WorkspaceTab(title = "Home"),
            Workspace.WorkspaceTab(title = "Documents"),
            Workspace.WorkspaceTab(title = "Downloads")
        )
        WorkspaceScreen(
            state = WorkspaceViewModel.State(tabs = tabs, selectedTabId = tabs.first().id),
            onNavToSettings = {},
            onAddTab = {},
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
            onCloseTab = {},
            onSelectTab = {}
        )
    }
}
