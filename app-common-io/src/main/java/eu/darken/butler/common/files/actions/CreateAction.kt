package eu.darken.butler.common.files.actions

import eu.darken.butler.common.files.APath
import eu.darken.butler.common.files.APathLookup
import eu.darken.butler.common.progress.Progress
import kotlinx.coroutines.flow.Flow

/**
 * Action interface for creating files and directories.
 * Create operations are typically instant operations, but this interface
 * supports async operations for filesystems like SAF or network paths.
 */
interface CreateAction<P : APath<P>, PL : APathLookup<P>> {
    suspend fun create(
        target: P,
        type: CreateType,
        options: Options = Options()
    ): Flow<State<P, PL>>

    enum class CreateType {
        FILE,
        DIRECTORY
    }

    data class Options(
        val onIssue: (suspend (PathActionIssue) -> PathActionIssue.Resolution)? = null,
    )

    sealed interface State<out P, out PL> {
        /**
         * Active state - creation in progress.
         * Create operations are typically instant, but this state exists
         * for consistency with other actions and to support async operations.
         */
        data class Active<out P, out PL>(
            val target: P,
            val type: CreateType,
            val primaryProgress: Progress.Data,
        ) : State<P, PL>

        /**
         * Completed state - path successfully created.
         */
        data class Completed<out P, out PL>(
            val created: PL,
        ) : State<P, PL>
    }
}
