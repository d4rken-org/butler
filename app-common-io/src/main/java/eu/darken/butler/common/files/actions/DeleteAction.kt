package eu.darken.butler.common.files.actions

import eu.darken.butler.common.files.APath
import eu.darken.butler.common.files.APathLookup
import kotlinx.coroutines.flow.Flow


interface DeleteAction<P : APath<P>, PL : APathLookup<P>> {
    suspend fun delete(
        targets: Set<P>,
        options: Options<P> = Options()
    ): Flow<State<P, PL>>

    data class Options<P : APath<P>>(
        val recursive: Boolean = false,
        val ignoreMissing: Boolean = true,
        val onIssue: (suspend (PathActionIssue) -> PathActionIssue.Resolution)? = null,
    )

    sealed interface State<out P, out PL> {
        data class Progress<out P, out PL>(
            val target: PL,
            val primaryProgress: eu.darken.butler.common.progress.Progress.Data,
            val secondaryProgress: eu.darken.butler.common.progress.Progress.Data? = null,
            val deletedBytes: Long = 0L,
            val totalBytes: Long = 0L,
            val currentItemStartTime: kotlin.time.Instant? = null,
        ) : State<P, PL>

        data class Result<out P, out PL>(
            val deleted: Set<PL>,
            val skipped: Set<PL>,
        ) : State<P, PL> {
            @Suppress("UNCHECKED_CAST")
            val bytesTotal: Long get() = (deleted as Set<APathLookup<*>>).mapNotNull { it.size }.sum()
        }
    }
}