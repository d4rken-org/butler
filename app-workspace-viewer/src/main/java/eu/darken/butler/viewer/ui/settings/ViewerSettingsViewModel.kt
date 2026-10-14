package eu.darken.butler.viewer.ui.settings

import dagger.hilt.android.lifecycle.HiltViewModel
import eu.darken.butler.common.coroutine.DispatcherProvider
import eu.darken.butler.common.datastore.value
import eu.darken.butler.common.debug.logging.log
import eu.darken.butler.common.debug.logging.logTag
import eu.darken.butler.common.ui.ViewModel4
import eu.darken.butler.viewer.core.ViewerSettings
import kotlinx.coroutines.flow.combine
import javax.inject.Inject

@HiltViewModel
class ViewerSettingsViewModel @Inject constructor(
    dispatcherProvider: DispatcherProvider,
    private val viewerSettings: ViewerSettings,
) : ViewModel4(dispatcherProvider, logTag("Viewer", "Settings", "ViewModel")) {

    val state = combine(
        viewerSettings.showNextAfterDelete.flow,
        viewerSettings.swipeBetweenFiles.flow,
    ) { showNextAfterDelete, swipeBetweenFiles ->
        State(
            showNextAfterDelete = showNextAfterDelete,
            swipeBetweenFiles = swipeBetweenFiles,
        )
    }.asStateFlow()

    fun updateShowNextAfterDelete(enabled: Boolean) = launch {
        log(tag) { "updateShowNextAfterDelete($enabled)" }
        viewerSettings.showNextAfterDelete.value(enabled)
    }

    fun updateSwipeBetweenFiles(enabled: Boolean) = launch {
        log(tag) { "updateSwipeBetweenFiles($enabled)" }
        viewerSettings.swipeBetweenFiles.value(enabled)
    }

    data class State(
        val showNextAfterDelete: Boolean = true,
        val swipeBetweenFiles: Boolean = true,
    )
}
