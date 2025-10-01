package eu.darken.butler.common.files.actions

import eu.darken.butler.common.files.APath
import eu.darken.butler.common.files.APathLookup
import kotlinx.coroutines.flow.Flow

interface CopyAction<P : APath, PL : APathLookup<P>> : GatewayAction<P> {
    suspend fun copy(
        sources: Set<P>,
        destination: P,
        options: Options<P> = Options()
    ): Flow<State<P, PL>>

    data class Options<P : APath>(
        val overwrite: Boolean = false,
        val preserveAttributes: Boolean = true,
        val followSymlinks: Boolean = false,
        val onIssue: (suspend (PathActionIssue) -> PathActionIssue.Resolution)? = null,
    )

    sealed interface State<P : APath, PL : APathLookup<P>> {
        data class Progress<P : APath, PL : APathLookup<P>>(
            val currentSource: P,
            val currentDestination: P,
            val primaryProgress: eu.darken.butler.common.progress.Progress.Data,
            val secondaryProgress: eu.darken.butler.common.progress.Progress.Data? = null,
            val bytesCopied: Long,
        ) : State<P, PL>

        data class Result<P : APath, PL : APathLookup<P>>(
            val copied: Set<Pair<P, P>>,
            val skipped: Set<P> = emptySet(),
            val bytesCopied: Long,
        ) : State<P, PL>
    }
}