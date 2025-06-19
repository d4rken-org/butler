package eu.darken.butler.workspace.ui.template

import dagger.assisted.Assisted
import dagger.assisted.AssistedFactory
import dagger.assisted.AssistedInject
import dagger.hilt.android.lifecycle.HiltViewModel
import eu.darken.butler.common.coroutine.DispatcherProvider
import eu.darken.butler.common.debug.logging.logTag
import eu.darken.butler.common.navigation.NavigationController
import eu.darken.butler.common.ui.ViewModel4
import eu.darken.butler.common.upgrade.UpgradeRepo
import eu.darken.butler.editor.ui.EditorWorkspaceTemplate
import eu.darken.butler.explorer.ui.ExplorerWorkspaceTemplate
import eu.darken.butler.searcher.ui.SearcherWorkspaceTemplate
import eu.darken.butler.workspace.core.Workspace
import eu.darken.butler.workspace.core.WorkspaceRepo
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.combine

@HiltViewModel(assistedFactory = TemplatesWorkspaceViewModel.Factory::class)
class TemplatesWorkspaceViewModel @AssistedInject constructor(
    @Assisted private val id: Workspace.Id,
    dispatchers: DispatcherProvider,
    navCtrl: NavigationController,
    private val workspaceRepo: WorkspaceRepo,
    private val upgradeRepo: UpgradeRepo,
) : ViewModel4(dispatchers, logTag("Workspace", "Templates", id.shortTag), navCtrl) {

    private val templates = MutableStateFlow(
        listOf(
            ExplorerWorkspaceTemplate(),
            SearcherWorkspaceTemplate(),
            EditorWorkspaceTemplate(),
        )
    )

    val state = combine(
        templates,
        upgradeRepo.upgradeInfo,
    ) { temps, upgradeInfo ->
        State(
            id = id,
            templates = temps,
            isUpgraded = upgradeInfo.isUpgraded,
        )
    }.asStateFlow()

    data class State(
        val id: Workspace.Id,
        val isUpgraded: Boolean,
        val templates: List<WorkspaceTemplate>,
    )

    @AssistedFactory
    interface Factory {
        fun create(id: Workspace.Id): TemplatesWorkspaceViewModel
    }
}
