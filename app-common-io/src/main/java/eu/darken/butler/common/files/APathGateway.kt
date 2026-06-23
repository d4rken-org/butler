package eu.darken.butler.common.files

import eu.darken.butler.common.files.actions.CopyAction
import eu.darken.butler.common.files.actions.CreateAction
import eu.darken.butler.common.files.actions.DeleteAction
import eu.darken.butler.common.files.actions.FileSystemAction
import eu.darken.butler.common.files.actions.MoveAction
import eu.darken.butler.common.sharedresource.HasSharedResource
import kotlinx.coroutines.flow.Flow

interface APathGateway<
    P : APath<P>,
    PL : APathLookup<P>,
    > : HasSharedResource<Any>,
    FileSystemOps<P, PL>,
    CopyAction<P, PL, P, PL>,
    MoveAction<P, PL, P, PL>,
    DeleteAction<P, PL>,
    CreateAction<P, PL>,
    FileSystemAction<P> {

    suspend fun walk(
        path: P,
        lookupOptions: LookupOptions,
        walkOptions: WalkOptions<P, PL> = WalkOptions()
    ): Flow<PL>

    data class WalkOptions<P : APath<P>, PLU : APathLookup<P>>(
        val pathDoesNotContain: Set<String>? = null,
        val onFilter: (suspend (PLU) -> Boolean)? = null,
        val onError: (suspend (PLU, Exception) -> Boolean)? = null,
        /**
         * Follow symlinks-to-directories wherever they point (with canonical-path cycle detection),
         * like `find -L`. Default false. Destructive callers must leave this false. Enabling it forces
         * the in-process walker (the streaming-IPC walk path does not honor it), see [isDirect].
         */
        val followSymlinks: Boolean = false,
    ) {
        val isDirect: Boolean
            get() = onFilter == null && onError == null && !followSymlinks
    }

    suspend fun du(
        path: P,
        options: DuOptions<P, PL> = DuOptions()
    ): Long

    data class DuOptions<P : APath<P>, PLU : APathLookup<P>>(
        val abortOnError: Boolean = false,
    )
}