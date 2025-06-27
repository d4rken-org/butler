package eu.darken.butler.explorer.core

import eu.darken.butler.common.files.APath

sealed interface ExplorerEntry {
    val path: APath
    val displayName: String
    val isNavigable: Boolean

    data class Directory(
        override val path: APath,
        override val displayName: String,
        val itemCount: Int? = null,
    ) : ExplorerEntry {
        override val isNavigable: Boolean = true
    }

    data class SyntheticOverview(
        override val path: APath,
        override val displayName: String,
        val quickAccessItems: List<QuickAccessItem> = emptyList(),
    ) : ExplorerEntry {
        override val isNavigable: Boolean = false

        data class QuickAccessItem(
            val path: APath,
            val displayName: String,
            val icon: String? = null,
        )
    }

    data class CloudRoot(
        override val path: APath,
        override val displayName: String,
        val provider: String,
        val isConnected: Boolean = false,
    ) : ExplorerEntry {
        override val isNavigable: Boolean = isConnected
    }

    data class DeviceRoot(
        override val path: APath,
        override val displayName: String,
        val accessMethod: AccessMethod,
    ) : ExplorerEntry {
        override val isNavigable: Boolean = true

        enum class AccessMethod {
            LOCAL, ROOT, ADB, SHELL
        }
    }
}