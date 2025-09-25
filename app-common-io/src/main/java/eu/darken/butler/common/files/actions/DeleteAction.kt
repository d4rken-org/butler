package eu.darken.butler.common.files.actions

import eu.darken.butler.common.files.APath
import kotlinx.coroutines.flow.Flow


interface DeleteAction<P : APath> : GatewayAction<P> {
    suspend fun delete(
        targets: Set<P>,
        options: Options<P> = Options()
    ): Flow<State<P>>

    data class Options<P : APath>(
        val recursive: Boolean = false,
        val ignoreMissing: Boolean = true,
        val onIssue: (suspend (PathActionIssue) -> PathActionIssue.Resolution)? = null,
    )

    sealed interface State<P : APath> {
        data class Progress<P : APath>(
            val target: P,
            val targetSize: Long,
            val primaryProgress: eu.darken.butler.common.progress.Progress.Data,
            val secondaryProgress: eu.darken.butler.common.progress.Progress.Data? = null,
            val bytesCurrent: Long,
        ) : State<P>

        data class Result<P : APath>(
            val deleted: Set<P>,
            val bytesTotal: Long,
        ) : State<P>
    }
}