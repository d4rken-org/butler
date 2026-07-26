package eu.darken.butler.workspace.ui.manager

import dagger.hilt.android.lifecycle.HiltViewModel
import eu.darken.butler.common.ca.CaString
import eu.darken.butler.common.coroutine.DispatcherProvider
import eu.darken.butler.common.datastore.value
import eu.darken.butler.common.debug.logging.Logging.Priority.*
import eu.darken.butler.common.debug.logging.log
import eu.darken.butler.common.debug.logging.logTag
import eu.darken.butler.common.flow.combine
import eu.darken.butler.common.ui.ViewModel4
import eu.darken.butler.workspace.core.Workspace
import eu.darken.butler.workspace.core.WorkspaceAction
import eu.darken.butler.workspace.core.WorkspaceRepo
import eu.darken.butler.workspace.core.WorkspaceSettings
import eu.darken.butler.workspace.core.defaultArguments
import eu.darken.butler.workspace.ui.WorkspacePageManager
import eu.darken.butler.workspace.ui.manager.preview.WorkspacePreviewManager
import eu.darken.butler.workspace.ui.template.QuickCreateItem
import eu.darken.butler.workspace.ui.template.WorkspaceTemplate
import eu.darken.butler.workspace.ui.template.availableTemplates
import eu.darken.butler.workspace.ui.template.toQuickCreateItem
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.withTimeoutOrNull
import javax.inject.Inject
import kotlin.time.Duration.Companion.milliseconds

@HiltViewModel
class WorkspaceManagerViewModel @Inject constructor(
    dispatchers: DispatcherProvider,
    private val workspaceRepo: WorkspaceRepo,
    private val workspaceSettings: WorkspaceSettings,
    private val workspacePageManager: WorkspacePageManager,
    private val workspacePreviewManager: WorkspacePreviewManager,
    workspaceTemplates: Set<@JvmSuppressWildcards WorkspaceTemplate>,
) : ViewModel4(dispatchers, logTag("Workspace", "Manager", "VM")) {

    private val filterOperationsFlow = MutableStateFlow(false)
    private val filterAttentionFlow = MutableStateFlow(false)

    private val quickCreateItems = workspaceTemplates.availableTemplates()
        .map { templates -> templates.filter { it.isQuickCreate }.map { it.toQuickCreateItem() } }

    val state = combine(
        workspaceRepo.state,
        workspaceSettings.showTipBadgeExplanation.flow,
        workspaceSettings.showTipFabLongPress.flow,
        workspaceSettings.livePreview.flow,
        workspacePageManager.state,
        filterOperationsFlow,
        filterAttentionFlow,
        quickCreateItems,
    ) { repoState, showBadge, showFabLongPressHint, livePreview, pageManagerState, filterOps, filterAtt, quickCreate ->
        State(
            workspaces = repoState.infos.map { info ->
                val panePosition = pageManagerState.selectedWorkspaces.entries
                    .find { it.value == info.id }?.key
                WorkspaceItem(
                    id = info.id,
                    type = info.type,
                    title = info.displayTitle,
                    subtitle = info.subtitle,
                    isFocused = pageManagerState.focusedWorkspaceId == info.id,
                    isSelected = pageManagerState.selectedWorkspaces.values.contains(info.id),
                    paneNumber = panePosition,
                    operationCount = info.operationCount,
                    attentionCount = info.attentionCount,
                    autoTitle = info.title,
                    customTitle = info.customTitle,
                    isSubWorkspace = info.isSubWorkspace,
                )
            },
            useLivePreview = livePreview,
            showBadgeExplanation = showBadge,
            showLongPressHint = showFabLongPressHint,
            operationsCount = repoState.operationCount,
            attentionCount = repoState.attentionCount,
            currentPaneCount = pageManagerState.currentPaneCount,
            filterOperations = filterOps,
            filterAttention = filterAtt,
            quickCreateItems = quickCreate,
            hasUnsavedChanges = repoState.infos.any { it.hasUnsavedChanges },
        )
    }.asStateFlow()

    fun closeWorkspace(id: Workspace.Id) = launch {
        workspaceRepo.execute(WorkspaceAction.Close(id))
    }

    fun reorderWorkspaces(workspaceIds: List<Workspace.Id>) = launch {
        workspaceRepo.execute(WorkspaceAction.Reorder(workspaceIds))
    }

    fun renameWorkspace(id: Workspace.Id, customTitle: String?) = launch {
        log(tag) { "renameWorkspace($id, $customTitle)" }
        workspaceRepo.execute(WorkspaceAction.Rename(id, customTitle))
    }

    fun selectWorkspace(id: Workspace.Id) = launch {
        log(tag) { "selectWorkspace($id)" }
        // Emit selection event to notify the parent screen
        workspacePageManager.selectWorkspaceFromManager(id)

        // Wait for selection to be processed before navigating back
        // This ensures pager animation can complete without being interrupted
        val selectionProcessed = withTimeoutOrNull(500.milliseconds) {
            workspacePageManager.state.first { it.focusedWorkspaceId == id }
        }

        if (selectionProcessed == null) {
            log(tag) { "Selection processing timed out, navigating back anyway" }
        }

        navigateBack()
    }

    fun createWorkspace(item: QuickCreateItem) = createWorkspace(item.type, item.arguments)

    fun createWorkspace(type: Workspace.Type, arguments: Workspace.Arguments? = null) = launch {
        log(tag) { "createWorkspace($type)" }
        val args = arguments ?: type.defaultArguments ?: return@launch
        when (val result = workspaceRepo.execute(WorkspaceAction.Create(type, args))) {
            is WorkspaceAction.Create.Result.Success -> {
                log(tag) { "Workspace created: ${result.newId}" }
            }
            is WorkspaceAction.Create.Result.AlreadyOpen -> {
                log(tag) { "Singleton already open, focusing existing: ${result.existingId}" }
                workspacePageManager.selectWorkspaceFromManager(result.existingId)
            }
            is WorkspaceAction.Create.Result.LimitReached -> {
                log(tag, WARN) { "Workspace creation blocked - limit reached" }
            }
        }
    }

    fun navigateBack() {
        log(tag) { "navigateBack()" }
        workspacePageManager.hideManagerOverlay()
    }

    fun dismissBadgeExplanation() = launch {
        workspaceSettings.showTipBadgeExplanation.value(false)
    }

    fun dismissLongPressHint() = launch {
        workspaceSettings.showTipFabLongPress.value(false)
    }

    fun closeAllWorkspaces() = launch {
        log(tag) { "closeAllWorkspaces()" }
        workspaceRepo.execute(WorkspaceAction.CloseAll)
    }

    fun onScreenAppeared() = launch {
        log(tag) { "onScreenAppeared() - invalidating focused workspace preview" }
        workspacePreviewManager.invalidateFocusedWorkspacePreview()
    }

    fun toggleOperationsFilter() {
        log(tag) { "toggleOperationsFilter() - current: ${filterOperationsFlow.value}" }
        filterOperationsFlow.value = !filterOperationsFlow.value
    }

    fun toggleAttentionFilter() {
        log(tag) { "toggleAttentionFilter() - current: ${filterAttentionFlow.value}" }
        filterAttentionFlow.value = !filterAttentionFlow.value
    }

    fun clearFilters() {
        log(tag) { "clearFilters()" }
        filterOperationsFlow.value = false
        filterAttentionFlow.value = false
    }

    data class State(
        val workspaces: List<WorkspaceItem> = emptyList(),
        val showBadgeExplanation: Boolean = true,
        val showLongPressHint: Boolean = true,
        val useLivePreview: Boolean = true,
        val operationsCount: Int = 0,
        val attentionCount: Int = 0,
        val currentPaneCount: Int = 1,
        val filterOperations: Boolean = false,
        val filterAttention: Boolean = false,
        val quickCreateItems: List<QuickCreateItem> = emptyList(),
        val hasUnsavedChanges: Boolean = false,
    ) {
        val workspaceCount: Int = workspaces.size

        val filteredWorkspaces: List<WorkspaceItem>
            get() {
                // If no filters active, return all workspaces
                if (!filterOperations && !filterAttention) return workspaces

                return workspaces.filter { workspace ->
                    val matchesOperations = !filterOperations || workspace.operationCount > 0
                    val matchesAttention = !filterAttention || workspace.attentionCount > 0
                    matchesOperations && matchesAttention
                }
            }
    }

    data class WorkspaceItem(
        val id: Workspace.Id,
        val type: Workspace.Type,
        /** What the card renders: the custom name when set, otherwise the automatic title. */
        val title: CaString,
        val subtitle: CaString?,
        val isFocused: Boolean = false,
        val isSelected: Boolean = false,
        val paneNumber: Int? = null,
        val operationCount: Int = 0,
        val attentionCount: Int = 0,
        /** The automatic title, shown as the rename dialog's placeholder. Defaults to [title]. */
        val autoTitle: CaString = title,
        val customTitle: String? = null,
        /**
         * Modal pickers show up as cards but are excluded from session persistence, so renaming one
         * would silently not survive a restart - the card hides its rename affordance.
         */
        val isSubWorkspace: Boolean = false,
    )
}
