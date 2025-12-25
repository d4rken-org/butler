package eu.darken.butler.templates.ui

import dagger.assisted.Assisted
import dagger.assisted.AssistedFactory
import dagger.assisted.AssistedInject
import dagger.hilt.android.lifecycle.HiltViewModel
import eu.darken.butler.apps.ui.AppsWorkspaceTemplate
import eu.darken.butler.common.BuildConfigWrap
import eu.darken.butler.common.coroutine.DispatcherProvider
import eu.darken.butler.common.debug.logging.Logging.Priority.*
import eu.darken.butler.common.debug.logging.log
import eu.darken.butler.common.debug.logging.logTag
import eu.darken.butler.common.developer.DeveloperSettings
import eu.darken.butler.common.ui.ViewModel4
import eu.darken.butler.developer.ui.DeveloperWorkspaceTemplate
import eu.darken.butler.editor.ui.EditorWorkspaceTemplate
import eu.darken.butler.explorer.ui.ExplorerWorkspaceTemplate
import eu.darken.butler.sdmaid.ui.SdMaidWorkspaceTemplate
import eu.darken.butler.searcher.ui.search.SearcherWorkspaceTemplate
import eu.darken.butler.upgrade.UpgradeRepo
import eu.darken.butler.workspace.core.Workspace
import eu.darken.butler.workspace.core.WorkspaceAction
import eu.darken.butler.workspace.core.WorkspaceRemote
import eu.darken.butler.workspace.ui.template.WorkspaceTemplate
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.map

@HiltViewModel(assistedFactory = TemplatesWorkspaceViewModel.Factory::class)
class TemplatesWorkspaceViewModel @AssistedInject constructor(
    @Assisted private val id: Workspace.Id,
    dispatchers: DispatcherProvider,
    private val workspaceRemote: WorkspaceRemote,
    private val upgradeRepo: UpgradeRepo,
    private val developerSettings: DeveloperSettings,
) : ViewModel4(dispatchers, logTag("Templates", "Workspace", id.shortTag)) {

    private val baseTemplates = listOf(
        ExplorerWorkspaceTemplate(),
        SearcherWorkspaceTemplate(),
        EditorWorkspaceTemplate(),
        AppsWorkspaceTemplate(),
        SdMaidWorkspaceTemplate(),
    )

    private val templates = developerSettings.isDeveloperModeUnlocked.flow.map { isUnlocked ->
        buildList {
            addAll(baseTemplates)
            if (isUnlocked) {
                add(DeveloperWorkspaceTemplate())
            }
        }
    }

    val state = combine(
        templates,
        upgradeRepo.upgradeInfo,
    ) { temps, upgradeInfo ->
        State(
            id = id,
            templates = temps,
            isUpgraded = upgradeInfo.isUpgraded,
            versionDescription = BuildConfigWrap.VERSION_DESCRIPTION_SHORT,
        )
    }.asStateFlow()

    fun createWorkspace(action: WorkspaceAction.Create) = launch {
        when (val result = workspaceRemote.execute(action)) {
            is WorkspaceAction.Create.Result.Success -> {
                log(tag) { "Workspace created: ${result.newId}" }
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
    )

    @AssistedFactory
    interface Factory {
        fun create(id: Workspace.Id): TemplatesWorkspaceViewModel
    }
}
