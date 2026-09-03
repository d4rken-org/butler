package eu.darken.butler.workspace.ui.settings.clipboard

import dagger.hilt.android.lifecycle.HiltViewModel
import eu.darken.butler.common.coroutine.DispatcherProvider
import eu.darken.butler.common.datastore.value
import eu.darken.butler.common.debug.logging.logTag
import eu.darken.butler.common.flow.combine
import eu.darken.butler.common.navigation.Nav
import eu.darken.butler.common.navigation.upgrade
import eu.darken.butler.common.ui.ViewModel4
import eu.darken.butler.upgrade.UpgradeRepo
import eu.darken.butler.workspace.core.clipboard.ClipboardSettings
import javax.inject.Inject

@HiltViewModel
class ClipboardSettingsViewModel @Inject constructor(
    dispatcherProvider: DispatcherProvider,
    private val clipboardSettings: ClipboardSettings,
    upgradeRepo: UpgradeRepo,
) : ViewModel4(dispatcherProvider, logTag("Clipboard", "Settings", "Screen", "VM")) {

    val state = combine(
        clipboardSettings.removeOnPaste.flow,
        clipboardSettings.maxItems.flow,
        upgradeRepo.upgradeInfo,
    ) { removeOnPaste, maxItems, upgradeInfo ->
        State(
            removeOnPaste = removeOnPaste,
            maxItems = maxItems,
            isUpgraded = upgradeInfo.isPro,
        )
    }.asStateFlow()

    fun toggleRemoveOnPaste() = launch {
        val current = clipboardSettings.removeOnPaste.value()
        clipboardSettings.removeOnPaste.value(!current)
    }

    fun setMaxItems(count: Int) = launch {
        clipboardSettings.maxItems.value(count)
    }

    fun upgradeButler() = launch {
        navTo(Nav.Main.upgrade())
    }

    data class State(
        val removeOnPaste: Boolean,
        val maxItems: Int,
        val isUpgraded: Boolean,
    )
}
