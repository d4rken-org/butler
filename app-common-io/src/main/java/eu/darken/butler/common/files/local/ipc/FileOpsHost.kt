package eu.darken.butler.common.files.local.ipc

import android.os.DeadObjectException
import android.os.RemoteException
import eu.darken.butler.common.coroutine.AppScope
import eu.darken.butler.common.coroutine.DispatcherProvider
import eu.darken.butler.common.debug.Bugs
import eu.darken.butler.common.debug.logging.Logging.Priority.*
import eu.darken.butler.common.debug.logging.asLog
import eu.darken.butler.common.debug.logging.log
import eu.darken.butler.common.debug.logging.logTag
import eu.darken.butler.common.files.Existence
import eu.darken.butler.common.files.LocalPath
import eu.darken.butler.common.files.MoveOutcome
import eu.darken.butler.common.files.LookupOptions
import eu.darken.butler.common.files.actions.CopyAction
import eu.darken.butler.common.files.extensions.isDescendantOfOrSelf
import eu.darken.butler.common.files.actions.MoveAction
import eu.darken.butler.common.files.actions.PathActionIssue
import eu.darken.butler.common.files.local.LocalFileSystemOps
import eu.darken.butler.common.files.local.LocalPathLookup
import eu.darken.butler.common.files.local.copy
import eu.darken.butler.common.files.local.delete
import eu.darken.butler.common.files.local.move
import eu.darken.butler.common.files.local.walkers.DirectLocalWalker
import eu.darken.butler.common.files.metadata.FileSystem
import eu.darken.butler.common.files.metadata.Ownership
import eu.darken.butler.common.files.metadata.Permissions
import eu.darken.butler.common.ipc.IpcErrorCodec
import eu.darken.butler.common.ipc.IpcHostModule
import eu.darken.butler.common.ipc.RemoteFileHandle
import eu.darken.butler.common.ipc.RemoteInputStream
import eu.darken.butler.common.ipc.remoteFileHandle
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.channelFlow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.plus
import kotlinx.coroutines.runBlocking
import java.io.IOException
import javax.inject.Inject
import kotlin.time.Instant

/**
 * Resides in extra process.
 */
class FileOpsHost @Inject constructor(
    @AppScope private val appScope: CoroutineScope,
    private val dispatcherProvider: DispatcherProvider,
    private val fileSystemOps: LocalFileSystemOps,
) : FileOpsConnection.Stub(), IpcHostModule {

    override fun listFilesStream(path: LocalPath): RemoteInputStream = try {
        if (Bugs.isTrace) log(TAG, VERBOSE) { "listFilesStream($path)..." }
        val result = runBlocking { fileSystemOps.listFiles(path) }
        if (Bugs.isTrace) log(TAG, VERBOSE) { "listFilesStream($path) ${result.size} items read, now streaming" }
        result.toRemoteInputStream()
    } catch (e: Exception) {
        log(TAG, ERROR) { "lookupFiles(path=$path) failed\n${e.asLog()}" }
        throw e.wrapToPropagate()
    }

    override fun lookup(path: LocalPath, options: LookupOptions): LocalPathLookup = try {
        if (Bugs.isTrace) log(TAG, VERBOSE) { "lookup($path, $options)..." }
        runBlocking { fileSystemOps.lookup(path, options) }
    } catch (e: Exception) {
        log(TAG, ERROR) { "lookup(path=$path) failed\n${e.asLog()}" }
        throw e.wrapToPropagate()
    }

    override fun lookupFilesStream(path: LocalPath, options: LookupOptions): RemoteInputStream = try {
        if (Bugs.isTrace) log(TAG, VERBOSE) { "lookupFilesStream($path, $options)..." }
        val lookups = runBlocking { fileSystemOps.lookupFiles(path, options) }
        lookups.toRemoteInputStream()
    } catch (e: Exception) {
        log(TAG, ERROR) { "lookupFiles(path=$path) failed\n${e.asLog()}" }
        throw e.wrapToPropagate()
    }

    override fun walkStream(
        path: LocalPath,
        lookupOptions: LookupOptions,
        pathDoesNotContain: List<String>
    ): RemoteInputStream = try {
        if (Bugs.isTrace) log(TAG, VERBOSE) { "walkStream($path)..." }
        runBlocking {
            DirectLocalWalker(
                fileSystemOps = fileSystemOps,
                start = path,
                lookupOptions = lookupOptions,
                onFilter = { lookup ->
                    pathDoesNotContain.none { lookup.path.contains(it) }
                },
            )
        }.toRemoteInputStream(appScope + dispatcherProvider.IO)
    } catch (e: Exception) {
        log(TAG, ERROR) { "walkStream(path=$path) failed\n${e.asLog()}" }
        throw e.wrapToPropagate()
    }

    override fun walkStreamV2(
        path: LocalPath,
        options: LookupOptions,
        spec: WalkSpec,
    ): RemoteInputStream = try {
        if (Bugs.isTrace) log(TAG, VERBOSE) { "walkStreamV2($path, $spec)..." }
        val events: Flow<WalkEvent> = flow {
            DirectLocalWalker(
                fileSystemOps = fileSystemOps,
                start = path,
                lookupOptions = options,
                onFilter = filter@{ lookup ->
                    if (spec.pathDoesNotContain?.any { lookup.path.contains(it) } == true) return@filter false
                    if (spec.excludeSubtrees?.any { lookup.lookedUp.isDescendantOfOrSelf(it) } == true) return@filter false
                    true
                },
                onError = { lookup, e ->
                    emit(WalkEvent.DirError(lookup, IpcErrorCodec.encodeCompact(e)))
                    true
                },
                followSymlinks = spec.followSymlinks,
            ).collect { emit(WalkEvent.Item(it)) }
            emit(WalkEvent.Done)
        }.catch { e ->
            if (e is kotlinx.coroutines.CancellationException) throw e
            log(TAG, ERROR) { "walkStreamV2($path) fatal: ${e.asLog()}" }
            // Full payload for the one terminal failure, the per-directory errors go compact.
            emit(WalkEvent.FatalError(path, IpcErrorCodec.encode(e)))
        }
        events.toEventRemoteStream(appScope + dispatcherProvider.IO)
    } catch (e: Exception) {
        log(TAG, ERROR) { "walkStreamV2(path=$path) failed\n${e.asLog()}" }
        throw e.wrapToPropagate()
    }

    override fun du(path: LocalPath): Long = try {
        if (Bugs.isTrace) log(TAG, VERBOSE) { "du($path)..." }
        runBlocking { fileSystemOps.du(path) }
    } catch (e: Exception) {
        log(TAG, ERROR) { "exists(path=$path) failed\n${e.asLog()}" }
        throw e.wrapToPropagate()
    }

    override fun file(path: LocalPath, readWrite: Boolean): RemoteFileHandle = try {
        if (Bugs.isTrace) log(TAG, VERBOSE) { "file($path, $readWrite)..." }
        runBlocking { fileSystemOps.file(path, readWrite) }.remoteFileHandle()
    } catch (e: Exception) {
        log(TAG, ERROR) { "file(path=$path) failed\n${e.asLog()}" }
        throw e.wrapToPropagate()
    }

    override fun createDir(path: LocalPath, createParents: Boolean): Boolean = try {
        if (Bugs.isTrace) log(TAG, VERBOSE) { "createDir($path, createParents=$createParents)..." }
        runBlocking { fileSystemOps.createDir(path, createParents) }
        true
    } catch (e: Exception) {
        log(TAG, ERROR) { "createDir(path=$path, createParents=$createParents) failed\n${e.asLog()}" }
        throw e.wrapToPropagate()
    }

    override fun createFile(path: LocalPath, createParents: Boolean): Boolean = try {
        if (Bugs.isTrace) log(TAG, VERBOSE) { "createFile($path, createParents=$createParents)..." }
        runBlocking { fileSystemOps.createFile(path, createParents) }
        true
    } catch (e: Exception) {
        log(TAG, ERROR) { "createFile(path=$path, createParents=$createParents) failed\n${e.asLog()}" }
        throw e.wrapToPropagate()
    }

    override fun canRead(path: LocalPath): Boolean = try {
        if (Bugs.isTrace) log(TAG, VERBOSE) { "canRead($path)..." }
        runBlocking { fileSystemOps.canRead(path) }
    } catch (e: Exception) {
        log(TAG, ERROR) { "path(path=$path) failed\n${e.asLog()}" }
        throw e.wrapToPropagate()
    }

    override fun canWrite(path: LocalPath): Boolean = try {
        if (Bugs.isTrace) log(TAG, VERBOSE) { "canWrite($path)..." }
        runBlocking { fileSystemOps.canWrite(path) }
    } catch (e: Exception) {
        log(TAG, ERROR) { "canWrite(path=$path) failed\n${e.asLog()}" }
        throw e.wrapToPropagate()
    }

    override fun exists(path: LocalPath): Boolean = try {
        if (Bugs.isTrace) log(TAG, VERBOSE) { "exists($path)..." }
        runBlocking { fileSystemOps.exists(path) }
    } catch (e: Exception) {
        log(TAG, ERROR) { "exists(path=$path) failed\n${e.asLog()}" }
        throw e.wrapToPropagate()
    }

    /**
     * Never throws across the binder: typed exceptions do not survive it in a minified build (R8
     * strips the single-String constructor [eu.darken.butler.common.ipc.IpcClientModule] needs
     * reflectively), a code does. A failure here is [Existence.UNKNOWN], the honest answer anyway.
     */
    override fun existsStrict(path: LocalPath): Int = try {
        if (Bugs.isTrace) log(TAG, VERBOSE) { "existsStrict($path)..." }
        runBlocking { fileSystemOps.existsStrict(path) }.ipcCode
    } catch (e: Exception) {
        log(TAG, ERROR) { "existsStrict(path=$path) failed\n${e.asLog()}" }
        Existence.UNKNOWN.ipcCode
    }

    override fun delete(path: LocalPath, recursive: Boolean): Boolean = try {
        log(TAG, VERBOSE) { "delete($path, recursive=$recursive)..." }
        runBlocking { fileSystemOps.delete(path, recursive) }
    } catch (e: Exception) {
        log(TAG, ERROR) { "delete(path=$path, recursive=$recursive) failed\n${e.asLog()}" }
        throw e.wrapToPropagate()
    }

    override fun createSymlink(linkPath: LocalPath, targetPath: LocalPath): Boolean = try {
        if (Bugs.isTrace) log(TAG, VERBOSE) { "createSymlink($linkPath,$targetPath)..." }
        runBlocking { fileSystemOps.createSymlink(linkPath, targetPath) }
    } catch (e: Exception) {
        log(TAG, ERROR) { "createSymlink(linkPath=$linkPath, targetPath=$targetPath) failed\n${e.asLog()}" }
        throw e.wrapToPropagate()
    }

    override fun readSymbolicLink(linkPath: LocalPath): LocalPath = try {
        if (Bugs.isTrace) log(TAG, VERBOSE) { "readSymbolicLink($linkPath)..." }
        runBlocking { fileSystemOps.readSymbolicLink(linkPath) }
    } catch (e: Exception) {
        log(TAG, ERROR) { "readSymbolicLink(linkPath=$linkPath) failed\n${e.asLog()}" }
        throw e.wrapToPropagate()
    }

    override fun canonicalize(path: LocalPath): LocalPath = try {
        if (Bugs.isTrace) log(TAG, VERBOSE) { "canonicalize($path)..." }
        runBlocking { fileSystemOps.canonicalize(path) }
    } catch (e: Exception) {
        log(TAG, ERROR) { "canonicalize(path=$path) failed\n${e.asLog()}" }
        throw e.wrapToPropagate()
    }

    override fun move(source: LocalPath, destination: LocalPath): Boolean = try {
        if (Bugs.isTrace) log(TAG, VERBOSE) { "move($source,$destination)..." }
        // AIDL wire format is Boolean: true = Moved, false = NotSupported (nothing mutated)
        runBlocking { fileSystemOps.move(source, destination) is MoveOutcome.Moved }
    } catch (e: Exception) {
        log(TAG, ERROR) { "move(source=$source, destination=$destination) failed\n${e.asLog()}" }
        throw e.wrapToPropagate()
    }

    override fun setModifiedAt(path: LocalPath, modifiedAt: Long): Boolean = try {
        if (Bugs.isTrace) log(TAG, VERBOSE) { "setModifiedAt($path,$modifiedAt)..." }
        runBlocking { fileSystemOps.setModifiedAt(path, Instant.fromEpochMilliseconds(modifiedAt)) }
    } catch (e: Exception) {
        log(TAG, ERROR) { "setModifiedAt(path=$path, modifiedAt=$modifiedAt) failed\n${e.asLog()}" }
        throw e.wrapToPropagate()
    }

    override fun setPermissions(path: LocalPath, permissions: Permissions): Boolean = try {
        if (Bugs.isTrace) log(TAG, VERBOSE) { "setPermissions($path,$permissions)..." }
        runBlocking { fileSystemOps.setPermissions(path, permissions) }
    } catch (e: Exception) {
        log(TAG, ERROR) { "setModifiedAt(path=$path, permissions=$permissions) failed\n${e.asLog()}" }
        throw e.wrapToPropagate()
    }

    override fun setOwnership(path: LocalPath, ownership: Ownership): Boolean = try {
        if (Bugs.isTrace) log(TAG, VERBOSE) { "setPermissions($path,$ownership)..." }
        runBlocking { fileSystemOps.setOwnership(path, ownership) }
    } catch (e: Exception) {
        log(TAG, ERROR) { "setModifiedAt(path=$path, ownership=$ownership) failed\n${e.asLog()}" }
        throw e.wrapToPropagate()
    }

    override fun getFileSystem(path: LocalPath): FileSystem = try {
        if (Bugs.isTrace) log(TAG, VERBOSE) { "getFileSystem($path)..." }
        runBlocking { fileSystemOps.getFileSystem(path) }
    } catch (e: Exception) {
        log(TAG, ERROR) { "getFileSystem(path=$path) failed\n${e.asLog()}" }
        throw e.wrapToPropagate()
    }

    override fun deleteStream(
        targets: List<LocalPath>,
        recursive: Boolean,
        ignoreMissing: Boolean,
        callback: FileOperationCallback?
    ): RemoteInputStream = try {
        log(
            TAG,
            VERBOSE
        ) { "deleteStream(): ${targets.size} targets (recursive=$recursive, ignoreMissing=$ignoreMissing)" }

        // Create flow of DeleteOperationEvent by wrapping the delete operation
        // Use channelFlow instead of flow to support emissions from IPC callback coroutine
        // IMPORTANT: Do NOT use .catch{} or .onCompletion{} operators - they wrap channelFlow
        // in a SafeFlow which has invariant checks that fail when emitting from IPC callbacks
        val eventFlow = channelFlow {
            // Convert callback to issue handler (explicit suspend function)
            suspend fun handleIssue(issue: PathActionIssue): PathActionIssue.Resolution {
                val ipcIssue = issue.toFileOperationIssue()
                try {
                    val ipcResolution = callback!!.onIssue(ipcIssue)
                    return toPathActionIssueResolution(ipcResolution, issue)
                } catch (e: DeadObjectException) {
                    log(TAG, ERROR) { "Client process died during issue resolution" }
                    throw IOException("Client process died", e)
                } catch (e: RemoteException) {
                    log(TAG, ERROR) { "IPC error during issue resolution: ${e.asLog()}" }
                    throw IOException("IPC communication failed", e)
                }
            }

            val onIssue: (suspend (PathActionIssue) -> PathActionIssue.Resolution)? =
                if (callback != null) ::handleIssue else null

            try {
                // Execute delete and collect flow
                targets.delete(
                    fileSystemOps = fileSystemOps,
                    recursive = recursive,
                    ignoreMissing = ignoreMissing,
                    onIssue = onIssue,
                ).collect { state ->
                    // Convert and send each state as event
                    val event = state.toDeleteOperationEvent()
                    send(event)
                }
                log(TAG, VERBOSE) { "deleteStream() completed successfully" }
            } catch (e: Exception) {
                log(TAG, ERROR) { "deleteStream() operation failed: ${e.asLog()}" }
                if (e is CancellationException) {
                    // A cancelled scope has to unwind; a cancelled operation is a terminal event.
                    if (!currentCoroutineContext().isActive) throw e
                    send(DeleteOperationEvent.Error(error = e.message ?: "Cancelled", cancelled = true))
                    return@channelFlow
                }
                // Send error event instead of throwing
                send(
                    DeleteOperationEvent.Error(
                        error = IpcErrorCodec.encode(e),
                        cancelled = false
                    )
                )
            }
        }

        // Convert flow to RemoteInputStream using generic streaming
        eventFlow.toRemoteInputStream(appScope + dispatcherProvider.IO)
    } catch (e: Exception) {
        log(TAG, ERROR) { "deleteStream(targets=${targets.size}) setup failed\n${e.asLog()}" }
        throw e.wrapToPropagate()
    }

    override fun copyStream(
        sources: List<LocalPath>,
        destination: LocalPath,
        overwrite: Boolean,
        preserveAttributes: Boolean,
        followSymlinks: Boolean,
        callback: FileOperationCallback?
    ): RemoteInputStream = try {
        log(TAG, VERBOSE) { "copyStream(): ${sources.size} sources → $destination" }

        // Create flow of CopyOperationEvent by wrapping the copy operation
        // Use channelFlow instead of flow to support emissions from IPC callback coroutine
        // IMPORTANT: Do NOT use .catch{} or .onCompletion{} operators - they wrap channelFlow
        // in a SafeFlow which has invariant checks that fail when emitting from IPC callbacks
        val eventFlow = channelFlow {
            // Convert callback to issue handler (explicit suspend function)
            suspend fun handleIssue(issue: PathActionIssue): PathActionIssue.Resolution {
                val ipcIssue = issue.toFileOperationIssue()
                try {
                    val ipcResolution = callback!!.onIssue(ipcIssue)
                    return toPathActionIssueResolution(ipcResolution, issue)
                } catch (e: DeadObjectException) {
                    log(TAG, ERROR) { "Client process died during issue resolution" }
                    throw IOException("Client process died", e)
                } catch (e: RemoteException) {
                    log(TAG, ERROR) { "IPC error during issue resolution: ${e.asLog()}" }
                    throw IOException("IPC communication failed", e)
                }
            }

            val onIssue: (suspend (PathActionIssue) -> PathActionIssue.Resolution)? =
                if (callback != null) ::handleIssue else null

            try {
                // Execute copy and collect flow
                sources.toSet().copy(
                    fileSystemOps = fileSystemOps,
                    destination = destination,
                    options = CopyAction.Options(
                        overwrite = overwrite,
                        preserveAttributes = preserveAttributes,
                        followSymlinks = followSymlinks
                    ),
                    onIssue = onIssue
                ).collect { state ->
                    // Convert and send each state as event
                    val event = state.toCopyOperationEvent()
                    send(event)
                }
                log(TAG, VERBOSE) { "copyStream() completed successfully" }
            } catch (e: Exception) {
                log(TAG, ERROR) { "copyStream() operation failed: ${e.asLog()}" }
                if (e is CancellationException) {
                    // A cancelled scope has to unwind; a cancelled operation is a terminal event.
                    if (!currentCoroutineContext().isActive) throw e
                    send(CopyOperationEvent.Error(error = e.message ?: "Cancelled", cancelled = true))
                    return@channelFlow
                }
                // Send error event instead of throwing
                send(
                    CopyOperationEvent.Error(
                        error = IpcErrorCodec.encode(e),
                        cancelled = false
                    )
                )
            }
        }

        // Convert flow to RemoteInputStream using generic streaming
        eventFlow.toRemoteInputStream(appScope + dispatcherProvider.IO)
    } catch (e: Exception) {
        log(TAG, ERROR) { "copyStream(sources=${sources.size}) setup failed\n${e.asLog()}" }
        throw e.wrapToPropagate()
    }

    override fun moveStream(
        sources: List<LocalPath>,
        destination: LocalPath,
        overwrite: Boolean,
        preserveAttributes: Boolean,
        followSymlinks: Boolean,
        callback: FileOperationCallback?
    ): RemoteInputStream = try {
        log(TAG, VERBOSE) { "moveStream(): ${sources.size} sources → $destination" }

        // Create flow of MoveOperationEvent by wrapping the move operation
        // Use channelFlow instead of flow to support emissions from IPC callback coroutine
        // IMPORTANT: Do NOT use .catch{} or .onCompletion{} operators - they wrap channelFlow
        // in a SafeFlow which has invariant checks that fail when emitting from IPC callbacks
        val eventFlow = channelFlow {
            // Convert callback to issue handler (explicit suspend function)
            suspend fun handleIssue(issue: PathActionIssue): PathActionIssue.Resolution {
                val ipcIssue = issue.toFileOperationIssue()
                try {
                    val ipcResolution = callback!!.onIssue(ipcIssue)
                    return toPathActionIssueResolution(ipcResolution, issue)
                } catch (e: DeadObjectException) {
                    log(TAG, ERROR) { "Client process died during issue resolution" }
                    throw IOException("Client process died", e)
                } catch (e: RemoteException) {
                    log(TAG, ERROR) { "IPC error during issue resolution: ${e.asLog()}" }
                    throw IOException("IPC communication failed", e)
                }
            }

            val onIssue: (suspend (PathActionIssue) -> PathActionIssue.Resolution)? =
                if (callback != null) ::handleIssue else null

            try {
                // Execute move and collect flow
                sources.toSet().move(
                    fileSystemOps = fileSystemOps,
                    destination = destination,
                    options = MoveAction.Options(
                        overwrite = overwrite,
                        preserveAttributes = preserveAttributes,
                        attemptAtomicMove = true
                    ),
                    onIssue = onIssue
                ).collect { state ->
                    // Convert and send each state as event
                    val event = state.toMoveOperationEvent()
                    send(event)
                }
                log(TAG, VERBOSE) { "moveStream() completed successfully" }
            } catch (e: Exception) {
                log(TAG, ERROR) { "moveStream() operation failed: ${e.asLog()}" }
                if (e is CancellationException) {
                    // A cancelled scope has to unwind; a cancelled operation is a terminal event.
                    if (!currentCoroutineContext().isActive) throw e
                    send(MoveOperationEvent.Error(error = e.message ?: "Cancelled", cancelled = true))
                    return@channelFlow
                }
                // Send error event instead of throwing
                send(
                    MoveOperationEvent.Error(
                        error = IpcErrorCodec.encode(e),
                        cancelled = false
                    )
                )
            }
        }

        // Convert flow to RemoteInputStream using generic streaming
        eventFlow.toRemoteInputStream(appScope + dispatcherProvider.IO)
    } catch (e: Exception) {
        log(TAG, ERROR) { "moveStream(sources=${sources.size}) setup failed\n${e.asLog()}" }
        throw e.wrapToPropagate()
    }

    companion object {
        val TAG = logTag("FileOps", "Service", "Host", Bugs.processTag)
    }
}