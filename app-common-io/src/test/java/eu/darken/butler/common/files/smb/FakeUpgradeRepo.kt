package eu.darken.butler.common.files.smb

import eu.darken.butler.upgrade.UpgradeRepo
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlin.time.Instant

/**
 * Backed by a MutableStateFlow, like the production repos. A cold single-element flow would end
 * `isProForUi`'s wait in a NoSuchElementException, which its catch turns into an allow - an
 * unsettled state would then pass through the fake instead of through the gate.
 */
class FakeUpgradeRepo(
    pro: Boolean = true,
    settled: Boolean = true,
) : UpgradeRepo {

    private class FakeInfo(
        override val isPro: Boolean,
        override val isSettled: Boolean,
    ) : UpgradeRepo.Info {
        override val type = UpgradeRepo.Type.FOSS
        override val upgradedAt: Instant? = null
        override val error: Throwable? = null
    }

    override val storeSite = ""
    override val upgradeSite = ""
    override val betaSite = ""
    override val upgradeInfo: Flow<UpgradeRepo.Info> = MutableStateFlow(FakeInfo(pro, settled))

    override suspend fun refresh() = Unit
}
