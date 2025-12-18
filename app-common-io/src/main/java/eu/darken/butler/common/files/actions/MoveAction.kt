package eu.darken.butler.common.files.actions

import eu.darken.butler.common.files.APath
import eu.darken.butler.common.files.APathLookup
import eu.darken.butler.common.progress.Progress
import kotlinx.coroutines.flow.Flow
import kotlin.time.Instant

interface MoveAction<
    SP : APath<SP>, SPL : APathLookup<SP>, // Source types
    DP : APath<DP>, DPL : APathLookup<DP>, // Destination types
    > {
    suspend fun move(
        sources: Set<SP>,
        destination: DP,
        onIssue: (suspend (PathActionIssue) -> PathActionIssue.Resolution)? = null,
        options: Options = Options()
    ): Flow<State<SP, SPL, DP, DPL>>

    data class Options(
        val preserveAttributes: Boolean = true,
        val overwrite: Boolean = false,
        val attemptAtomicMove: Boolean = true,
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
            val movedBytes: Long = 0L,
            val totalBytes: Long = 0L,
            val currentFileSize: Long = 0L,
            val currentFileBytes: Long = 0L,
            val currentFileStartTime: Instant? = null,
        ) : State<SP, SPL, DP, DPL>

        data class Completed<
            SP : APath<SP>, SPL : APathLookup<SP>, // Source types
            DP : APath<DP>, DPL : APathLookup<DP>, // Destination types
            >(
            val movedFiles: Set<Pair<SPL, APathLookup<DP>>>,
            val skippedFiles: Set<SPL> = emptySet(),
            val bytesMoved: Long,
        ) : State<SP, SPL, DP, DPL>
    }

}