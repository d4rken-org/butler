package eu.darken.butler.main.ui.settings

import dagger.hilt.android.lifecycle.HiltViewModel
import eu.darken.butler.common.BuildConfigWrap
import eu.darken.butler.common.WebpageTool
import eu.darken.butler.common.coroutine.DispatcherProvider
import eu.darken.butler.common.debug.logging.log
import eu.darken.butler.common.debug.logging.logTag
import eu.darken.butler.common.ui.ViewModel4
import eu.darken.butler.upgrade.UpgradeRepo
import kotlinx.coroutines.flow.map
import javax.inject.Inject

@HiltViewModel
class SettingsViewModel @Inject constructor(
    dispatcherProvider: DispatcherProvider,
    private val webpageTool: WebpageTool,
    private val upgradeRepo: UpgradeRepo,
) : ViewModel4(dispatcherProvider, logTag("Settings", "ViewModel")) {

    val state = upgradeRepo.upgradeInfo
        .map { upgradeInfo ->
            State(
                versionText = BuildConfigWrap.VERSION_DESCRIPTION,
                isUpgraded = upgradeInfo.isUpgraded
            )
        }
        .asStateFlow()

    fun openUrl(url: String) {
        log(tag) { "openUrl($url)" }
        webpageTool.open(url)
    }

    data class State(
        val versionText: String = BuildConfigWrap.VERSION_DESCRIPTION,
        val isUpgraded: Boolean = false,
    )
}