package eu.darken.butler.workspace.ui.workspaces

import dagger.hilt.android.lifecycle.HiltViewModel
import eu.darken.butler.common.coroutine.DispatcherProvider
import eu.darken.butler.common.debug.logging.log
import eu.darken.butler.common.debug.logging.logTag
import eu.darken.butler.common.navigation.Nav
import eu.darken.butler.common.navigation.NavigationController
import eu.darken.butler.common.navigation.upgrade
import eu.darken.butler.common.ui.ViewModel4
import eu.darken.butler.upgrade.UpgradeRepo
import eu.darken.butler.workspace.core.Workspace
import eu.darken.butler.workspace.core.WorkspaceAction
import eu.darken.butler.workspace.core.WorkspaceRemote
import eu.darken.butler.workspace.core.WorkspaceRepo
import eu.darken.butler.workspace.core.WorkspaceSettings
import eu.darken.butler.workspace.ui.WorkspacePanelMode
import kotlinx.coroutines.flow.combine
import javax.inject.Inject


@HiltViewModel
class WorkspaceViewModel @Inject constructor(
    dispatchers: DispatcherProvider,
    private val navCtrl: NavigationController,
    upgradeRepo: UpgradeRepo,
    private val workspaceRepo: WorkspaceRepo,
    workspaceSettings: WorkspaceSettings,
) : ViewModel4(dispatchers, logTag("Workspace", "Screen", "VM"), navCtrl) {

    val state = combine(
        workspaceRepo.state,
        upgradeRepo.upgradeInfo,
        workspaceSettings.isButtonActionsFlipped.flow,
        workspaceSettings.swipeGesturesEnabled.flow,
    ) { repoState, upgradeInfo, isButtonFlipped, swipeGesturesEnabled ->

        State(
            state = repoState,
            isUpgraded = upgradeInfo.isUpgraded,
            isButtonActionsFlipped = isButtonFlipped,
            swipeGesturesEnabled = swipeGesturesEnabled,
        )
    }.asStateFlow()

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
        private val state: WorkspaceRemote.State,
        val isUpgraded: Boolean,
        val isButtonActionsFlipped: Boolean = false,
        val swipeGesturesEnabled: Boolean = true,
    ) {
        val displayMode: WorkspacePanelMode
            get() = state.panelMode

        val focused: Workspace.Id?
            get() = state.focusedWorkspace

        val current: Workspace.Info?
            get() = state.infos.firstOrNull { it.id == focused }

        val selected: Map<Int, Workspace.Info>
            get() = state.selectedWorkspaces.mapNotNull { (position, id) ->
                state.infos.find { it.id == id }?.let { position to it }
            }.toMap()

        val all: List<Workspace.Info>
            get() = state.infos
    }
}
