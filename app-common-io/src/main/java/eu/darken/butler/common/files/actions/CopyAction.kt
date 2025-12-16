package eu.darken.butler.common.files.actions

import eu.darken.butler.common.files.APath
import eu.darken.butler.common.files.APathLookup
import eu.darken.butler.common.progress.Progress
import kotlinx.coroutines.flow.Flow
import kotlin.time.Instant

interface CopyAction<
    SP : APath<SP>, SPL : APathLookup<SP>, // Source types
    DP : APath<DP>, DPL : APathLookup<DP>, // Destination types
    > {
    suspend fun copy(
        sources: Set<SP>,
        destination: DP,
        onIssue: (suspend (PathActionIssue) -> PathActionIssue.Resolution)? = null,
        options: Options = Options()
    ): Flow<State<SP, SPL, DP, DPL>>

    data class Options(
        val overwrite: Boolean = false,
        val preserveAttributes: Boolean = true,
        val followSymlinks: Boolean = false,
    )

    sealed interface State<
        SP : APath<SP>, SPL : APathLookup<SP>, // Source types
        DP : APath<DP>, DPL : APathLookup<DP>, // Destination types
        > {
        data class Active<
            SP : APath<SP>, SPL : APathLookup<SP>, // Source types
            DP : APath<DP>, DPL : APathLookup<DP>, // Destination types
            >(
            val currentSource: SPL,
            val currentDestination: DP?,
            val primaryProgress: Progress.Data,
            val secondaryProgress: Progress.Data? = null,
            val copiedBytes: Long = 0L,
            val totalBytes: Long = 0L,
            val currentFileSize: Long = 0L,
            val currentFileBytes: Long = 0L,
            val currentFileStartTime: Instant? = null,
        ) : State<SP, SPL, DP, DPL>

        data class Completed<
            SP : APath<SP>, SPL : APathLookup<SP>, // Source types
            DP : APath<DP>, DPL : APathLookup<DP>, // Destination types
            >(
            val copied: Set<Pair<SPL, APathLookup<DP>>>,
            val skipped: Set<SPL> = emptySet(),
            val copiedBytes: Long,
        ) : State<SP, SPL, DP, DPL>
    }
}