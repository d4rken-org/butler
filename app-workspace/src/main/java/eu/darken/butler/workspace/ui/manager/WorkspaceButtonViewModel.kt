package eu.darken.butler.workspace.ui.manager

import dagger.hilt.android.lifecycle.HiltViewModel
import eu.darken.butler.common.coroutine.DispatcherProvider
import eu.darken.butler.common.debug.logging.log
import eu.darken.butler.common.debug.logging.logTag
import eu.darken.butler.common.navigation.Nav
import eu.darken.butler.common.navigation.settings
import eu.darken.butler.common.navigation.upgrade
import eu.darken.butler.common.ui.ViewModel4
import eu.darken.butler.workspace.core.WorkspaceAction
import eu.darken.butler.workspace.core.WorkspaceRemote
import eu.darken.butler.workspace.ui.WorkspacePageManager
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import javax.inject.Inject

@HiltViewModel
class WorkspaceButtonViewModel @Inject constructor(
    dispatchers: DispatcherProvider,
    private val workspaceRemote: WorkspaceRemote,
    private val workspacePageManager: WorkspacePageManager,
) : ViewModel4(dispatchers, logTag("Workspace", "Button", "VM")), WorkspaceButtonProvider {

    init {
        log(tag) { "init(): $this" }
    }

    override val state: Flow<State?> = workspaceRemote.state.map {
        State(
            workspaceCount = it.workspaceCount,
            operationsCount = it.operationCount,
            attentionCount = it.attentionCount,
            hasUnsavedChanges = it.infos.any { info -> info.hasUnsavedChanges },
        )
    }.asStateFlow()

    override fun executeWorkspaceAction(action: WorkspaceAction) = launch {
        log(tag) { "onWorkspaceAction($action)" }
        workspaceRemote.execute(action)
    }

    override fun navToWorkspaceManager() {
        log(tag) { "showWorkspaceManager()" }
        workspacePageManager.showManagerOverlay()
    }

    override fun navToSettings() {
        log(tag) { "onNavToSettings()" }
        navTo(Nav.Main.settings())
    }

    override fun navToUpgradeButler() {
        log(tag) { "upgradeButler()" }
        navTo(Nav.Main.upgrade())
    }

    data class State(
        val workspaceCount: Int = 0,
        val operationsCount: Int = 0,
        val attentionCount: Int = 0,
        val hasUnsavedChanges: Boolean = false,
    )

}
