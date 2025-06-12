package eu.darken.butler.main.ui.settings

import dagger.hilt.android.lifecycle.HiltViewModel
import eu.darken.butler.common.BuildConfigWrap
import eu.darken.butler.common.ButlerLinks
import eu.darken.butler.common.WebpageTool
import eu.darken.butler.common.coroutine.DispatcherProvider
import eu.darken.butler.common.debug.logging.log
import eu.darken.butler.common.debug.logging.logTag
import eu.darken.butler.common.navigation.NavigationController
import eu.darken.butler.common.ui.ViewModel4
import kotlinx.coroutines.flow.flowOf
import javax.inject.Inject

@HiltViewModel
class SettingsViewModel @Inject constructor(
    dispatcherProvider: DispatcherProvider,
    navCtrl: NavigationController,
    private val webpageTool: WebpageTool,
) : ViewModel4(dispatcherProvider, logTag("Settings", "ViewModel"), navCtrl) {

    val state = flowOf(State()).asStateFlow()

    fun openChangelog() = launch {
        log(tag) { "openChangelog()" }
        webpageTool.open(ButlerLinks.CHANGELOG)
    }

    data class State(
        val versionText: String = BuildConfigWrap.VERSION_DESCRIPTION,
    )
}