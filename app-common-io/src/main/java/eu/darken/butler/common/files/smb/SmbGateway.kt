package eu.darken.butler.common.files.smb

import eu.darken.butler.common.coroutine.AppScope
import eu.darken.butler.common.coroutine.DispatcherProvider
import eu.darken.butler.common.debug.Bugs
import eu.darken.butler.common.debug.logging.Logging.Priority.ERROR
import eu.darken.butler.common.debug.logging.Logging.Priority.INFO
import eu.darken.butler.common.debug.logging.Logging.Priority.VERBOSE
import eu.darken.butler.common.debug.logging.Logging.Priority.WARN
import eu.darken.butler.common.debug.logging.log
import eu.darken.butler.common.debug.logging.logTag
import eu.darken.butler.common.files.APathGateway
import eu.darken.butler.common.files.FileSystemOps
import eu.darken.butler.common.files.LookupOptions
import eu.darken.butler.common.files.SmbPath
import eu.darken.butler.common.files.actions.CopyAction
import eu.darken.butler.common.files.actions.CreateAction
import eu.darken.butler.common.files.actions.DeleteAction
import eu.darken.butler.common.files.actions.MoveAction
import eu.darken.butler.common.files.actions.PathActionIssue
import eu.darken.butler.common.files.errors.ReadException
import eu.darken.butler.common.files.extensions.isDirectory
import eu.darken.butler.common.files.extensions.isFile
import eu.darken.butler.common.files.operations.GenericCrossTypeCopyStrategy
import eu.darken.butler.common.files.operations.TransferStrategy
import eu.darken.butler.common.files.operations.copyGeneric
import eu.darken.butler.common.files.operations.createGeneric
import eu.darken.butler.common.files.operations.deleteGeneric
import eu.darken.butler.common.files.operations.moveGeneric
import eu.darken.butler.common.sharedresource.SharedResource
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.plus
import kotlinx.coroutines.runBlocking
import java.io.IOException
import java.util.LinkedList
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Gateway for [SmbPath]s. Primitives come from [SmbFileSystemOps], batch operations from the
 * generic operation framework, since SMB has no server-side copy we could take a shortcut through.
 */
@Singleton
class SmbGateway @Inject constructor(
    @AppScope private val appScope: CoroutineScope,
    private val dispatcherProvider: DispatcherProvider,
    private val fileSystemOps: SmbFileSystemOps,
    private val pool: SmbConnectionPool,
) : APathGateway<SmbPath, SmbPathLookup>,
    FileSystemOps<SmbPath, SmbPathLookup> by fileSystemOps {

    /**
     * Unlike the keep-alive resources of the other gateways this one owns the connection pool:
     * when the last user lets go, the sessions have to be closed rather than just forgotten.
     */
    override val sharedResource = SharedResource(
        tag = TAG,
        parentScope = appScope + dispatcherProvider.IO,
        source = callbackFlow {
            send(pool)
            awaitClose { runBlocking { pool.close() } }
        },
    )

    override suspend fun walk(
        path: SmbPath,
        lookupOptions: LookupOptions,
        walkOptions: APathGateway.WalkOptions<SmbPath, SmbPathLookup>,
    ): Flow<SmbPathLookup> = flow {
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
                    // Reparse points are not followed: the target may be outside the share entirely.
                    if (child.isDirectory) queue.addFirst(child)
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
        path: SmbPath,
        options: APathGateway.DuOptions<SmbPath, SmbPathLookup>,
    ): Long {
        val start = try {
            lookup(path, LookupOptions(fetchSize = true))
        } catch (e: Exception) {
            log(TAG, WARN) { "du($path) failed." }
            throw ReadException(path = path, cause = e)
        }
        if (start.isFile) return start.size ?: 0L

        var total = start.size ?: 0L
        val queue = LinkedList(listOf(start))
        while (!queue.isEmpty()) {
            val lookUp = queue.removeFirst()

            val newBatch = try {
                lookupFiles(lookUp.lookedUp, LookupOptions(fetchSize = true))
            } catch (e: IOException) {
                log(TAG, ERROR) { "Failed to read $lookUp: $e" }
                if (options.abortOnError) throw ReadException(path = path, cause = e)
                emptyList()
            }

            newBatch.forEach { child ->
                total += child.size ?: 0L
                if (child.isDirectory) queue.addFirst(child)
            }
        }
        return total
    }

    override suspend fun delete(
        targets: Set<SmbPath>,
        options: DeleteAction.Options<SmbPath>
    ): Flow<DeleteAction.State<SmbPath, SmbPathLookup>> = flow {
        log(TAG, VERBOSE) { "delete(): ${targets.size} targets" }

        targets.deleteGeneric(
            fileSystemOps = fileSystemOps,
            recursive = options.recursive,
            ignoreMissing = options.ignoreMissing,
            onIssue = options.onIssue,
        ).collect { state ->
            emit(state)
            if (state is DeleteAction.State.Completed) {
                log(TAG, INFO) { "delete(): Finished, deleted ${state.deleted.size} items" }
            }
        }
    }.flowOn(dispatcherProvider.IO)

    override suspend fun copy(
        sources: Set<SmbPath>,
        destination: SmbPath,
        onIssue: (suspend (PathActionIssue) -> PathActionIssue.Resolution)?,
        options: CopyAction.Options
    ): Flow<CopyAction.State<SmbPath, SmbPathLookup, SmbPath, SmbPathLookup>> = flow {
        log(TAG, VERBOSE) { "copy(): ${sources.size} sources to $destination" }

        sources.copyGeneric(
            destination = destination,
            sourceOps = fileSystemOps,
            destOps = fileSystemOps,
            strategy = GenericCrossTypeCopyStrategy(),
            options = TransferStrategy.Options(
                preserveAttributes = options.preserveAttributes,
                followSymlinks = options.followSymlinks,
            ),
            onIssue = onIssue,
        ).collect { state ->
            emit(state)
            if (state is CopyAction.State.Completed) {
                log(TAG, INFO) { "copy(): Finished, copied ${state.copied.size} items" }
            }
        }
    }.flowOn(dispatcherProvider.IO)

    override suspend fun move(
        sources: Set<SmbPath>,
        destination: SmbPath,
        onIssue: (suspend (PathActionIssue) -> PathActionIssue.Resolution)?,
        options: MoveAction.Options
    ): Flow<MoveAction.State<SmbPath, SmbPathLookup, SmbPath, SmbPathLookup>> = flow {
        log(TAG, VERBOSE) { "move(): ${sources.size} sources to $destination" }

        sources.moveGeneric(
            destination = destination,
            sourceOps = fileSystemOps,
            destOps = fileSystemOps,
            strategy = SmbPathMoveStrategy(),
            options = TransferStrategy.Options(
                preserveAttributes = options.preserveAttributes,
                followSymlinks = false,
                overwrite = options.overwrite,
                attemptAtomicMove = options.attemptAtomicMove,
            ),
            onIssue = onIssue,
        ).collect { state ->
            emit(state)
            if (state is MoveAction.State.Completed) {
                log(TAG, INFO) { "move(): Finished, moved ${state.movedFiles.size} items" }
            }
        }
    }.flowOn(dispatcherProvider.IO)

    override suspend fun create(
        target: SmbPath,
        type: CreateAction.CreateType,
        options: CreateAction.Options
    ): Flow<CreateAction.State<SmbPath, SmbPathLookup>> = flow {
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
        val TAG = logTag("Gateway", "SMB")
    }
}
