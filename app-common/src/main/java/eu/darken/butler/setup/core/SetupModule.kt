package eu.darken.butler.setup.core

import androidx.annotation.StringRes
import eu.darken.butler.common.R
import kotlinx.coroutines.flow.Flow
import kotlinx.serialization.Serializable
import kotlin.time.Instant

interface SetupModule {
    val type: Type
    val state: Flow<State>

    suspend fun refresh()

    sealed interface State {
        val type: Type

        interface Loading : State {
            val startAt: Instant
        }

        interface Current : State {
            val isComplete: Boolean
            val isAvailable: Boolean
                get() = true

            /** Whether the backing app/binary is present (distinct from available/granted). */
            val isInstalled: Boolean
                get() = false
        }
    }

    @Serializable
    enum class Type(
        @StringRes val labelRes: Int,
        val helpUrl: String,
    ) {
        USAGE_STATS(R.string.setup_usagestats_title, "https://butler.darken.eu/setup#usage-statistics"),
        SHIZUKU(R.string.setup_shizuku_card_title, "https://butler.darken.eu/setup#adb-access"),
        ROOT(R.string.setup_root_card_title, "https://butler.darken.eu/setup#root-access"),
        NOTIFICATION(R.string.setup_notification_title, "https://butler.darken.eu/setup#notifications"),
        STORAGE(R.string.setup_manage_storage_card_title, "https://butler.darken.eu/setup#storage-permissions"),
        INVENTORY(R.string.setup_inventory_card_title, "https://butler.darken.eu/setup#app-inventory"),
    }
}