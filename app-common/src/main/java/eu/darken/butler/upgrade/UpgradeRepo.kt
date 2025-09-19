package eu.darken.butler.upgrade

import kotlinx.coroutines.flow.Flow
import kotlin.time.Instant

interface UpgradeRepo {
    val mainWebsite: String

    val upgradeInfo: Flow<Info>

    suspend fun refresh()

    interface Info {
        val type: Type

        val isUpgraded: Boolean

        val upgradedAt: Instant?
    }

    enum class Type {
        GPLAY,
        FOSS
    }
}