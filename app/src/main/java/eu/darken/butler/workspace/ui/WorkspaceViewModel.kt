package eu.darken.butler.workspace.ui

import dagger.hilt.android.lifecycle.HiltViewModel
import eu.darken.butler.common.coroutine.DispatcherProvider
import eu.darken.butler.common.debug.logging.logTag
import eu.darken.butler.common.navigation.NavigationController
import eu.darken.butler.common.ui.ViewModel4
import eu.darken.butler.workspace.core.Workspace
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.combine
import javax.inject.Inject

@HiltViewModel
class WorkspaceViewModel
@Inject
constructor(
    dispatchers: DispatcherProvider,
    navCtrl: NavigationController,
) : ViewModel4(dispatchers, logTag("Workspace", "ViewModel"), navCtrl) {

    private val _tabs = MutableStateFlow(emptyList<Workspace.Tab>())

    private val _selectedTabId = MutableStateFlow<Workspace.Id?>(null)

    val state =
        combine(_tabs, _selectedTabId) { tabs, selectedTabId ->
            State(tabs = tabs, selectedTabId = selectedTabId)
        }
            .asStateFlow()

    fun addTab() {
        val currentTabs = _tabs.value.toMutableList()
        val newTab = Workspace.WorkspaceTab(title = "New Tab ${currentTabs.size + 1}")
        currentTabs.add(newTab)
        _tabs.value = currentTabs
        _selectedTabId.value = newTab.id
    }

    fun addTabWithTitle(title: String) {
        val currentTabs = _tabs.value.toMutableList()
        val newTab = Workspace.WorkspaceTab(title = title)
        currentTabs.add(newTab)
        _tabs.value = currentTabs
        _selectedTabId.value = newTab.id
    }

    fun closeTab(tabId: Workspace.Id) {
        val currentTabs = _tabs.value.toMutableList()
        currentTabs.removeAll { it.id == tabId }

        _tabs.value = currentTabs
        if (currentTabs.isNotEmpty()) {
            if (_selectedTabId.value == tabId) {
                _selectedTabId.value = currentTabs.first().id
            }
        } else {
            _selectedTabId.value = Workspace.Id()
        }
    }

    fun selectTab(tabId: Workspace.Id) {
        _selectedTabId.value = tabId
    }

    fun transformTab(tabId: Workspace.Id, newTitle: String) {
        val currentTabs = _tabs.value.toMutableList()
        val tabIndex = currentTabs.indexOfFirst { it.id == tabId }
        if (tabIndex != -1) {
            val oldTab = currentTabs[tabIndex]
            currentTabs[tabIndex] = Workspace.WorkspaceTab(id = oldTab.id, title = newTitle)
            _tabs.value = currentTabs
        }
    }

    data class State(
        val tabs: List<Workspace.Tab> = emptyList(),
        val selectedTabId: Workspace.Id? = null
    )
}
