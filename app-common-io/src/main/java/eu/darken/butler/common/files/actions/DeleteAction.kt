package eu.darken.butler.common.files.actions

import eu.darken.butler.common.files.APath
import eu.darken.butler.common.files.APathLookup
import kotlinx.coroutines.flow.Flow


interface DeleteAction<P : APath, PL : APathLookup<P>> : GatewayAction<P> {
    suspend fun delete(
        targets: Set<P>,
        options: Options<P> = Options()
    ): Flow<State<P, PL>>

    data class Options<P : APath>(
        val recursive: Boolean = false,
        val ignoreMissing: Boolean = true,
        val onIssue: (suspend (PathActionIssue) -> PathActionIssue.Resolution)? = null,
    )

    sealed interface State<P : APath, PL : APathLookup<P>> {
        data class Progress<P : APath, PL : APathLookup<P>>(
            val target: PL,
            val primaryProgress: eu.darken.butler.common.progress.Progress.Data,
            val secondaryProgress: eu.darken.butler.common.progress.Progress.Data? = null,
            val bytesCurrent: Long,
        ) : State<P, PL>

        data class Result<P : APath, PL : APathLookup<P>>(
            val deleted: Set<PL>,
        ) : State<P, PL> {
            val bytesTotal: Long get() = deleted.sumOf { it.size }
        }
    }
}