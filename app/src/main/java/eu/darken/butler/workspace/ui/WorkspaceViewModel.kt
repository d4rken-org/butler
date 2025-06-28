package eu.darken.butler.workspace.ui

import dagger.hilt.android.lifecycle.HiltViewModel
import eu.darken.butler.common.coroutine.DispatcherProvider
import eu.darken.butler.common.debug.logging.Logging.Priority.*
import eu.darken.butler.common.debug.logging.log
import eu.darken.butler.common.debug.logging.logTag
import eu.darken.butler.common.flow.replayingShare
import eu.darken.butler.common.navigation.Nav
import eu.darken.butler.common.navigation.NavigationController
import eu.darken.butler.common.navigation.upgrade
import eu.darken.butler.common.ui.ViewModel4
import eu.darken.butler.common.upgrade.UpgradeRepo
import eu.darken.butler.templates.ui.WorkspaceTab
import eu.darken.butler.workspace.core.Workspace
import eu.darken.butler.workspace.core.WorkspaceAction
import eu.darken.butler.workspace.core.WorkspaceRepo
import eu.darken.butler.workspace.core.WorkspaceSettings
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.launchIn
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.sync.Mutex
import javax.inject.Inject


@HiltViewModel
class WorkspaceViewModel @Inject constructor(
    dispatchers: DispatcherProvider,
    private val navCtrl: NavigationController,
    upgradeRepo: UpgradeRepo,
    private val workspaceRepo: WorkspaceRepo,
    private val workspaceSettings: WorkspaceSettings,
) : ViewModel4(dispatchers, logTag("Workspace", "Screen","VM"), navCtrl) {

    private val tabLock = Mutex()
    private val currentTabs = workspaceRepo.state
        .map { state ->
            state.workspaceInfos.map {
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
        workspaceSettings.isButtonActionsFlipped.flow,
    ) { tabs, selectedTabId, upgradeInfo, isButtonActionsFlipped ->
        State(
            tabs = tabs,
            selected = selectedTabId,
            isUpgraded = upgradeInfo.isUpgraded,
            isButtonActionsFlipped = isButtonActionsFlipped
        )
    }.asStateFlow()

    init {
        // Observe workspace selection from WorkspaceManagerViewModel
        workspaceRepo.state
            .map { it.selectedWorkspaceId }
            .onEach { workspaceId ->
                if (workspaceId != selectedTabId.value) {
                    log(tag) { "External workspace selection: $workspaceId" }
                    selectedTabId.value = workspaceId
                }
            }
            .launchIn(vmScope)
    }

    fun modifyTab(
        action: WorkspaceAction,
    ) = launch {
        log(tag) { "modifyTab($action)" }

        workspaceRepo.execute(action)
    }

    fun upgradeButler() = launch {
        log(tag) { "upgradeButler()" }
        navCtrl.goTo(Nav.Main.upgrade())
    }

    data class State(
        val tabs: List<WorkspaceTab>,
        val selected: Workspace.Id?,
        val isUpgraded: Boolean,
        val isButtonActionsFlipped: Boolean = false,
    ) {
        val current: WorkspaceTab?
            get() = tabs.firstOrNull { it.id == selected }
    }
}
