package eu.darken.butler.common.files.actions

import eu.darken.butler.common.files.APath
import eu.darken.butler.common.files.APathLookup
import kotlinx.coroutines.flow.Flow
import kotlin.time.Instant

interface CopyAction<P : APath<P>, PL : APathLookup<P>> : GatewayAction<P> {
    suspend fun copy(
        sources: Set<P>,
        destination: P,
        onIssue: (suspend (PathActionIssue) -> PathActionIssue.Resolution)? = null,
        options: Options<P> = Options()
    ): Flow<State<P, PL>>

    data class Options<P : APath<P>>(
        val overwrite: Boolean = false,
        val preserveAttributes: Boolean = true,
        val followSymlinks: Boolean = false,
    )

    sealed interface State<P : APath<P>, PL : APathLookup<P>> {
        data class Progress<P : APath<P>, PL : APathLookup<P>>(
            val currentSource: P,
            val currentDestination: P,
            val primaryProgress: eu.darken.butler.common.progress.Progress.Data,
            val secondaryProgress: eu.darken.butler.common.progress.Progress.Data? = null,
            val copiedBytes: Long = 0L,
            val totalBytes: Long = 0L,
            val currentFileSize: Long = 0L,
            val currentFileBytes: Long = 0L,
            val currentFileStartTime: Instant? = null,
        ) : State<P, PL>

        data class Result<P : APath<P>, PL : APathLookup<P>>(
            val copied: Set<Pair<P, P>>,
            val skipped: Set<P> = emptySet(),
            val copiedBytes: Long,
        ) : State<P, PL>
    }
}