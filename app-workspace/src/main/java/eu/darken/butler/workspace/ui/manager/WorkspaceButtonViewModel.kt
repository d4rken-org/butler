package eu.darken.butler.workspace.ui.manager

import dagger.hilt.android.lifecycle.HiltViewModel
import eu.darken.butler.common.coroutine.DispatcherProvider
import eu.darken.butler.common.debug.logging.log
import eu.darken.butler.common.debug.logging.logTag
import eu.darken.butler.common.navigation.Nav
import eu.darken.butler.common.navigation.settings
import eu.darken.butler.common.navigation.upgrade
import eu.darken.butler.common.ui.ViewModel4
import eu.darken.butler.workspace.core.Workspace
import eu.darken.butler.workspace.core.WorkspaceAction
import eu.darken.butler.workspace.core.WorkspaceRemote
import eu.darken.butler.workspace.contracts.templates.TemplatesArguments
import eu.darken.butler.workspace.core.createAndFocus
import eu.darken.butler.workspace.ui.WorkspacePageManager
import eu.darken.butler.workspace.ui.template.QuickCreateItem
import eu.darken.butler.workspace.ui.template.WorkspaceTemplate
import eu.darken.butler.workspace.ui.template.availableTemplates
import eu.darken.butler.workspace.ui.template.toQuickCreateItem
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.onStart
import javax.inject.Inject

@HiltViewModel
class WorkspaceButtonViewModel @Inject constructor(
    dispatchers: DispatcherProvider,
    private val workspaceRemote: WorkspaceRemote,
    private val workspacePageManager: WorkspacePageManager,
    workspaceTemplates: Set<@JvmSuppressWildcards WorkspaceTemplate>,
) : ViewModel4(dispatchers, logTag("Workspace", "Button", "VM")), WorkspaceButtonProvider {

    init {
        log(tag) { "init(): $this" }
    }

    private val quickCreateItems = workspaceTemplates.availableTemplates()
        .map { templates -> templates.filter { it.isQuickCreate }.map { it.toQuickCreateItem() } }
        .onStart { emit(emptyList()) }

    override val state: Flow<State?> = combine(
        workspaceRemote.state,
        quickCreateItems,
    ) { remoteState, quickCreate ->
        State(
            workspaceCount = remoteState.workspaceCount,
            operationsCount = remoteState.operationCount,
            attentionCount = remoteState.attentionCount,
            hasUnsavedChanges = remoteState.infos.any { info -> info.hasUnsavedChanges },
            quickCreateItems = quickCreate,
        )
    }.asStateFlow()

    override fun executeWorkspaceAction(action: WorkspaceAction) = launch {
        log(tag) { "onWorkspaceAction($action)" }
        workspaceRemote.execute(action)
    }

    override fun createWorkspace(item: QuickCreateItem) = launch {
        log(tag) { "createWorkspace(${item.type})" }
        workspaceRemote.createAndFocus(item.type, item.arguments)
    }

    override fun createTemplatesWorkspace() = launch {
        log(tag) { "createTemplatesWorkspace()" }
        workspaceRemote.createAndFocus(Workspace.Type.TEMPLATES, TemplatesArguments.Default())
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
        val quickCreateItems: List<QuickCreateItem> = emptyList(),
    )

}
