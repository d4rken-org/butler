package eu.darken.butler.upgrade.ui

import dagger.hilt.android.lifecycle.HiltViewModel
import eu.darken.butler.common.coroutine.DispatcherProvider
import eu.darken.butler.common.debug.logging.logTag
import eu.darken.butler.common.navigation.Nav
import eu.darken.butler.common.navigation.NavigationController
import eu.darken.butler.common.navigation.upgrade
import eu.darken.butler.common.ui.ViewModel4
import eu.darken.butler.upgrade.UpgradeRepo
import kotlinx.coroutines.flow.map
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.time.format.FormatStyle
import javax.inject.Inject

@HiltViewModel
class UpgradeStatusViewModel @Inject constructor(
    dispatchers: DispatcherProvider,
    navCtrl: NavigationController,
    private val upgradeRepo: UpgradeRepo,
) : ViewModel4(dispatchers, logTag("Upgrade", "Status"), navCtrl) {

    val state = upgradeRepo.upgradeInfo
        .map { info ->
            State(
                isUpgraded = info.isUpgraded,
                upgradeType = info.type,
                upgradedAt = info.upgradedAt,
                upgradedAtFormatted = info.upgradedAt?.let { formatDate(it) }
            )
        }
        .asStateFlow()

    private fun formatDate(instant: Instant): String {
        val formatter = DateTimeFormatter
            .ofLocalizedDate(FormatStyle.LONG)
            .withZone(ZoneId.systemDefault())
        return formatter.format(instant)
    }

    fun onUpgradeClick() {
        navTo(Nav.Main.upgrade())
    }

    data class State(
        val isUpgraded: Boolean,
        val upgradeType: UpgradeRepo.Type,
        val upgradedAt: Instant?,
        val upgradedAtFormatted: String?,
    )
}