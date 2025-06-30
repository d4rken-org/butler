package eu.darken.butler.workspace.ui

import dagger.hilt.android.lifecycle.HiltViewModel
import eu.darken.butler.common.WebpageTool
import eu.darken.butler.common.coroutine.DispatcherProvider
import eu.darken.butler.common.debug.logging.Logging.Priority.*
import eu.darken.butler.common.debug.logging.log
import eu.darken.butler.common.debug.logging.logTag
import eu.darken.butler.common.flow.replayingShare
import eu.darken.butler.common.navigation.Nav
import eu.darken.butler.common.navigation.NavigationController
import eu.darken.butler.common.navigation.upgrade
import eu.darken.butler.common.ui.ViewModel4
import eu.darken.butler.main.core.motd.MotdRepo
import eu.darken.butler.main.core.motd.MotdState
import eu.darken.butler.templates.ui.WorkspaceTab
import eu.darken.butler.upgrade.UpgradeRepo
import eu.darken.butler.workspace.core.Workspace
import eu.darken.butler.workspace.core.WorkspaceAction
import eu.darken.butler.workspace.core.WorkspaceRepo
import eu.darken.butler.workspace.core.WorkspaceSettings
import eu.darken.butler.workspace.ui.manager.workspaceManager
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.launchIn
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.sync.Mutex
import java.util.UUID
import javax.inject.Inject


@HiltViewModel
class WorkspaceViewModel @Inject constructor(
    dispatchers: DispatcherProvider,
    private val navCtrl: NavigationController,
    upgradeRepo: UpgradeRepo,
    private val workspaceRepo: WorkspaceRepo,
    private val workspaceSettings: WorkspaceSettings,
    private val motdRepo: MotdRepo,
    private val webpageTool: WebpageTool,
) : ViewModel4(dispatchers, logTag("Workspace", "Screen","VM"), navCtrl) {

    private val tabLock = Mutex()
    private val currentTabs = workspaceRepo.state
        .map { state ->
            state.workspaceInfos.map {
                WorkspaceTab(
                    id = it.id,
                    type = it.type,
                    title = it.title,
                )
            }
        }
        .onEach { tabs ->
            tabs.forEachIndexed { index, tab -> log(tag, VERBOSE) { "TAB#$index: $tab" } }
        }
        .replayingShare(vmScope)
    private val selectedTabId = MutableStateFlow<Workspace.Id?>(null)
    private val hiddenMotdIds = MutableStateFlow<Set<UUID>>(emptySet())

    val state = combine(
        currentTabs,
        selectedTabId,
        upgradeRepo.upgradeInfo,
        workspaceSettings.isButtonActionsFlipped.flow,
        combine(motdRepo.motd, hiddenMotdIds) { motd, hiddenIds ->
            motd?.takeIf { it.id !in hiddenIds }
        }
    ) { tabs, selectedTabId, upgradeInfo, isButtonActionsFlipped, visibleMotd ->
        State(
            tabs = tabs,
            selected = selectedTabId,
            isUpgraded = upgradeInfo.isUpgraded,
            isButtonActionsFlipped = isButtonActionsFlipped,
            motd = visibleMotd,
        )
    }.asStateFlow()

    init {
        // Observe workspace selection from WorkspaceManagerViewModel
        workspaceRepo.state
            .map { it.selectedWorkspaceId }
            .onEach { workspaceId ->
                if (workspaceId != selectedTabId.value) {
                    log(tag) { "External workspace selection: $workspaceId" }
                    selectedTabId.value = workspaceId
                }
            }
            .launchIn(vmScope)
    }

    fun executeAction(
        action: WorkspaceAction,
    ) = launch {
        log(tag) { "executeAction($action)" }
        workspaceRepo.execute(action)
    }

    fun openWorkspaceManager() = launch {
        log(tag) { "openWorkspaceManager()" }
        navCtrl.goTo(Nav.workspaceManager())
    }

    fun upgradeButler() = launch {
        log(tag) { "upgradeButler()" }
        navCtrl.goTo(Nav.Main.upgrade())
    }

    fun hideMotd(id: UUID) = launch {
        log(tag) { "hideMotd($id)" }
        hiddenMotdIds.update { it + id }
    }

    fun dismissMotd(id: UUID) = launch {
        log(tag) { "dismissMotd($id)" }
        motdRepo.dismiss(id)
    }

    fun openMotdLink(url: String) = launch {
        log(tag) { "openMotdLink($url)" }
        webpageTool.open(url)
    }

    data class State(
        val tabs: List<WorkspaceTab>,
        val selected: Workspace.Id?,
        val isUpgraded: Boolean,
        val isButtonActionsFlipped: Boolean = false,
        val motd: MotdState? = null,
    ) {
        val current: WorkspaceTab?
            get() = tabs.firstOrNull { it.id == selected }
    }
}
