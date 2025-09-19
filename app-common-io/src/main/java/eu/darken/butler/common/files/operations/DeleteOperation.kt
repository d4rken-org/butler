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
        val ignoreMissing: Boolean = false,
        val onIssue: (suspend (Issue) -> Issue.Resolution?)? = null,
    )

    sealed interface State<P : APath> {
        data class Progress<P : APath>(
            val currentTarget: P,
            val filesDeleted: Int,
        ) : State<P>

        data class Result<P : APath>(
            val deletedFiles: Set<P>,
            val deletedSize: Long,
        ) : State<P>
    }
}