package eu.darken.butler.common.files

import eu.darken.butler.common.files.actions.CopyAction
import eu.darken.butler.common.files.actions.DeleteAction
import eu.darken.butler.common.files.actions.FileSystemAction
import eu.darken.butler.common.files.actions.MoveAction
import eu.darken.butler.common.files.operations.FileSystemOps
import eu.darken.butler.common.sharedresource.HasSharedResource
import kotlinx.coroutines.flow.Flow
import okio.FileHandle

interface APathGateway<
    P : APath,
    PL : APathLookup<P>,
    PLE : APathLookupExtended<P>,
    > : HasSharedResource<Any>,
    FileSystemOps<P, PL, PLE>,
    CopyAction<P, PL>,
    MoveAction<P, PL>,
    DeleteAction<P, PL>,
    FileSystemAction<P> {

    suspend fun walk(
        path: P,
        options: WalkOptions<P, PL> = WalkOptions()
    ): Flow<PL>

    data class WalkOptions<P : APath, PLU : APathLookup<P>>(
        val pathDoesNotContain: Set<String>? = null,
        val onFilter: (suspend (PLU) -> Boolean)? = null,
        val onError: (suspend (PLU, Exception) -> Boolean)? = null
    ) {
        val isDirect: Boolean
            get() = onFilter == null && onError == null
    }

    suspend fun du(
        path: P,
        options: DuOptions<P, PL> = DuOptions()
    ): Long

    data class DuOptions<P : APath, PLU : APathLookup<P>>(
        val abortOnError: Boolean = false,
    )

    /**
     * Get a FileHandle for advanced file operations (random access, seeking).
     *
     * ## Why is file() Gateway-only and not in FileSystemOps?
     *
     * FileHandle (Okio) provides advanced capabilities like random access and seeking,
     * but is not needed for basic file operations. The separation follows this design:
     *
     * - **FileSystemOps** (primitives): Provides openInputStream/openOutputStream for
     *   sequential access. These are standard Java streams used by copy/move operations.
     *   Required for all file system implementations.
     *
     * - **APathGateway** (advanced): Provides file() for random access use cases like
     *   image loading, video streaming, or large file manipulation. Gateway-specific
     *   feature, not all operations need it.
     *
     * This separation keeps FileSystemOps focused on essential primitives while allowing
     * gateways to provide advanced features. Operations like copy/move only need streams,
     * not FileHandle.
     *
     * @param path The file path to open
     * @param readWrite If true, open for read/write; if false, open read-only
     * @return FileHandle for advanced file operations
     */
    suspend fun file(path: P, readWrite: Boolean): FileHandle
}