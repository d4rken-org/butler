package eu.darken.butler.main.ui.settings

import dagger.hilt.android.lifecycle.HiltViewModel
import eu.darken.butler.common.BuildConfigWrap
import eu.darken.butler.common.WebpageTool
import eu.darken.butler.common.coroutine.DispatcherProvider
import eu.darken.butler.common.debug.logging.log
import eu.darken.butler.common.debug.logging.logTag
import eu.darken.butler.common.pkgs.SDMaidTool
import eu.darken.butler.common.ui.ViewModel4
import eu.darken.butler.upgrade.UpgradeRepo
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.map
import javax.inject.Inject

@HiltViewModel
class SettingsViewModel @Inject constructor(
    dispatcherProvider: DispatcherProvider,
    private val webpageTool: WebpageTool,
    private val upgradeRepo: UpgradeRepo,
    private val sdMaidTool: SDMaidTool,
) : ViewModel4(dispatcherProvider, logTag("Settings", "ViewModel")) {

    val state = flow {
        val isSDMaidInstalled = sdMaidTool.isInstalled()
        upgradeRepo.upgradeInfo.collect { upgradeInfo ->
            emit(
                State(
                    versionText = BuildConfigWrap.VERSION_DESCRIPTION,
                    isUpgraded = upgradeInfo.isUpgraded,
                    isSDMaidInstalled = isSDMaidInstalled,
                )
            )
        }
    }.asStateFlow()

    fun openUrl(url: String) {
        log(tag) { "openUrl($url)" }
        webpageTool.open(url)
    }

    fun openSDMaidInstall() {
        log(tag) { "openSDMaidInstall()" }
        sdMaidTool.openInstallPage()
    }

    data class State(
        val versionText: String = BuildConfigWrap.VERSION_DESCRIPTION,
        val isUpgraded: Boolean = false,
        val isSDMaidInstalled: Boolean = true,
    )
}