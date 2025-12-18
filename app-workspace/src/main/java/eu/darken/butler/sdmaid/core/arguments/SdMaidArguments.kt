package eu.darken.butler.sdmaid.core.arguments

import eu.darken.butler.workspace.core.Workspace
import kotlinx.parcelize.Parcelize
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/**
 * Arguments for creating an SD Maid workspace.
 */
@Serializable
sealed interface SdMaidArguments : Workspace.Arguments {
    override val type: Workspace.Type get() = Workspace.Type.SDMAID

    @Serializable
    @SerialName("default")
    @Parcelize
    data class Default(
        val initialTool: ToolType? = null,
    ) : SdMaidArguments

    /**
     * SD Maid tool types that can be launched from Butler.
     */
    enum class ToolType {
        APP_CLEANER,
        SYSTEM_CLEANER,
        CORPSE_FINDER,
        STORAGE_ANALYZER,
    }
}
