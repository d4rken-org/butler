package eu.darken.butler.provider.documents.ui.settings

import dagger.hilt.android.lifecycle.HiltViewModel
import eu.darken.butler.common.coroutine.DispatcherProvider
import eu.darken.butler.common.datastore.value
import eu.darken.butler.common.debug.logging.Logging.Priority.INFO
import eu.darken.butler.common.debug.logging.log
import eu.darken.butler.common.debug.logging.logTag
import eu.darken.butler.common.ui.ViewModel4
import eu.darken.butler.provider.documents.core.DocumentsProviderSettings
import kotlinx.coroutines.flow.map
import javax.inject.Inject

@HiltViewModel
class DocumentsProviderSettingsViewModel @Inject constructor(
    dispatcherProvider: DispatcherProvider,
    private val documentsProviderSettings: DocumentsProviderSettings,
) : ViewModel4(
    dispatcherProvider,
    logTag("Settings", "DocumentsProvider", "ViewModel"),
) {

    val state = documentsProviderSettings.isEnabled.flow.map { isEnabled ->
        State(isEnabled = isEnabled)
    }.asStateFlow()

    fun updateEnabled(enabled: Boolean) = launch {
        log(tag, INFO) { "updateEnabled($enabled)" }
        documentsProviderSettings.isEnabled.value(enabled)
    }

    data class State(
        val isEnabled: Boolean = false,
    )
}
