package eu.darken.butler.common.files.local.ipc

import dagger.assisted.Assisted
import dagger.assisted.AssistedFactory
import dagger.assisted.AssistedInject
import eu.darken.butler.common.debug.Bugs
import eu.darken.butler.common.debug.logging.Logging.Priority.*
import eu.darken.butler.common.debug.logging.log
import eu.darken.butler.common.debug.logging.logTag
import eu.darken.butler.common.files.APathGateway
import eu.darken.butler.common.files.Existence
import eu.darken.butler.common.files.FileSystemOps
import eu.darken.butler.common.files.LocalPath
import eu.darken.butler.common.files.MoveOutcome
import eu.darken.butler.common.files.LookupOptions
import eu.darken.butler.common.files.actions.CopyAction
import eu.darken.butler.common.files.actions.DeleteAction
import eu.darken.butler.common.files.actions.MoveAction
import eu.darken.butler.common.files.actions.PathActionIssue
import eu.darken.butler.common.files.errors.ReadException
import eu.darken.butler.common.files.local.LocalPathLookup
import eu.darken.butler.common.files.metadata.FileSystem
import eu.darken.butler.common.files.metadata.Ownership
import eu.darken.butler.common.files.metadata.Permissions
import eu.darken.butler.common.ipc.IpcClientModule
import eu.darken.butler.common.ipc.fileHandle
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.runBlocking
import okio.FileHandle
import okio.buffer
import okio.Sink
import java.io.FilterInputStream
import java.io.FilterOutputStream
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import kotlin.coroutines.cancellation.CancellationException
import kotlin.time.Instant

class FileOpsClient @AssistedInject constructor(
    @Assisted private val fileOpsConnection: FileOpsConnection
) : IpcClientModule,
    FileSystemOps<LocalPath, LocalPathLookup>,
    CopyAction<LocalPath, LocalPathLookup, LocalPath, LocalPathLookup>,
    MoveAction<LocalPath, LocalPathLookup, LocalPath, LocalPathLookup>,
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
     * Host-side streaming walk: the whole subtree is walked in the privileged process, one IPC
     * stream total. Directory errors arrive as events and are routed to [walkOptions].onError;
     * a stream ending without a terminal event is reported as truncation, not clean completion.
     *
     * Doesn't run into IPC buffer overflows on large directories.
     */
    fun walk(
        path: LocalPath,
        lookupOptions: LookupOptions,
        walkOptions: APathGateway.WalkOptions<LocalPath, LocalPathLookup>,
        excludeSubtrees: List<LocalPath>? = null,
    ): Flow<LocalPathLookup> {
        if (!walkOptions.isStreamable) {
            throw IllegalArgumentException("onFilter cannot cross the IPC boundary")
        }

        val spec = WalkSpec(
            pathDoesNotContain = walkOptions.pathDoesNotContain?.toList(),
            followSymlinks = walkOptions.followSymlinks,
            excludeSubtrees = excludeSubtrees,
        )

        return flow {
            val output = try {
                fileOpsConnection.walkStreamV2(path, lookupOptions, spec)
            } catch (e: Exception) {
                throw e.refineException()
            }

            var terminated = false
            output.toWalkEventFlow().collect { event ->
                when (event) {
                    is WalkEvent.Item -> emit(event.lookup)
                    is WalkEvent.DirError -> {
                        val error = ReadException(
                            message = event.message ?: "Failed to read directory",
                            path = event.lookup.lookedUp,
                        )
                        val continueWalk = walkOptions.onError?.invoke(event.lookup, error) ?: true
                        if (!continueWalk) throw error
                    }
                    is WalkEvent.FatalError -> {
                        terminated = true
                        throw ReadException(
                            message = event.message ?: "Walk failed",
                            path = event.path ?: path,
                        )
                    }
                    WalkEvent.Done -> terminated = true
                }
            }
            if (!terminated) throw ReadException("Walk stream truncated", path)
        }
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

    override suspend fun canonicalize(path: LocalPath): LocalPath = try {
        fileOpsConnection.canonicalize(path)
    } catch (e: Exception) {
        throw e.refineException()
    }

    override suspend fun move(source: LocalPath, destination: LocalPath): MoveOutcome = try {
        // AIDL wire format is Boolean: true = Moved, false = NotSupported (nothing mutated)
        if (fileOpsConnection.move(source, destination)) {
            MoveOutcome.Moved
        } else {
            MoveOutcome.NotSupported("Remote atomic move not supported")
        }
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

    override suspend fun existsStrict(path: LocalPath): Existence = try {
        Existence.fromIpcCode(fileOpsConnection.existsStrict(path))
    } catch (e: CancellationException) {
        throw e
    } catch (e: Exception) {
        // A dead service or a failed transport is not an answer about the path.
        log(TAG, WARN) { "existsStrict($path) failed: $e" }
        Existence.UNKNOWN
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
                    val resolution = runBlocking {
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
                // Handle Error events by throwing appropriate exception
                if (event is DeleteOperationEvent.Error) {
                    if (event.cancelled) {
                        throw CancellationException(event.error)
                    } else {
                        throw IOException(event.error)
                    }
                }
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
        onIssue: (suspend (PathActionIssue) -> PathActionIssue.Resolution)?,
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
                    val resolution = runBlocking {
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
                // Handle Error events by throwing appropriate exception
                if (event is CopyOperationEvent.Error) {
                    if (event.cancelled) {
                        throw CancellationException(event.error)
                    } else {
                        throw IOException(event.error)
                    }
                }
                // Convert each event to CopyAction.State
                event.toCopyActionState()
            }
    } catch (e: Exception) {
        throw e.refineException()
    }

    /**
     * Move files with progress streaming and interactive issue resolution.
     *
     * @param sources Set of source paths to move
     * @param destination Destination directory or file
     * @param onIssue Issue handler for conflict resolution (optional)
     * @param options Move options (overwrite, preserve attributes, atomic move)
     * @return Flow of State updates (scan progress, move progress, and final result)
     */
    override suspend fun move(
        sources: Set<LocalPath>,
        destination: LocalPath,
        onIssue: (suspend (PathActionIssue) -> PathActionIssue.Resolution)?,
        options: MoveAction.Options,
    ): Flow<MoveAction.State<LocalPath, LocalPathLookup, LocalPath, LocalPathLookup>> = try {
        log(TAG, VERBOSE) { "move(): ${sources.size} sources → $destination" }

        // Create AIDL callback wrapper if onIssue is provided
        val callback: FileOperationCallback? = onIssue?.let { issueHandler ->
            object : FileOperationCallback.Stub() {
                override fun onIssue(issue: FileOperationIssue): FileOperationIssueResolution {
                    // Convert IPC issue to domain issue
                    val domainIssue = issue.toPathActionIssue()

                    // Call user's issue handler (blocking call)
                    val resolution = runBlocking {
                        issueHandler(domainIssue)
                    }

                    // Convert domain resolution to IPC resolution
                    return resolution.toFileOperationIssueResolution()
                }
            }
        }

        // Call host's moveStream()
        val remoteInputStream = fileOpsConnection.moveStream(
            sources.toList(),
            destination,
            options.overwrite,
            options.preserveAttributes,
            false,  // followSymlinks - MoveAction doesn't have this option
            callback
        )

        // Convert RemoteInputStream to Flow<MoveOperationEvent>
        remoteInputStream.toEventFlow(MoveOperationEvent.CREATOR)
            .map { event ->
                // Handle Error events by throwing appropriate exception
                if (event is MoveOperationEvent.Error) {
                    if (event.cancelled) {
                        throw CancellationException(event.error)
                    } else {
                        throw IOException(event.error)
                    }
                }
                // Convert each event to MoveAction.State
                event.toMoveActionState()
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
    } catch (e: CancellationException) {
        throw e
    } catch (e: Exception) {
        throw e.refineException()
    }

    override suspend fun openInputStream(path: LocalPath): InputStream {
        val handle = file(path, readWrite = false)
        try {
            return object : FilterInputStream(handle.source().buffer().inputStream()) {
                override fun close() = closePreservingSuppressed(
                    { super.close() },
                    { handle.close() },
                )
            }
        } catch (e: CancellationException) {
            closeHandleAfterFailure(handle, e)
        } catch (e: Exception) {
            closeHandleAfterFailure(handle, e)
        }
    }

    override suspend fun openOutputStream(path: LocalPath, append: Boolean): OutputStream {
        val handle = file(path, readWrite = true)
        try {
            if (!append) handle.resize(0)
            val sink: Sink = if (append) handle.appendingSink() else handle.sink()
            return object : FilterOutputStream(sink.buffer().outputStream()) {
                override fun close() = closePreservingSuppressed(
                    { super.close() },
                    { handle.close() },
                )
            }
        } catch (e: CancellationException) {
            closeHandleAfterFailure(handle, e)
        } catch (e: Exception) {
            closeHandleAfterFailure(handle, e)
        }
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

    private fun closeHandleAfterFailure(handle: FileHandle, error: Exception): Nothing {
        var closeError: Throwable? = null
        try {
            handle.close()
        } catch (t: Throwable) {
            closeError = t
        }
        val toThrow = if (error is CancellationException) error else error.refineException()
        closeError?.let { toThrow.addSuppressed(it) }
        throw toThrow
    }

    private fun closePreservingSuppressed(vararg closeables: () -> Unit) {
        var thrown: Throwable? = null
        closeables.forEach { close ->
            try {
                close()
            } catch (t: Throwable) {
                thrown?.addSuppressed(t) ?: run { thrown = t }
            }
        }
        thrown?.let { throw it }
    }

    @AssistedFactory
    interface Factory {
        fun create(connection: FileOpsConnection): FileOpsClient
    }
}
