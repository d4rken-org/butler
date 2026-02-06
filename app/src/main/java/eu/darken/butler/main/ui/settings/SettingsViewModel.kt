package eu.darken.butler.main.ui.settings

import dagger.hilt.android.lifecycle.HiltViewModel
import eu.darken.butler.common.BuildConfigWrap
import eu.darken.butler.common.WebpageTool
import eu.darken.butler.common.coroutine.DispatcherProvider
import eu.darken.butler.common.datastore.value
import eu.darken.butler.common.debug.logging.Logging.Priority.*
import eu.darken.butler.common.debug.logging.log
import eu.darken.butler.common.debug.logging.logTag
import eu.darken.butler.common.developer.DeveloperSettings
import eu.darken.butler.common.ui.ViewModel4
import eu.darken.butler.upgrade.UpgradeRepo
import kotlinx.coroutines.flow.combine
import javax.inject.Inject

@HiltViewModel
class SettingsViewModel @Inject constructor(
    dispatcherProvider: DispatcherProvider,
    private val webpageTool: WebpageTool,
    private val upgradeRepo: UpgradeRepo,
    private val developerSettings: DeveloperSettings,
) : ViewModel4(dispatcherProvider, logTag("Settings", "ViewModel")) {

    val state = combine(
        upgradeRepo.upgradeInfo,
        developerSettings.isDeveloperModeUnlocked.flow,
    ) { upgradeInfo, isDeveloperUnlocked ->
        State(
            versionText = BuildConfigWrap.VERSION_DESCRIPTION,
            isUpgraded = upgradeInfo.isUpgraded,
            isDeveloperModeUnlocked = isDeveloperUnlocked,
            canUnlockDeveloperMode = !isDeveloperUnlocked,
        )
    }.asStateFlow()

    fun openUrl(url: String) {
        log(tag) { "openUrl($url)" }
        webpageTool.open(url)
    }

    fun unlockDeveloperMode() = launch {
        log(tag, INFO) { "Unlocking developer mode" }
        developerSettings.isDeveloperModeUnlocked.value(true)
    }

    data class State(
        val versionText: String = BuildConfigWrap.VERSION_DESCRIPTION,
        val isUpgraded: Boolean = false,
        val isDeveloperModeUnlocked: Boolean = false,
        val canUnlockDeveloperMode: Boolean = false,
    )
}