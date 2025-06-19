package eu.darken.butler.workspace.ui

import dagger.hilt.android.lifecycle.HiltViewModel
import eu.darken.butler.common.coroutine.DispatcherProvider
import eu.darken.butler.common.debug.logging.Logging.Priority.*
import eu.darken.butler.common.debug.logging.log
import eu.darken.butler.common.debug.logging.logTag
import eu.darken.butler.common.flow.replayingShare
import eu.darken.butler.common.navigation.NavigationController
import eu.darken.butler.common.ui.ViewModel4
import eu.darken.butler.common.upgrade.UpgradeRepo
import eu.darken.butler.main.ui.AppNav
import eu.darken.butler.workspace.core.Workspace
import eu.darken.butler.workspace.core.WorkspaceRepo
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import javax.inject.Inject


@HiltViewModel
class WorkspaceViewModel @Inject constructor(
    dispatchers: DispatcherProvider,
    private val navCtrl: NavigationController,
    private val upgradeRepo: UpgradeRepo,
    private val workspaceRepo: WorkspaceRepo,
) : ViewModel4(dispatchers, logTag("Workspace", "Screen","VM"), navCtrl) {

    private val tabLock = Mutex()
    private val currentTabs = workspaceRepo.infos
        .map { infos ->
            infos.map {
                WorkspaceTab(
                    id = it.id,
                    type = it.type,
                    title = it.title,
                )
            }
        }
        .onEach { tabs ->
            tabs.forEachIndexed { index, tab -> log(tag, VERBOSE) { "TAB#$index: $tab" } }
        }
        .replayingShare(vmScope)
    private val selectedTabId = MutableStateFlow<Workspace.Id?>(null)

    val state = combine(
        currentTabs,
        selectedTabId,
        upgradeRepo.upgradeInfo,
    ) { tabs, selectedTabId, upgradeInfo ->
        State(
            tabs = tabs,
            selected = selectedTabId,
            showUpgradePrompt = !upgradeInfo.isPro
        )
    }.asStateFlow()

    fun modifyTab(
        action: TabAction,
    ) = launch {
        log(tag) { "modifyTab($action)" }

        tabLock.withLock {
            when (action) {
                is TabAction.Select -> {
                    log(tag) { "Selected tab $action, previous: ${selectedTabId.value}" }
                    if (selectedTabId.value != action.id) {
                        selectedTabId.value = action.id
                        log(tag) { "Tab selection changed to: ${action.id}" }
                    } else {
                        log(tag) { "Tab selection unchanged, already selected: ${action.id}" }
                    }
                }

                is TabAction.Create -> {
                    log(tag) { "Creating new workspace with $action" }
                    val newId = workspaceRepo.create(
                        type = action.type,
                        arguments = action.arguments,
                        idToReplace = action.replace
                    )
                    log(tag) { "New workspace created with id $newId, selecting and scrolling to it" }
                    selectedTabId.value = newId
                }

                is TabAction.Close -> {
                    log(tag) { "Closing workspace with id ${action.id}" }
                    val tabsBeforeDelete = currentTabs.first()
                    val closingIndex = tabsBeforeDelete.indexOfFirst { it.id == action.id }
                    val wasSelected = selectedTabId.value == action.id
                    
                    workspaceRepo.delete(action.id)
                    val tabsAfterDelete = tabsBeforeDelete - tabsBeforeDelete[closingIndex]

                    // If closed tab wasn't selected, keep current selection unchanged
                    if (tabsAfterDelete.isNotEmpty() && wasSelected) {
                        // Select next most intuitive tab when closing the selected tab
                        val newSelectedId = when {
                            // If there's a tab to the right, select it
                            closingIndex < tabsAfterDelete.size -> tabsAfterDelete[closingIndex].id
                            // Otherwise select the tab to the left (last tab)
                            else -> tabsAfterDelete.last().id
                        }
                        log(tag) { "Closed selected tab, selecting new tab: $newSelectedId" }
                        selectedTabId.value = newSelectedId
                    } else if (tabsAfterDelete.isEmpty()) {
                        log(tag) { "Closed last tab, setting selection to null" }
                        selectedTabId.value = null
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
        val tabs: List<WorkspaceTab>,
        val selected: Workspace.Id?,
        val showUpgradePrompt: Boolean,
    ) {
        val current: WorkspaceTab?
            get() = tabs.firstOrNull { it.id == selected }
    }
}
