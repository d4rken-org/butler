package eu.darken.butler.common.files.actions

import eu.darken.butler.common.files.APath
import kotlinx.coroutines.flow.Flow


interface MoveAction<P : APath> : GatewayAction<P> {
    suspend fun move(
        sources: Set<P>,
        destination: P,
        options: Options<P> = Options()
    ): Flow<State<P>>

    data class Options<P : APath>(
        val overwrite: Boolean = false,
        val onIssue: (suspend (PathActionIssue) -> PathActionIssue.Resolution?)? = null,
    )

    sealed interface State<P : APath> {
        data class Progress<P : APath>(
            val currentSource: P,
            val currentDestination: P,
            val totalSources: Int,
            val sourcesCompleted: Int,
            val totalFiles: Int,
            val filesProcessed: Int,
            val totalBytes: Long,
            val bytesMoved: Long
        ) : State<P>

        data class Result<P : APath>(
            val movedFiles: Set<Pair<P, P>>,
            val skippedFiles: Set<P> = emptySet(),
            val bytesMoved: Long,
        ) : State<P>
    }

}