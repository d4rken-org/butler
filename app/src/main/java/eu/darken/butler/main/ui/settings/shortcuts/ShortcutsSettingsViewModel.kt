package eu.darken.butler.main.ui.settings.shortcuts

import dagger.hilt.android.lifecycle.HiltViewModel
import eu.darken.butler.R
import eu.darken.butler.common.ca.CaString
import eu.darken.butler.common.ca.toCaString
import eu.darken.butler.common.coroutine.DispatcherProvider
import eu.darken.butler.common.datastore.value
import eu.darken.butler.common.debug.logging.Logging.Priority.*
import eu.darken.butler.common.debug.logging.log
import eu.darken.butler.common.debug.logging.logTag
import eu.darken.butler.common.flow.combine
import eu.darken.butler.common.ui.ViewModel4
import eu.darken.butler.main.core.shortcuts.LastAccessedPaths
import eu.darken.butler.main.core.shortcuts.ShortcutRepo
import eu.darken.butler.main.core.shortcuts.ShortcutSettings
import kotlinx.coroutines.flow.MutableStateFlow
import javax.inject.Inject

@HiltViewModel
class ShortcutsSettingsViewModel @Inject constructor(
    dispatcherProvider: DispatcherProvider,
    private val shortcutSettings: ShortcutSettings,
    private val shortcutRepo: ShortcutRepo,
) : ViewModel4(dispatcherProvider, logTag("Shortcuts", "Settings", "VM")) {

    private val eventFlow = MutableStateFlow<Event?>(null)

    val state = combine(
        shortcutSettings.isEnabled.flow,
        shortcutSettings.autoRememberEnabled.flow,
        shortcutSettings.maxShortcuts.flow,
        shortcutSettings.minAccessCount.flow,
        shortcutSettings.lastAccessedData.flow,
        eventFlow,
    ) { isEnabled, autoRememberEnabled, maxShortcuts, minAccess, lastAccessedData, event ->
        State(
            isEnabled = isEnabled,
            autoRememberEnabled = autoRememberEnabled,
            maxShortcuts = maxShortcuts,
            minAccessCount = minAccess,
            currentShortcuts = lastAccessedData.paths.size,
            lastEvent = event,
        )
    }.asStateFlow()

    data class State(
        val isEnabled: Boolean = true,
        val autoRememberEnabled: Boolean = true,
        val maxShortcuts: Int = 3,
        val minAccessCount: Int = 3,
        val currentShortcuts: Int = 0,
        val lastEvent: Event? = null,
    )

    sealed interface Event {
        data class ShortcutsCleared(val message: CaString) : Event
        data object EventConsumed : Event
    }

    fun updateEnabled(enabled: Boolean) = launch {
        log(tag, INFO) { "updateEnabled($enabled)" }
        shortcutSettings.isEnabled.value(enabled)
    }

    fun updateAutoRemember(enabled: Boolean) = launch {
        log(tag, INFO) { "updateAutoRemember($enabled)" }
        shortcutSettings.autoRememberEnabled.value(enabled)
    }

    fun updateMaxShortcuts(count: Int) = launch {
        log(tag, INFO) { "updateMaxShortcuts($count)" }
        shortcutSettings.maxShortcuts.value(count)
    }

    fun updateMinAccessCount(count: Int) = launch {
        log(tag, INFO) { "updateMinAccessCount($count)" }
        shortcutSettings.minAccessCount.value(count)
    }

    fun clearShortcuts() = launch {
        log(tag, INFO) { "clearShortcuts()" }
        shortcutSettings.lastAccessedData.value(LastAccessedPaths())
        eventFlow.value = Event.ShortcutsCleared(
            R.string.shortcuts_settings_cleared_message.toCaString()
        )
    }

    fun onEvent(event: Event) {
        if (event is Event.EventConsumed) {
            eventFlow.value = null
        }
    }
}
