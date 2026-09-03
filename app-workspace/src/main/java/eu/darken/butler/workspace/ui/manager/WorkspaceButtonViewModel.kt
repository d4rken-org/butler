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
import eu.darken.butler.workspace.core.WorkspaceStacks
import eu.darken.butler.workspace.contracts.templates.TemplatesArguments
import eu.darken.butler.workspace.core.createAndFocus
import eu.darken.butler.workspace.core.usage.WorkspaceUsageRepo
import eu.darken.butler.workspace.ui.WorkspacePageManager
import eu.darken.butler.workspace.ui.template.QuickCreateItem
import eu.darken.butler.workspace.ui.template.WorkspaceTemplate
import eu.darken.butler.workspace.ui.template.availableTemplates
import eu.darken.butler.workspace.ui.template.toQuickCreateItem
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.onStart
import javax.inject.Inject

@HiltViewModel
class WorkspaceButtonViewModel @Inject constructor(
    dispatchers: DispatcherProvider,
    private val workspaceRemote: WorkspaceRemote,
    private val workspacePageManager: WorkspacePageManager,
    workspaceTemplates: Set<@JvmSuppressWildcards WorkspaceTemplate>,
    usageRepo: WorkspaceUsageRepo,
) : ViewModel4(dispatchers, logTag("Workspace", "Button", "VM")), WorkspaceButtonProvider {

    init {
        log(tag) { "init(): $this" }
    }

    /**
     * Ranking runs over all available templates, so a user who lives in History sees History here.
     * The quick-create templates pad the list while fewer than [RECENT_LIMIT] types have been used.
     * The outer empty emission keeps the button's badges rendering before the DataStore-backed
     * template availability flows resolve; the UI hides the category header while the list is empty.
     */
    private val recentItems = combine(
        workspaceTemplates.availableTemplates(),
        usageRepo.rankedTypes.onStart { emit(emptyList()) },
    ) { templates, ranked ->
        val byType = templates.associateBy { it.type }
        val used = ranked.mapNotNull { byType[it] }
        val fallback = templates.filter { it.isQuickCreate }
        (used + fallback).distinctBy { it.type }.take(RECENT_LIMIT).map { it.toQuickCreateItem() }
    }.onStart { emit(emptyList()) }

    override val state: Flow<State?> = combine(
        workspaceRemote.state,
        recentItems,
    ) { remoteState, recent ->
        val stacks = WorkspaceStacks(remoteState.infos)
        State(
            workspaceCount = remoteState.workspaceCount,
            operationsCount = remoteState.operationCount,
            attentionCount = remoteState.attentionCount,
            hasUnsavedChanges = remoteState.infos.any { info -> info.hasUnsavedChanges },
            recentItems = recent,
            unitsByMember = remoteState.infos.associate { info ->
                // Only resolvable units get unit semantics. A recovery unit is keyed on its first
                // member in list order, which is not necessarily one the others hang off, so
                // closing it can leave siblings open - and the count would have promised otherwise.
                // Those fall back to closing themselves, which is what this row did before.
                val members = stacks.unitOf(info.id)
                info.id to StackUnit(
                    ownerId = members?.firstOrNull()?.id ?: info.id,
                    size = members?.size ?: 1,
                )
            },
        )
    }.asStateFlow()

    override fun executeWorkspaceAction(action: WorkspaceAction) = launch {
        log(tag) { "onWorkspaceAction($action)" }
        workspaceRemote.execute(action)
    }

    /**
     * Where a tab created from this button goes: right of whatever is focused right now.
     *
     * Sampled synchronously, before [launch] hands the block to a dispatcher - reading focus inside
     * the coroutine would sample it at an arbitrary later moment and anchor the tab to wherever the
     * user has navigated since the tap.
     */
    private fun focusedTabId(): Workspace.Id? = workspacePageManager.state.value.focusedWorkspaceId

    override fun createWorkspace(item: QuickCreateItem) {
        val sourceId = focusedTabId()
        launch {
            log(tag) { "createWorkspace(${item.type}), source=$sourceId" }
            workspaceRemote.createAndFocus(item.type, item.arguments, sourceWorkspaceId = sourceId)
        }
    }

    override fun createTemplatesWorkspace() {
        val sourceId = focusedTabId()
        launch {
            log(tag) { "createTemplatesWorkspace(), source=$sourceId" }
            workspaceRemote.createAndFocus(
                type = Workspace.Type.TEMPLATES,
                arguments = TemplatesArguments.Default(),
                sourceWorkspaceId = sourceId,
            )
        }
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
        val recentItems: List<QuickCreateItem> = emptyList(),
        /**
         * The ownership unit each open workspace belongs to, keyed by member. The menu's close acts
         * on whole units: a tab and the overlays stacked on it are one thing to the user, so it has
         * to take the stack down like the manager's card does.
         */
        val unitsByMember: Map<Workspace.Id, StackUnit> = emptyMap(),
    )

    /**
     * @param ownerId the tab the unit belongs to - closing it closes every member.
     * @param size members in the unit, the tab included; 1 means a tab with nothing stacked on it.
     */
    data class StackUnit(
        val ownerId: Workspace.Id,
        val size: Int,
    )

    companion object {
        private const val RECENT_LIMIT = 3
    }

}
