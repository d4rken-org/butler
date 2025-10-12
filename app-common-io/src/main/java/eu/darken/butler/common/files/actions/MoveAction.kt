package eu.darken.butler.common.files.actions

import eu.darken.butler.common.files.APath
import eu.darken.butler.common.files.APathLookup
import kotlinx.coroutines.flow.Flow
import kotlin.time.Instant

interface MoveAction<P : APath, PL : APathLookup<P>> : GatewayAction<P> {
    suspend fun move(
        sources: Set<P>,
        destination: P,
        options: Options<P> = Options()
    ): Flow<State<P, PL>>

    data class Options<P : APath>(
        val preserveAttributes: Boolean = true,
        val overwrite: Boolean = false,
        val onIssue: (suspend (PathActionIssue) -> PathActionIssue.Resolution)? = null,
    )

    sealed interface State<out P : APath, out PL : APathLookup<P>> {
        data class Progress<out P : APath, out PL : APathLookup<P>>(
            val currentSource: P,
            val currentDestination: P,
            val primaryProgress: eu.darken.butler.common.progress.Progress.Data,
            val secondaryProgress: eu.darken.butler.common.progress.Progress.Data? = null,
            val movedBytes: Long,
            val totalBytes: Long = 0L,
            val currentFileSize: Long = 0L,
            val currentFileBytes: Long = 0L,
            val currentFileStartTime: Instant? = null,
        ) : State<P, PL>

        data class Result<out P : APath, out PL : APathLookup<P>>(
            val movedFiles: Set<Pair<P, P>>,
            val skippedFiles: Set<P> = emptySet(),
            val bytesMoved: Long,
        ) : State<P, PL>
    }

}