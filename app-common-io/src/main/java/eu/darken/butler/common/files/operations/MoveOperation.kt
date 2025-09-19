package eu.darken.butler.common.files.operations

import eu.darken.butler.common.files.APath
import kotlinx.coroutines.flow.Flow


interface MoveOperation<P : APath> {
    suspend fun move(
        source: P,
        destination: P,
        options: Options = Options()
    ): Flow<Result>

    data class Options(
        val overwrite: Boolean = false,
        val onIssue: (suspend (Issue) -> Issue.Resolution?)? = null,
        val onProgress: (suspend (Progress) -> Unit)? = null
    )

    data class Progress(
        val currentSource: APath,
        val currentDestination: APath,
        val totalFiles: Int,
        val filesProcessed: Int,
    )

    data class Result(
        val source: APath,
        val destination: APath,
        val filesMoved: Int,
        val skipped: List<APath> = emptyList(),
        val failures: Map<APath, Exception> = emptyMap()
    )

}