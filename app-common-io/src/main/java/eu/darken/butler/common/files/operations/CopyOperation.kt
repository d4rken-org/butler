package eu.darken.butler.common.files.operations

import eu.darken.butler.common.files.APath
import kotlinx.coroutines.flow.Flow

interface CopyOperation<P : APath> : GatewayOperation<P> {
    suspend fun copy(
        source: P,
        destination: P,
        options: Options<P> = Options()
    ): Flow<Result<P>>

    data class Options<P : APath>(
        val overwrite: Boolean = false,
        val preserveAttributes: Boolean = true,
        val onIssue: (suspend (Issue) -> Issue.Resolution?)? = null,
        val onProgress: (suspend (Progress<P>) -> Unit)? = null
    )

    data class Progress<P : APath>(
        val currentSource: P,
        val currentDestination: P,
        val totalFiles: Int,
        val filesProcessed: Int,
        val totalBytes: Long,
        val bytesCopied: Long
    )

    data class Result<P : APath>(
        val copiedFiles: Set<Pair<P, P>>,
        val bytesCopied: Long,
        val averageBytesPerSecond: Long,
        val peakBytesPerSecond: Long,
    )
}