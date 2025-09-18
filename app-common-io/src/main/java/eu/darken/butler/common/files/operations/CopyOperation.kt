package eu.darken.butler.common.files.operations

import eu.darken.butler.common.files.APath
import kotlinx.coroutines.flow.Flow

interface CopyOperation<P : APath> {
    suspend fun copy(
        source: P,
        destination: P,
        options: Options = Options()
    ): Flow<Result>

    data class Options(
        val overwrite: Boolean = false,
        val preserveAttributes: Boolean = true,
        val onIssue: (suspend (Issue) -> Issue.Resolution?)? = null,
        val onProgress: (suspend (Progress) -> Unit)? = null
    )

    data class Progress(
        val currentSource: APath,
        val currentDestination: APath,
        val totalFiles: Int,
        val filesProcessed: Int,
        val totalBytes: Long,
        val bytesCopied: Long
    )

    data class Result(
        val source: APath,
        val destination: APath,
        val filesCopied: Int,
        val bytesCopied: Long,
    )
}