package eu.darken.butler.common.files.local.ipc

import dagger.assisted.Assisted
import dagger.assisted.AssistedFactory
import dagger.assisted.AssistedInject
import eu.darken.butler.common.debug.Bugs
import eu.darken.butler.common.debug.logging.Logging.Priority.*
import eu.darken.butler.common.debug.logging.log
import eu.darken.butler.common.debug.logging.logTag
import eu.darken.butler.common.files.APathGateway
import eu.darken.butler.common.files.LocalPath
import eu.darken.butler.common.files.local.LocalPathLookup
import eu.darken.butler.common.files.local.LocalPathLookupExtended
import eu.darken.butler.common.files.metadata.Ownership
import eu.darken.butler.common.files.metadata.Permissions
import eu.darken.butler.common.ipc.IpcClientModule
import eu.darken.butler.common.ipc.fileHandle
import kotlinx.coroutines.flow.Flow
import okio.FileHandle
import kotlin.time.Instant

class FileOpsClient @AssistedInject constructor(
    @Assisted private val fileOpsConnection: FileOpsConnection
) : IpcClientModule {

    /**
     * Doesn't run into IPC buffer overflows on large directories
     */
    fun listFiles(path: LocalPath): List<LocalPath> = try {
        fileOpsConnection.listFilesStream(path).toLocalPaths().also {
            if (Bugs.isTrace) log(TAG) { "listFiles($path) finished streaming, ${it.size} items" }
        }
    } catch (e: Exception) {
        throw e.refineException()
    }

    fun lookup(path: LocalPath): LocalPathLookup = try {
        fileOpsConnection.lookup(path).also {
            if (Bugs.isTrace) log(TAG, VERBOSE) { "lookup($path): $it" }
        }
    } catch (e: Exception) {
        throw e.refineException()
    }

    /**
     * Doesn't run into IPC buffer overflows on large directories
     */
    fun lookupFiles(path: LocalPath): List<LocalPathLookup> = try {
        fileOpsConnection.lookupFilesStream(path).toLocalPathLookups().also {
            if (Bugs.isTrace) log(TAG, VERBOSE) { "lookupFiles($path) finished streaming, ${it.size} items" }
        }
    } catch (e: Exception) {
        throw e.refineException()
    }

    /**
     * Doesn't run into IPC buffer overflows on large directories
     */
    fun lookupFilesExtendedStream(path: LocalPath): List<LocalPathLookupExtended> = try {
        fileOpsConnection.lookupFilesExtendedStream(path).toLocalPathLookupExtended().also {
            if (Bugs.isTrace) log(
                TAG,
                VERBOSE
            ) { "lookupFilesExtendedStream($path) finished streaming, ${it.size} items" }
        }
    } catch (e: Exception) {
        throw e.refineException()
    }

    /**
     * Doesn't run into IPC buffer overflows on large directories
     */
    fun walk(
        path: LocalPath,
        options: APathGateway.WalkOptions<LocalPath, LocalPathLookup>,
    ): Flow<LocalPathLookup> {
        if (!options.isDirect) throw IllegalArgumentException("Only direct walk options are supported")

        val output = try {
            fileOpsConnection.walkStream(
                path,
                (options.pathDoesNotContain ?: emptyList()).toMutableList(),
            )
        } catch (e: Exception) {
            throw e.refineException()
        }
        return output.toLocalPathLookupFlow()
    }

    fun du(path: LocalPath): Long = try {
        fileOpsConnection.du(path)
    } catch (e: Exception) {
        throw e.refineException()
    }

    fun file(path: LocalPath, readWrite: Boolean): FileHandle = try {
        fileOpsConnection.file(path, readWrite).fileHandle(readWrite)
    } catch (e: Exception) {
        throw e.refineException()
    }

    fun createDir(path: LocalPath): Boolean = try {
        fileOpsConnection.createDir(path)
    } catch (e: Exception) {
        throw e.refineException()
    }

    fun createFile(path: LocalPath): Boolean = try {
        fileOpsConnection.createFile(path)
    } catch (e: Exception) {
        throw e.refineException()
    }

    fun canRead(path: LocalPath): Boolean = try {
        fileOpsConnection.canRead(path)
    } catch (e: Exception) {
        throw e.refineException()
    }

    fun canWrite(path: LocalPath): Boolean = try {
        fileOpsConnection.canWrite(path)
    } catch (e: Exception) {
        throw e.refineException()
    }

    fun exists(path: LocalPath): Boolean = try {
        fileOpsConnection.exists(path)
    } catch (e: Exception) {
        throw e.refineException()
    }

    fun delete(path: LocalPath, recursive: Boolean = false): Boolean = try {
        fileOpsConnection.delete(path, recursive)
    } catch (e: Exception) {
        throw e.refineException()
    }

    fun createSymlink(linkPath: LocalPath, targetPath: LocalPath): Boolean = try {
        fileOpsConnection.createSymlink(linkPath, targetPath)
    } catch (e: Exception) {
        throw e.refineException()
    }

    fun setModifiedAt(path: LocalPath, modifiedAt: Instant): Boolean = try {
        fileOpsConnection.setModifiedAt(path, modifiedAt.toEpochMilliseconds())
    } catch (e: Exception) {
        throw e.refineException()
    }

    fun setPermissions(path: LocalPath, permissions: Permissions): Boolean = try {
        fileOpsConnection.setPermissions(path, permissions)
    } catch (e: Exception) {
        throw e.refineException()
    }

    fun setOwnership(path: LocalPath, ownership: Ownership): Boolean = try {
        fileOpsConnection.setOwnership(path, ownership)
    } catch (e: Exception) {
        throw e.refineException()
    }

    companion object {
        val TAG = logTag("FileOps", "Service", "Client")
    }

    @AssistedFactory
    interface Factory {
        fun create(connection: FileOpsConnection): FileOpsClient
    }
}