package eu.darken.butler.upgrade.core

import eu.darken.butler.common.WebpageTool
import eu.darken.butler.common.datastore.value
import eu.darken.butler.common.debug.logging.log
import eu.darken.butler.common.debug.logging.logTag
import eu.darken.butler.common.flow.setupCommonEventHandlers
import eu.darken.butler.upgrade.UpgradeRepo
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.combine
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.time.Clock
import kotlin.time.Instant
import kotlin.uuid.Uuid

@Singleton
class UpgradeRepoFoss @Inject constructor(
    private val fossCache: FossCache,
    private val webpageTool: WebpageTool,
) : UpgradeRepo {

    override val mainWebsite: String = SITE

    private val refreshTrigger = MutableStateFlow(Uuid.random())

    override val upgradeInfo: Flow<UpgradeRepo.Info> = combine(
        fossCache.upgrade.flow,
        refreshTrigger
    ) { data, _ ->
        if (data == null) {
            Info()
        } else {
            Info(
                isUpgraded = true,
                upgradedAt = data.upgradedAt,
                fossUpgradeType = data.upgradeType,
            )
        }
    }
        .setupCommonEventHandlers(TAG) { "upgradeInfo" }

    fun openSponsorPage() {
        log(TAG) { "openSponsorPage()" }
        webpageTool.open(mainWebsite)
    }

    suspend fun applyUpgrade() {
        log(TAG) { "applyUpgrade()" }
        fossCache.upgrade.value(
            FossUpgrade(
                upgradedAt = Clock.System.now(),
                upgradeType = FossUpgrade.Type.GITHUB_SPONSORS,
            )
        )
    }

    override suspend fun refresh() {
        log(TAG) { "refresh()" }
        refreshTrigger.value = Uuid.random()
    }

    data class Info(
        override val isUpgraded: Boolean = false,
        override val upgradedAt: Instant? = null,
        val fossUpgradeType: FossUpgrade.Type? = null,
    ) : UpgradeRepo.Info {
        override val type: UpgradeRepo.Type = UpgradeRepo.Type.FOSS
    }

    companion object {
        private const val SITE = "https://github.com/sponsors/d4rken"
        private val TAG = logTag("Upgrade", "Foss", "Repo")
    }
}