package eu.darken.butler.workspace.ui

import dagger.hilt.android.lifecycle.HiltViewModel
import eu.darken.butler.common.coroutine.DispatcherProvider
import eu.darken.butler.common.debug.logging.log
import eu.darken.butler.common.debug.logging.logTag
import eu.darken.butler.common.navigation.NavigationController
import eu.darken.butler.common.ui.ViewModel4
import eu.darken.butler.main.ui.AppNav
import eu.darken.butler.workspace.core.Workspace
import eu.darken.butler.workspace.core.WorkspaceTab
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import javax.inject.Inject

@HiltViewModel
class WorkspaceViewModel
@Inject
constructor(
    dispatchers: DispatcherProvider,
    private val navCtrl: NavigationController,
) : ViewModel4(dispatchers, logTag("Workspace", "ViewModel"), navCtrl) {

    private val tabLock = Mutex()
    private val _tabs = MutableStateFlow(emptyList<WorkspaceTab>())
    private val _selectedTabId = MutableStateFlow<Workspace.Id?>(null)

    val state = combine(
        _tabs,
        _selectedTabId
    ) { tabs, selectedTabId ->
        State(
            tabs = tabs,
            selected = selectedTabId
        )
    }.asStateFlow()

    fun modifyTab(
        action: TabAction,
    ) = launch {
        log(tag) { "modifyTab($action)" }

        tabLock.withLock {
            val currentTabs = _tabs.value.toMutableList()

            when (action) {
                is TabAction.Select -> {
                    _selectedTabId.value = action.id
                }

                is TabAction.Create -> {
                    val newTab = WorkspaceTab(
                        type = action.type
                    )
                    if (action.replace != null) {
                        val tabIndex = currentTabs.indexOfFirst { it.id == action.replace }
                        if (tabIndex == -1) throw IllegalStateException("Tab not found")

                        // TODO clean up old tab?
                        currentTabs[tabIndex]

                        currentTabs[tabIndex] = newTab
                    } else {
                        currentTabs.add(newTab)
                    }

                    _tabs.value = currentTabs
                    _selectedTabId.value = newTab.id
                }

                is TabAction.Close -> {
                    val currentTabs = _tabs.value.toMutableList()
                    currentTabs.removeAll { it.id == action.id }

                    _tabs.value = currentTabs
                    if (currentTabs.isNotEmpty()) {
                        if (_selectedTabId.value == action.id) {
                            _selectedTabId.value = currentTabs.first().id
                        }
                    } else {
                        _selectedTabId.value = null
                    }
                }
            }
        }
    }

    fun upgradeButler() = launch {
        log(tag) { "upgradeButler()" }
        navCtrl.goTo(AppNav.Main.Upgrade)
    }

    data class State(
        val tabs: List<WorkspaceTab> = emptyList(),
        val selected: Workspace.Id? = null
    ) {
        val current: WorkspaceTab?
            get() = tabs.firstOrNull { it.id == selected }
    }
}
