package eu.darken.butler.common.files.local.ipc

import dagger.assisted.Assisted
import dagger.assisted.AssistedFactory
import dagger.assisted.AssistedInject
import eu.darken.butler.common.debug.Bugs
import eu.darken.butler.common.debug.logging.Logging.Priority.*
import eu.darken.butler.common.debug.logging.log
import eu.darken.butler.common.debug.logging.logTag
import eu.darken.butler.common.files.APathGateway
import eu.darken.butler.common.files.FileSystemOps
import eu.darken.butler.common.files.LocalPath
import eu.darken.butler.common.files.LookupOptions
import eu.darken.butler.common.files.actions.CopyAction
import eu.darken.butler.common.files.actions.DeleteAction
import eu.darken.butler.common.files.local.LocalPathLookup
import eu.darken.butler.common.files.metadata.FileSystem
import eu.darken.butler.common.files.metadata.Ownership
import eu.darken.butler.common.files.metadata.Permissions
import eu.darken.butler.common.ipc.IpcClientModule
import eu.darken.butler.common.ipc.fileHandle
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import okio.FileHandle
import okio.buffer
import java.io.InputStream
import java.io.OutputStream
import kotlin.time.Instant

class FileOpsClient @AssistedInject constructor(
    @Assisted private val fileOpsConnection: FileOpsConnection
) : IpcClientModule,
    FileSystemOps<LocalPath, LocalPathLookup>,
    CopyAction<LocalPath, LocalPathLookup, LocalPath, LocalPathLookup>,
    DeleteAction<LocalPath, LocalPathLookup> {

    /**
     * Doesn't run into IPC buffer overflows on large directories
     */
    override suspend fun listFiles(path: LocalPath): List<LocalPath> = try {
        fileOpsConnection.listFilesStream(path).toLocalPaths().also {
            if (Bugs.isTrace) log(TAG) { "listFiles($path) finished streaming, ${it.size} items" }
        }
    } catch (e: Exception) {
        throw e.refineException()
    }

    override suspend fun lookup(path: LocalPath, options: LookupOptions): LocalPathLookup = try {
        fileOpsConnection.lookup(path, options).also {
            if (Bugs.isTrace) log(TAG, VERBOSE) { "lookup($path, $options): $it" }
        }
    } catch (e: Exception) {
        throw e.refineException()
    }

    /**
     * Doesn't run into IPC buffer overflows on large directories
     */
    override suspend fun lookupFiles(path: LocalPath, options: LookupOptions): List<LocalPathLookup> = try {
        fileOpsConnection.lookupFilesStream(path, options).toLocalPathLookups().also {
            if (Bugs.isTrace) log(TAG, VERBOSE) { "lookupFiles($path, $options) finished streaming, ${it.size} items" }
        }
    } catch (e: Exception) {
        throw e.refineException()
    }

    /**
     * Doesn't run into IPC buffer overflows on large directories
     */
    fun walk(
        path: LocalPath,
        lookupOptions: LookupOptions,
        walkOptions: APathGateway.WalkOptions<LocalPath, LocalPathLookup>,
    ): Flow<LocalPathLookup> {
        if (!walkOptions.isDirect) throw IllegalArgumentException("Only direct walk options are supported")

        val output = try {
            fileOpsConnection.walkStream(
                path,
                lookupOptions,
                (walkOptions.pathDoesNotContain ?: emptyList()).toMutableList(),
            )
        } catch (e: Exception) {
            throw e.refineException()
        }
        return output.toLocalPathLookupFlow()
    }

    suspend fun du(path: LocalPath): Long = try {
        fileOpsConnection.du(path)
    } catch (e: Exception) {
        throw e.refineException()
    }

    override suspend fun createDir(path: LocalPath, createParents: Boolean): Unit = try {
        fileOpsConnection.createDir(path, createParents)
        Unit
    } catch (e: Exception) {
        throw e.refineException()
    }

    override suspend fun createFile(path: LocalPath, createParents: Boolean): Unit = try {
        fileOpsConnection.createFile(path, createParents)
        Unit
    } catch (e: Exception) {
        throw e.refineException()
    }

    override suspend fun createSymlink(linkPath: LocalPath, targetPath: LocalPath): Boolean = try {
        fileOpsConnection.createSymlink(linkPath, targetPath)
    } catch (e: Exception) {
        throw e.refineException()
    }

    override suspend fun readSymbolicLink(linkPath: LocalPath): LocalPath = try {
        fileOpsConnection.readSymbolicLink(linkPath)
    } catch (e: Exception) {
        throw e.refineException()
    }

    override suspend fun move(source: LocalPath, destination: LocalPath): Boolean = try {
        fileOpsConnection.move(source, destination)
    } catch (e: Exception) {
        throw e.refineException()
    }

    override suspend fun canRead(path: LocalPath): Boolean = try {
        fileOpsConnection.canRead(path)
    } catch (e: Exception) {
        throw e.refineException()
    }

    override suspend fun canWrite(path: LocalPath): Boolean = try {
        fileOpsConnection.canWrite(path)
    } catch (e: Exception) {
        throw e.refineException()
    }

    override suspend fun exists(path: LocalPath): Boolean = try {
        fileOpsConnection.exists(path)
    } catch (e: Exception) {
        throw e.refineException()
    }

    override suspend fun delete(path: LocalPath, recursive: Boolean): Boolean = try {
        fileOpsConnection.delete(path, recursive)
    } catch (e: Exception) {
        throw e.refineException()
    }

    /**
     * Delete multiple files with progress streaming and interactive issue resolution.
     *
     * @param targets Set of files/directories to delete
     * @param options Deletion options (recursive, ignoreMissing, issue handler)
     * @return Flow of State updates (Progress and final Result)
     */
    override suspend fun delete(
        targets: Set<LocalPath>,
        options: DeleteAction.Options<LocalPath>,
    ): Flow<DeleteAction.State<LocalPath, LocalPathLookup>> = try {
        log(TAG, VERBOSE) { "delete(): ${targets.size} targets" }

        // Create AIDL callback wrapper if onIssue is provided
        val callback: FileOperationCallback? = options.onIssue?.let { issueHandler ->
            object : FileOperationCallback.Stub() {
                override fun onIssue(issue: FileOperationIssue): FileOperationIssueResolution {
                    // Convert IPC issue to domain issue
                    val domainIssue = issue.toPathActionIssue()

                    // Call user's issue handler (blocking call)
                    val resolution = kotlinx.coroutines.runBlocking {
                        issueHandler(domainIssue)
                    }

                    // Convert domain resolution to IPC resolution
                    return resolution.toFileOperationIssueResolution()
                }
            }
        }

        // Call host's deleteStream()
        val remoteInputStream = fileOpsConnection.deleteStream(
            targets.toList(),
            options.recursive,
            options.ignoreMissing,
            callback
        )

        // Convert RemoteInputStream to Flow<DeleteOperationEvent>
        remoteInputStream.toEventFlow(DeleteOperationEvent.CREATOR)
            .map { event ->
                // Convert each event to DeleteAction.State
                event.toDeleteActionState()
            }
    } catch (e: Exception) {
        throw e.refineException()
    }

    /**
     * Copy files with progress streaming and interactive issue resolution.
     *
     * @param sources Set of files/directories to copy
     * @param destination Target directory or file path
     * @param onIssue Issue handler for conflict resolution (optional)
     * @param options Copy options (overwrite, preserve attributes, follow symlinks)
     * @return Flow of State updates (scan progress, copy progress, and final result)
     */
    override suspend fun copy(
        sources: Set<LocalPath>,
        destination: LocalPath,
        onIssue: (suspend (eu.darken.butler.common.files.actions.PathActionIssue) -> eu.darken.butler.common.files.actions.PathActionIssue.Resolution)?,
        options: CopyAction.Options,
    ): Flow<CopyAction.State<LocalPath, LocalPathLookup, LocalPath, LocalPathLookup>> = try {
        log(TAG, VERBOSE) { "copy(): ${sources.size} sources → $destination" }

        // Create AIDL callback wrapper if onIssue is provided
        val callback: FileOperationCallback? = onIssue?.let { issueHandler ->
            object : FileOperationCallback.Stub() {
                override fun onIssue(issue: FileOperationIssue): FileOperationIssueResolution {
                    // Convert IPC issue to domain issue
                    val domainIssue = issue.toPathActionIssue()

                    // Call user's issue handler (blocking call)
                    val resolution = kotlinx.coroutines.runBlocking {
                        issueHandler(domainIssue)
                    }

                    // Convert domain resolution to IPC resolution
                    return resolution.toFileOperationIssueResolution()
                }
            }
        }

        // Call host's copyStream()
        val remoteInputStream = fileOpsConnection.copyStream(
            sources.toList(),
            destination,
            options.overwrite,
            options.preserveAttributes,
            options.followSymlinks,
            callback
        )

        // Convert RemoteInputStream to Flow<CopyOperationEvent>
        remoteInputStream.toEventFlow(CopyOperationEvent.CREATOR)
            .map { event ->
                // Convert each event to CopyAction.State
                event.toCopyActionState()
            }
    } catch (e: Exception) {
        throw e.refineException()
    }

    override suspend fun setModifiedAt(path: LocalPath, modifiedAt: Instant): Boolean = try {
        fileOpsConnection.setModifiedAt(path, modifiedAt.toEpochMilliseconds())
    } catch (e: Exception) {
        throw e.refineException()
    }

    override suspend fun setPermissions(path: LocalPath, permissions: Permissions): Boolean = try {
        fileOpsConnection.setPermissions(path, permissions)
    } catch (e: Exception) {
        throw e.refineException()
    }

    override suspend fun setOwnership(path: LocalPath, ownership: Ownership): Boolean = try {
        fileOpsConnection.setOwnership(path, ownership)
    } catch (e: Exception) {
        throw e.refineException()
    }

    override suspend fun file(path: LocalPath, readWrite: Boolean): FileHandle = try {
        fileOpsConnection.file(path, readWrite).fileHandle(readWrite)
    } catch (e: Exception) {
        throw e.refineException()
    }

    override suspend fun openInputStream(path: LocalPath): InputStream = try {
        file(path, readWrite = false).source().buffer().inputStream()
    } catch (e: Exception) {
        throw e.refineException()
    }

    override suspend fun openOutputStream(path: LocalPath, append: Boolean): OutputStream = try {
        file(path, readWrite = true).sink().buffer().outputStream()
    } catch (e: Exception) {
        throw e.refineException()
    }

    override suspend fun getFileSystem(path: LocalPath): FileSystem = try {
        fileOpsConnection.getFileSystem(path)
            ?: throw IllegalStateException("IPC connection returned null for getFileSystem($path)")
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