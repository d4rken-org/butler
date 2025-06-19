package eu.darken.butler.common.upgrade.ui

import dagger.hilt.android.lifecycle.HiltViewModel
import eu.darken.butler.common.coroutine.DispatcherProvider
import eu.darken.butler.common.debug.logging.log
import eu.darken.butler.common.debug.logging.logTag
import eu.darken.butler.common.navigation.NavigationController
import eu.darken.butler.common.ui.ViewModel4
import eu.darken.butler.common.upgrade.core.UpgradeRepoFoss
import kotlinx.coroutines.flow.filter
import kotlinx.coroutines.flow.first
import javax.inject.Inject

@HiltViewModel
class UpgradeViewModel @Inject constructor(
    dispatcherProvider: DispatcherProvider,
    navCtrl: NavigationController,
    private val upgradeRepo: UpgradeRepoFoss,
) : ViewModel4(dispatcherProvider, logTag("Upgrade","Screen","VM"), navCtrl) {

    fun openSponsor() = launch {
        log(tag) { "openSponsor()" }
        upgradeRepo.launchGithubSponsorsUpgrade()
        upgradeRepo.upgradeInfo.filter { it.isUpgraded }.first()
        navUp()
    }
}
