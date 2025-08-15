package eu.darken.butler.templates.ui

import dagger.assisted.Assisted
import dagger.assisted.AssistedFactory
import dagger.assisted.AssistedInject
import dagger.hilt.android.lifecycle.HiltViewModel
import eu.darken.butler.common.BuildConfigWrap
import eu.darken.butler.common.coroutine.DispatcherProvider
import eu.darken.butler.common.debug.logging.logTag
import eu.darken.butler.common.navigation.NavigationController
import eu.darken.butler.common.ui.ViewModel4
import eu.darken.butler.editor.ui.EditorWorkspaceTemplate
import eu.darken.butler.explorer.ui.ExplorerWorkspaceTemplate
import eu.darken.butler.searcher.ui.search.SearcherWorkspaceTemplate
import eu.darken.butler.upgrade.UpgradeRepo
import eu.darken.butler.workspace.core.Workspace
import eu.darken.butler.workspace.core.WorkspaceRemote
import eu.darken.butler.workspace.ui.WorkspacePanelMode
import eu.darken.butler.workspace.ui.template.WorkspaceTemplate
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.map

@HiltViewModel(assistedFactory = TemplatesWorkspaceViewModel.Factory::class)
class TemplatesWorkspaceViewModel @AssistedInject constructor(
    @Assisted private val id: Workspace.Id,
    dispatchers: DispatcherProvider,
    navCtrl: NavigationController,
    private val workspaceRemote: WorkspaceRemote,
    private val upgradeRepo: UpgradeRepo,
) : ViewModel4(dispatchers, logTag( "Templates","Workspace", id.shortTag), navCtrl) {

    private val templates = MutableStateFlow(
        listOf(
            ExplorerWorkspaceTemplate(),
            SearcherWorkspaceTemplate(),
            EditorWorkspaceTemplate(),
        )
    )

    private val workspaceTabs = workspaceRemote.state
        .map { repoState ->
            repoState.infos.map { info ->
                WorkspaceTab(
                    id = info.id,
                    type = info.type,
                    title = info.title,
                )
            }
        }

    val state = combine(
        templates,
        workspaceTabs,
        upgradeRepo.upgradeInfo,
        workspaceRemote.state.map { it.panelMode },
    ) { temps, tabs, upgradeInfo, panelMode ->
        State(
            id = id,
            templates = temps,
            workspaceTabs = tabs,
            selectedTabId = id,
            isUpgraded = upgradeInfo.isUpgraded,
            panelMode = panelMode,
            versionDescription = BuildConfigWrap.VERSION_DESCRIPTION_SHORT,
        )
    }.asStateFlow()

    data class State(
        val id: Workspace.Id,
        val isUpgraded: Boolean,
        val templates: List<WorkspaceTemplate>,
        val workspaceTabs: List<WorkspaceTab>,
        val selectedTabId: Workspace.Id,
        val panelMode: WorkspacePanelMode,
        val versionDescription: String,
    )

    @AssistedFactory
    interface Factory {
        fun create(id: Workspace.Id): TemplatesWorkspaceViewModel
    }
}
