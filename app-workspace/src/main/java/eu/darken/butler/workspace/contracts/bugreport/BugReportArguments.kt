package eu.darken.butler.workspace.contracts.bugreport

import eu.darken.butler.workspace.core.Workspace
import kotlinx.parcelize.Parcelize
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/**
 * Arguments for the Bug Report workspace. Singleton + stateless: it always lists all locally-stored
 * reports, so there is nothing to carry across creation or session restore.
 */
@Serializable
sealed interface BugReportArguments : Workspace.Arguments {
    override val type: Workspace.Type get() = Workspace.Type.BUG_REPORT

    @Serializable
    @SerialName("arguments")
    @Parcelize
    data class Default(
        val placeholder: String = "",
    ) : BugReportArguments
}
