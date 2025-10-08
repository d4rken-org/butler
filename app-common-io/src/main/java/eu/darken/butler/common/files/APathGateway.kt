package eu.darken.butler.common.files

import eu.darken.butler.common.files.actions.CopyAction
import eu.darken.butler.common.files.actions.DeleteAction
import eu.darken.butler.common.files.actions.FileSystemAction
import eu.darken.butler.common.files.actions.MoveAction
import eu.darken.butler.common.files.metadata.Ownership
import eu.darken.butler.common.files.metadata.Permissions
import eu.darken.butler.common.sharedresource.HasSharedResource
import kotlinx.coroutines.flow.Flow
import okio.FileHandle
import kotlin.time.Instant

interface APathGateway<
    P : APath,
    PL : APathLookup<P>,
    PLE : APathLookupExtended<P>,
    > : HasSharedResource<Any>,
    CopyAction<P, PL>,
    MoveAction<P, PL>,
    DeleteAction<P, PL>,
    FileSystemAction<P> {

    suspend fun createDir(path: P)

    suspend fun createFile(path: P)

    suspend fun listFiles(path: P): Collection<P>

    suspend fun lookup(path: P): PL

    suspend fun lookupFiles(path: P): Collection<PL>

    suspend fun lookupFilesExtended(path: P): Collection<PLE>

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

    suspend fun exists(path: P): Boolean

    suspend fun canWrite(path: P): Boolean

    suspend fun canRead(path: P): Boolean

    suspend fun file(path: P, readWrite: Boolean): FileHandle

    suspend fun createSymlink(linkPath: P, targetPath: P): Boolean

    suspend fun setModifiedAt(path: P, modifiedAt: Instant): Boolean

    suspend fun setPermissions(path: P, permissions: Permissions): Boolean

    suspend fun setOwnership(path: P, ownership: Ownership): Boolean
}