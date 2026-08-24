package eu.darken.butler.workspace.contracts.explorer

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/**
 * Non-directory navigation target an Explorer tab was parked on, persisted so a restored tab can
 * name itself before it is instantiated. A directory target is represented by
 * [ExplorerArguments.startPath] instead.
 */
@Serializable
enum class ExplorerStartTarget {
    @SerialName("home") HOME,
    @SerialName("device") DEVICE,
    @SerialName("network") NETWORK,
    @SerialName("trash") TRASH,
    ;
}
