package eu.darken.butler.setup.ui

import dagger.assisted.Assisted
import dagger.assisted.AssistedFactory
import dagger.assisted.AssistedInject
import dagger.hilt.android.lifecycle.HiltViewModel
import eu.darken.butler.common.WebpageTool
import eu.darken.butler.common.coroutine.DispatcherProvider
import eu.darken.butler.common.debug.logging.log
import eu.darken.butler.common.debug.logging.logTag
import eu.darken.butler.common.navigation.NavigationController
import eu.darken.butler.common.ui.ViewModel4
import eu.darken.butler.setup.core.SetupAction
import eu.darken.butler.setup.core.SetupItem
import eu.darken.butler.setup.core.SetupManager
import eu.darken.butler.setup.core.SetupModule
import kotlinx.coroutines.flow.map

@HiltViewModel(assistedFactory = SetupViewModel.Factory::class)
class SetupViewModel @AssistedInject constructor(
    @Assisted private val options: SetupScreenOptions,
    dispatcherProvider: DispatcherProvider,
    navCtrl: NavigationController,
    private val setupManager: SetupManager,
    private val webpageTool: WebpageTool,
) : ViewModel4(dispatcherProvider, logTag("Setup", "ViewModel"), navCtrl) {

    init {
        log(tag) { "init with options: $options" }
        setupManager.setOptions(options)
    }

    val state = setupManager.setupItems
        .map { items ->
            State(items = items)
        }
        .asStateFlow()

    fun refresh() = launch {
        log(tag) { "refresh()" }
        setupManager.refresh()
    }

    fun executeAction(type: SetupModule.Type, action: SetupAction) = launch {
        log(tag) { "executeAction(type=$type, action=$action)" }
        setupManager.executeAction(type, action)
    }

    fun openHelp(type: SetupModule.Type) = launch {
        log(tag) { "openHelp(type=$type)" }
        val helpUrl = getHelpUrl(type)
        webpageTool.open(helpUrl)
    }

    private fun getHelpUrl(type: SetupModule.Type): String {
        val baseUrl = "https://github.com/d4rken-org/butler/wiki"
        return when (type) {
            SetupModule.Type.ROOT -> "$baseUrl/Root-Access"
            SetupModule.Type.SHIZUKU -> "$baseUrl/Shizuku-Setup"
            SetupModule.Type.NOTIFICATION -> "$baseUrl/Notifications"
            SetupModule.Type.USAGE_STATS -> "$baseUrl/Usage-Stats"
            SetupModule.Type.SAF -> "$baseUrl/Storage-Access-Framework"
            SetupModule.Type.STORAGE -> "$baseUrl/Storage-Permissions"
            SetupModule.Type.INVENTORY -> "$baseUrl/App-Inventory"
        }
    }

    data class State(
        val items: List<SetupItem> = emptyList(),
    )

    @AssistedFactory
    interface Factory {
        fun create(options: SetupScreenOptions): SetupViewModel
    }
}