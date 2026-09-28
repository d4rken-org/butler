package eu.darken.butler.viewer.ui.settings

import dagger.hilt.android.lifecycle.HiltViewModel
import eu.darken.butler.common.coroutine.DispatcherProvider
import eu.darken.butler.common.datastore.value
import eu.darken.butler.common.debug.logging.log
import eu.darken.butler.common.debug.logging.logTag
import eu.darken.butler.common.ui.ViewModel4
import eu.darken.butler.viewer.core.ViewerSettings
import kotlinx.coroutines.flow.map
import javax.inject.Inject

@HiltViewModel
class ViewerSettingsViewModel @Inject constructor(
    dispatcherProvider: DispatcherProvider,
    private val viewerSettings: ViewerSettings,
) : ViewModel4(dispatcherProvider, logTag("Viewer", "Settings", "ViewModel")) {

    val state = viewerSettings.showNextAfterDelete.flow
        .map { State(showNextAfterDelete = it) }
        .asStateFlow()

    fun updateShowNextAfterDelete(enabled: Boolean) = launch {
        log(tag) { "updateShowNextAfterDelete($enabled)" }
        viewerSettings.showNextAfterDelete.value(enabled)
    }

    data class State(
        val showNextAfterDelete: Boolean = true,
    )
}
