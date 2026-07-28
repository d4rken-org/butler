package eu.darken.butler.templates.ui

import dagger.assisted.Assisted
import dagger.assisted.AssistedFactory
import dagger.assisted.AssistedInject
import dagger.hilt.android.lifecycle.HiltViewModel
import eu.darken.butler.common.BuildConfigWrap
import eu.darken.butler.common.coroutine.DispatcherProvider
import eu.darken.butler.common.debug.logging.Logging.Priority.*
import eu.darken.butler.common.debug.logging.log
import eu.darken.butler.common.debug.logging.logTag
import eu.darken.butler.common.ui.ViewModel4
import eu.darken.butler.upgrade.UpgradeRepo
import eu.darken.butler.workspace.core.Workspace
import eu.darken.butler.workspace.core.WorkspaceAction
import eu.darken.butler.workspace.core.WorkspaceEvent
import eu.darken.butler.workspace.core.WorkspaceRemote
import eu.darken.butler.workspace.ui.template.WorkspaceTemplate
import eu.darken.butler.workspace.ui.template.availableTemplates
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.map

@HiltViewModel(assistedFactory = TemplatesWorkspaceViewModel.Factory::class)
class TemplatesWorkspaceViewModel @AssistedInject constructor(
    @Assisted private val id: Workspace.Id,
    dispatchers: DispatcherProvider,
    private val workspaceRemote: WorkspaceRemote,
    private val upgradeRepo: UpgradeRepo,
    workspaceTemplates: Set<@JvmSuppressWildcards WorkspaceTemplate>,
) : ViewModel4(dispatchers, logTag("Templates", "Workspace", id.shortTag)) {

    private val activeSingletonTypes = workspaceRemote.state.map { state ->
        state.infos
            .filter { !it.isSubWorkspace && it.type.isSingleton }
            .map { it.type }
            .toSet()
    }

    private val templates = combine(
        workspaceTemplates.availableTemplates(),
        activeSingletonTypes,
    ) { available, activeSingletons ->
        available.filterNot { it.type in activeSingletons }
    }

    private val customTitleFlow = workspaceRemote.state
        .map { state -> state.infos.firstOrNull { it.id == id }?.customTitle }

    val state = combine(
        templates,
        upgradeRepo.upgradeInfo,
        customTitleFlow,
    ) { temps, upgradeInfo, currentCustomTitle ->
        State(
            id = id,
            templates = temps,
            isUpgraded = upgradeInfo.isPro,
            versionDescription = BuildConfigWrap.VERSION_DESCRIPTION_SHORT,
            customTitle = currentCustomTitle,
        )
    }.asStateFlow()

    // Owned here rather than in the page: the rename dialog renders from the overlay slot, which is
    // a sibling of the page, so a `remember` there would be a different instance from the one the
    // overlay reads.
    private val _renameDialogVisible = MutableStateFlow(false)
    val renameDialogVisible: StateFlow<Boolean> = _renameDialogVisible

    fun showRenameDialog() {
        _renameDialogVisible.value = true
    }

    fun dismissRenameDialog() {
        _renameDialogVisible.value = false
    }

    fun renameWorkspace(customTitle: String?) = launch {
        log(tag) { "renameWorkspace($customTitle)" }
        _renameDialogVisible.value = false
        workspaceRemote.execute(WorkspaceAction.Rename(id, customTitle))
    }

    fun createWorkspace(action: WorkspaceAction.Create) = launch {
        when (val result = workspaceRemote.execute(action)) {
            is WorkspaceAction.Create.Result.Success -> {
                log(tag) { "Workspace created: ${result.newId}" }
            }
            is WorkspaceAction.Create.Result.AlreadyOpen -> {
                log(tag) { "Singleton already open, focusing existing: ${result.existingId}" }
                workspaceRemote.emitEvent(WorkspaceEvent.SelectionRequested(result.existingId))
            }
            is WorkspaceAction.Create.Result.LimitReached -> {
                log(tag, WARN) { "Workspace creation blocked - limit reached" }
            }
        }
    }

    data class State(
        val id: Workspace.Id,
        val isUpgraded: Boolean,
        val templates: List<WorkspaceTemplate>,
        val versionDescription: String,
        val customTitle: String? = null,
    )

    @AssistedFactory
    interface Factory {
        fun create(id: Workspace.Id): TemplatesWorkspaceViewModel
    }
}
