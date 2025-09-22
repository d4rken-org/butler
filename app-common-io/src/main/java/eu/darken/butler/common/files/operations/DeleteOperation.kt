package eu.darken.butler.common.files.operations

import eu.darken.butler.common.files.APath
import kotlinx.coroutines.flow.Flow


interface DeleteOperation<P : APath> : GatewayOperation<P> {
    suspend fun delete(
        targets: Set<P>,
        options: Options<P> = Options()
    ): Flow<State<P>>

    data class Options<P : APath>(
        val recursive: Boolean = false,
        val ignoreMissing: Boolean = true,
        val onIssue: (suspend (Issue) -> Issue.Resolution)? = null,
    )

    sealed interface State<P : APath> {
        data class Progress<P : APath>(
            val target: P,
            val targetSize: Long,
            val pathsCurrent: Int,
            val pathsTotal: Int,
            val bytesCurrent: Long,
        ) : State<P>

        data class Result<P : APath>(
            val deleted: Set<P>,
            val bytesTotal: Long,
        ) : State<P>
    }
}