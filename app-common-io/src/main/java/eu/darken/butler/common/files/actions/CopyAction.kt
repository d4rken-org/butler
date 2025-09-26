package eu.darken.butler.common.files.actions

import eu.darken.butler.common.files.APath
import kotlinx.coroutines.flow.Flow

interface CopyAction<P : APath> : GatewayAction<P> {
    suspend fun copy(
        sources: Set<P>,
        destination: P,
        options: Options<P> = Options()
    ): Flow<State<P>>

    data class Options<P : APath>(
        val overwrite: Boolean = false,
        val preserveAttributes: Boolean = true,
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
            val bytesCopied: Long
        ) : State<P>

        data class Result<P : APath>(
            val copiedFiles: Set<Pair<P, P>>,
            val skippedFiles: Set<P> = emptySet(),
            val totalBytesCopied: Long,
        ) : State<P>
    }
}