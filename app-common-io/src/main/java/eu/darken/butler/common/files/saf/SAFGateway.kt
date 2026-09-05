package eu.darken.butler.common.files.saf

import android.content.Intent
import android.os.ParcelFileDescriptor
import android.system.Os
import eu.darken.butler.common.coroutine.AppScope
import eu.darken.butler.common.coroutine.DispatcherProvider
import eu.darken.butler.common.debug.Bugs
import eu.darken.butler.common.debug.logging.Logging.Priority.*
import eu.darken.butler.common.debug.logging.asLog
import eu.darken.butler.common.debug.logging.log
import eu.darken.butler.common.debug.logging.logTag
import eu.darken.butler.common.files.APathGateway
import eu.darken.butler.common.files.FileSystemOps
import eu.darken.butler.common.files.LookupOptions
import eu.darken.butler.common.files.SAFPath
import eu.darken.butler.common.files.actions.CopyAction
import eu.darken.butler.common.files.actions.CreateAction
import eu.darken.butler.common.files.actions.DeleteAction
import eu.darken.butler.common.files.actions.MoveAction
import eu.darken.butler.common.files.actions.PathActionIssue
import eu.darken.butler.common.files.errors.ReadException
import eu.darken.butler.common.files.extensions.isDirectory
import eu.darken.butler.common.files.extensions.isFile
import eu.darken.butler.common.files.metadata.FileSystem
import eu.darken.butler.common.files.operations.createGeneric
import eu.darken.butler.common.files.saf.SAFFileSystemOps.*
import eu.darken.butler.common.sharedresource.SharedResource
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.plus
import kotlinx.coroutines.withContext
import okio.FileHandle
import java.io.IOException
import java.util.LinkedList
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class SAFGateway @Inject constructor(
    @AppScope private val appScope: CoroutineScope,
    private val fileSystemOps: SAFFileSystemOps,
    private val dispatcherProvider: DispatcherProvider,
) : APathGateway<SAFPath, SAFPathLookup>,
    FileSystemOps<SAFPath, SAFPathLookup> by fileSystemOps {

    override val sharedResource = SharedResource.createKeepAlive(TAG, appScope + dispatcherProvider.IO)

    private suspend fun <T> runIO(
        block: suspend CoroutineScope.() -> T
    ): T = withContext(dispatcherProvider.IO) { block() }

    override suspend fun walk(
        path: SAFPath,
        lookupOptions: LookupOptions,
        walkOptions: APathGateway.WalkOptions<SAFPath, SAFPathLookup>,
    ): Flow<SAFPathLookup> = flow {
        val start = lookup(path, lookupOptions)
        log(TAG, VERBOSE) { "walk($path) -> $start" }

        if (start.isFile) {
            emit(start)
            return@flow
        }

        val queue = LinkedList(listOf(start))

        while (!queue.isEmpty()) {
            val lookUp = queue.removeFirst()

            val newBatch = try {
                lookupFiles(lookUp.lookedUp, lookupOptions)
            } catch (e: IOException) {
                log(TAG, ERROR) { "Failed to read $lookUp: $e" }
                if (walkOptions.onError?.invoke(lookUp, e) != false) {
                    emptyList()
                } else {
                    throw e
                }
            }

            newBatch
                .filter {
                    val allowed = walkOptions.onFilter?.invoke(it) ?: true
                    if (Bugs.isTrace) {
                        if (!allowed) log(TAG, VERBOSE) { "Skipping (filter): $it" }
                    }
                    allowed
                }
                .forEach { child ->
                    if (child.isDirectory) {
                        if (Bugs.isTrace) log(TAG, VERBOSE) { "Walking: $child" }
                        queue.addFirst(child)
                    }
                    emit(child)
                }
        }
    }
        .flowOn(dispatcherProvider.IO)
        .catch { e ->
            log(TAG, WARN) { "walk($path) failed." }
            throw ReadException(path = path, cause = e)
        }

    override suspend fun du(
        path: SAFPath,
        options: APathGateway.DuOptions<SAFPath, SAFPathLookup>,
    ): Long = runIO {
        try {
            val start = lookup(path, LookupOptions(fetchSize = true))
            log(TAG, VERBOSE) { "du($path) -> $start" }

            if (start.isFile) return@runIO start.size ?: 0L

            var total = start.size ?: 0L

            val queue = LinkedList(listOf(start))
            while (!queue.isEmpty()) {
                val lookUp = queue.removeFirst()

                val newBatch = try {
                    lookupFiles(lookUp.lookedUp, LookupOptions(fetchSize = true))
                } catch (e: IOException) {
                    log(TAG, ERROR) { "Failed to read $lookUp: $e" }
                    emptyList()
                }

                newBatch.forEach { child ->
                    total += child.size ?: 0L
                    if (child.isDirectory) queue.addFirst(child)
                }
            }

            total
        } catch (e: Exception) {
            log(TAG, WARN) { "du($path) failed." }
            throw ReadException(path = path, cause = e)
        }
    }

    override suspend fun file(path: SAFPath, readWrite: Boolean): FileHandle = runIO {
        try {
            log(TAG, VERBOSE) { "file(readWrite=$readWrite): $path" }

            if (readWrite && !fileSystemOps.canWrite(path)) throw IOException("Permission denied: writable=false")
            else if (!fileSystemOps.canRead(path)) throw IOException("Permission denied: readable=false")

            val pfd = fileSystemOps.openPFD(path, if (readWrite) FileMode.READ_WRITE else FileMode.READ)
            pfd.toFileHandle(readWrite)
        } catch (e: Exception) {
            log(TAG, WARN) { "Failed to access from $path: ${e.asLog()}" }
            throw ReadException(path = path, cause = e)
        }
    }

    /**
     * Best-effort read-only [ParcelFileDescriptor] for streaming previews. Returns null (instead of
     * throwing) when the descriptor can't be opened, so preview callers can fall back to a placeholder.
     */
    suspend fun openReadPFD(path: SAFPath): ParcelFileDescriptor? = runIO {
        try {
            if (!fileSystemOps.canRead(path)) return@runIO null
            fileSystemOps.openPFD(path, FileMode.READ)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            log(TAG, WARN) { "openReadPFD failed for $path: ${e.asLog()}" }
            null
        }
    }

    override suspend fun getFileSystem(path: SAFPath): FileSystem = runIO {
        val statvfs = try {
            log(TAG, VERBOSE) { "getInfo(): $path" }

            val pfd = fileSystemOps.openPFD(path, FileMode.READ)
            pfd.use { Os.fstatvfs(it.fileDescriptor) }
        } catch (e: Exception) {
            log(TAG, ERROR) { "getInfo(): Failed on $path: ${e.asLog()}" }
            null
        }

        FileSystem(
            freeSpace = statvfs?.let { statvfs.f_bavail * statvfs.f_frsize },
            totalSpace = statvfs?.let { statvfs.f_blocks * statvfs.f_frsize },
        )
    }

    override suspend fun delete(
        targets: Set<SAFPath>,
        options: DeleteAction.Options<SAFPath>
    ): Flow<DeleteAction.State<SAFPath, SAFPathLookup>> = flow {
        log(TAG, VERBOSE) { "delete(): ${targets.size} targets" }

        targets.delete(
            fileSystemOps = fileSystemOps,
            recursive = options.recursive,
            ignoreMissing = options.ignoreMissing,
            onIssue = options.onIssue
        ).collect { state ->
            emit(state)
            if (state is DeleteAction.State.Completed) {
                log(TAG, INFO) { "delete(): Finished, deleted ${state.deleted.size} items" }
            }
        }
    }.flowOn(dispatcherProvider.IO)

    override suspend fun copy(
        sources: Set<SAFPath>,
        destination: SAFPath,
        onIssue: (suspend (PathActionIssue) -> PathActionIssue.Resolution)?,
        options: CopyAction.Options
    ): Flow<CopyAction.State<SAFPath, SAFPathLookup, SAFPath, SAFPathLookup>> = flow {
        log(TAG, VERBOSE) { "copy(): ${sources.size} sources to $destination" }

        sources.copy(
            destination = destination,
            fileSystemOps = fileSystemOps,
            onIssue = onIssue,
        ).collect { state ->
            emit(state)
            if (state is CopyAction.State.Completed) {
                log(TAG, INFO) { "copy(): Finished, copied ${state.copied.size} items" }
            }
        }
    }.flowOn(dispatcherProvider.IO)

    override suspend fun move(
        sources: Set<SAFPath>,
        destination: SAFPath,
        onIssue: (suspend (PathActionIssue) -> PathActionIssue.Resolution)?,
        options: MoveAction.Options
    ): Flow<MoveAction.State<SAFPath, SAFPathLookup, SAFPath, SAFPathLookup>> = flow {
        log(TAG, VERBOSE) { "move(): ${sources.size} sources to $destination" }

        sources.move(
            destination = destination,
            fileSystemOps = fileSystemOps,
            options = options,
            onIssue = onIssue,
        ).collect { state ->
            emit(state)
            if (state is MoveAction.State.Completed) {
                log(TAG, INFO) { "move(): Finished, moved ${state.movedFiles.size} items" }
            }
        }
    }.flowOn(dispatcherProvider.IO)

    override suspend fun create(
        target: SAFPath,
        type: CreateAction.CreateType,
        options: CreateAction.Options
    ): Flow<CreateAction.State<SAFPath, SAFPathLookup>> = flow {
        log(TAG, VERBOSE) { "create(): $target (type=$type)" }

        target.createGeneric(
            fileSystemOps = fileSystemOps,
            type = type,
            onIssue = options.onIssue,
        ).collect { state ->
            emit(state)
            if (state is CreateAction.State.Completed) {
                log(TAG, INFO) { "create(): Finished, created ${state.created}" }
            }
        }
    }.flowOn(dispatcherProvider.IO)

    companion object {
        val TAG = logTag("Gateway", "SAF")

        const val RW_FLAGSINT = Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION
    }
}