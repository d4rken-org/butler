package eu.darken.butler.common.files.operations

import eu.darken.butler.common.files.APath
import kotlinx.coroutines.flow.Flow


interface MoveOperation<P : APath> : GatewayOperation<P> {
    suspend fun move(
        source: P,
        destination: P,
        options: Options<P> = Options()
    ): Flow<Result<P>>

    data class Options<P : APath>(
        val overwrite: Boolean = false,
        val onIssue: (suspend (Issue) -> Issue.Resolution?)? = null,
        val onProgress: (suspend (Progress<P>) -> Unit)? = null
    )

    data class Progress<P : APath>(
        val currentSource: P,
        val currentDestination: P,
        val totalFiles: Int,
        val filesProcessed: Int,
    )

    data class Result<P : APath>(
        val movedFiles: Set<Pair<P, P>>,
        val bytesMoved: Long,
    )

}