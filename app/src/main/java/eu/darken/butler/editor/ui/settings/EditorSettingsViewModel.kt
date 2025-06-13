package eu.darken.butler.editor.ui.settings

import dagger.hilt.android.lifecycle.HiltViewModel
import eu.darken.butler.common.coroutine.DispatcherProvider
import eu.darken.butler.common.datastore.value
import eu.darken.butler.common.debug.logging.log
import eu.darken.butler.common.debug.logging.logTag
import eu.darken.butler.common.navigation.NavigationController
import eu.darken.butler.common.ui.ViewModel4
import eu.darken.butler.editor.core.EditorSettings
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flowOf
import javax.inject.Inject

@HiltViewModel
class EditorSettingsViewModel
@Inject
constructor(
    dispatcherProvider: DispatcherProvider,
    navCtrl: NavigationController,
    private val editorSettings: EditorSettings,
) : ViewModel4(dispatcherProvider, logTag("Editor", "Settings", "ViewModel"), navCtrl) {

    val state = combine(
        editorSettings.showLineNumbers.flow,
        flowOf(Unit),
    ) { showLineNumbers, _ ->
        State(
            showLineNumbers = showLineNumbers,
        )
    }.asStateFlow()


    fun updateShowLineNumbers(enabled: Boolean) = launch {
        log(tag) { "updateShowLineNumbers($enabled)" }
        editorSettings.showLineNumbers.value(enabled)
    }

    data class State(
        val showLineNumbers: Boolean = true,
    )
}