package eu.darken.butler.workspace.ui.manager

import dagger.hilt.android.lifecycle.HiltViewModel
import eu.darken.butler.common.coroutine.DispatcherProvider
import eu.darken.butler.common.debug.logging.log
import eu.darken.butler.common.debug.logging.logTag
import eu.darken.butler.common.navigation.Nav
import eu.darken.butler.common.navigation.NavigationController
import eu.darken.butler.common.navigation.settings
import eu.darken.butler.common.ui.ViewModel4
import eu.darken.butler.workspace.ui.WorkspacePanelMode
import eu.darken.butler.workspace.core.WorkspaceAction
import eu.darken.butler.workspace.core.WorkspaceRemote
import kotlinx.coroutines.flow.map
import javax.inject.Inject

@HiltViewModel
class WorkspaceButtonViewModel @Inject constructor(
    dispatchers: DispatcherProvider,
    navCtrl: NavigationController,
    private val workspaceRemote: WorkspaceRemote,
) : ViewModel4(dispatchers, logTag("Workspace", "Button", "VM"), navCtrl) {

    val state = workspaceRemote.state.map {
        State(
            workspaceCount = it.workspaceCount,
            operationsCount = it.operationCount,
            attentionCount = it.attentionCount,
        )
    }.asStateFlow()

    fun onWorkspaceAction(action: WorkspaceAction) = launch {
        log(tag) { "onWorkspaceAction($action)" }
        workspaceRemote.execute(action)
    }

    fun onNavToWorkspaceManager() {
        log(tag) { "onNavToWorkspaceManager()" }
        navTo(Nav.workspaceManager())
    }

    fun onNavToSettings() {
        log(tag) { "onNavToSettings()" }
        navTo(Nav.Main.settings())
    }

    data class State(
        val workspaceCount: Int = 0,
        val operationsCount: Int = 0,
        val attentionCount: Int = 0,
    )
}
