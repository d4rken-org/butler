package eu.darken.butler.common.pkgs.installer

import eu.darken.butler.common.pkgs.Pkg

/**
 * What an install run reports. Every path ends in exactly one [Success] or [Failure]; the flow never
 * completes without a terminal event.
 */
sealed interface AppInstallEvent {

    data class Progress(
        val stage: Stage,
        val current: Long,
        val total: Long,
        val label: String? = null,
    ) : AppInstallEvent

    /** Expansion files could not be placed. The install itself already succeeded. */
    data class ObbFailed(val reason: String) : AppInstallEvent

    /**
     * The platform installer is waiting for the user's confirmation. The run stays alive until they
     * answer; [issue] is how they get back to the dialog if it never appeared or was dismissed.
     */
    data class ConfirmationRequired(val issue: AppInstallConfirmationIssue) : AppInstallEvent

    data class Success(
        val pkgId: Pkg.Id?,
        val viaMode: AppInstaller.Mode,
        val obbPlaced: Boolean,
    ) : AppInstallEvent

    data class Failure(val error: Throwable) : AppInstallEvent

    enum class Stage { INSPECTING, EXTRACTING, WRITING, COMMITTING, PLACING_OBB }
}
