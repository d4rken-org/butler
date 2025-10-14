package eu.darken.butler.common.files.local

import android.R
import android.R.attr.*
import eu.darken.butler.common.adb.AdbManager
import eu.darken.butler.common.adb.AdbUnavailableException
import eu.darken.butler.common.adb.canUseAdbNow
import eu.darken.butler.common.adb.service.runModuleAction
import eu.darken.butler.common.coroutine.AppScope
import eu.darken.butler.common.coroutine.DispatcherProvider
import eu.darken.butler.common.debug.logging.Logging.Priority.*
import eu.darken.butler.common.debug.logging.log
import eu.darken.butler.common.debug.logging.logTag
import eu.darken.butler.common.files.APathGateway
import eu.darken.butler.common.files.LocalPath
import eu.darken.butler.common.files.actions.CopyAction
import eu.darken.butler.common.files.actions.DeleteAction
import eu.darken.butler.common.files.actions.MoveAction
import eu.darken.butler.common.files.io.callbacks
import eu.darken.butler.common.files.local.accessibility.LocalPathAccessChecker
import eu.darken.butler.common.files.local.ipc.FileOpsClient
import eu.darken.butler.common.files.local.walkers.DirectLocalWalker
import eu.darken.butler.common.files.local.walkers.IndirectLocalWalker
import eu.darken.butler.common.files.metadata.FileSystemInfo
import eu.darken.butler.common.files.metadata.Ownership
import eu.darken.butler.common.files.metadata.Permissions
import eu.darken.butler.common.root.RootManager
import eu.darken.butler.common.root.RootUnavailableException
import eu.darken.butler.common.root.canUseRootNow
import eu.darken.butler.common.root.service.runModuleAction
import eu.darken.butler.common.sharedresource.SharedResource
import eu.darken.butler.common.sharedresource.keepResourcesAlive
import eu.darken.butler.common.storage.StorageEnvironment
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.onCompletion
import kotlinx.coroutines.plus
import kotlinx.coroutines.withContext
import okio.FileHandle
import okio.buffer
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.time.Instant

@Singleton
class LocalGateway @Inject constructor(
    @AppScope private val appScope: CoroutineScope,
    private val dispatcherProvider: DispatcherProvider,
    private val fileSystemOps: LocalFileSystemOps,
    private val storageEnvironment: StorageEnvironment,
    private val rootManager: RootManager,
    private val adbManager: AdbManager,
    private val accessibilityChecker: LocalPathAccessChecker,
) : APathGateway<LocalPath, LocalPathLookup, LocalPathLookupExtended> {

    // Represents the resource that keeps the gateway resources alive
    // Internal resources should add themselfes as child to this
    override val sharedResource = SharedResource.createKeepAlive(TAG, appScope + dispatcherProvider.IO)

    private suspend fun <T> rootOps(action: suspend (FileOpsClient) -> T): T {
        if (!rootManager.canUseRootNow()) throw RootUnavailableException()
        return keepResourcesAlive(rootManager.serviceClient) {
            rootManager.serviceClient.runModuleAction(FileOpsClient::class.java) { action(it) }
        }
    }

    private suspend fun <T> adbOps(action: suspend (FileOpsClient) -> T): T {
        if (!adbManager.canUseAdbNow()) throw AdbUnavailableException()
        return keepResourcesAlive(adbManager.serviceClient) {
            adbManager.serviceClient.runModuleAction(FileOpsClient::class.java) { action(it) }
        }
    }

    suspend fun hasRoot(): Boolean = rootManager.canUseRootNow()

    suspend fun hasAdb(): Boolean = adbManager.canUseAdbNow()

    private suspend fun <T> runIO(
        block: suspend CoroutineScope.() -> T
    ): T = withContext(dispatcherProvider.IO) {
        block()
    }

    /**
     * Executes a file operation with automatic mode selection and escalation.
     *
     * For Mode.AUTO: Tries normal mode first (ensures correct ownership), escalates to root/ADB on IOException.
     * For explicit modes: Executes directly without fallback.
     *
     * @param mode The requested execution mode
     * @param operation Operation name for logging (e.g., "createDir")
     * @param path Optional path for logging
     * @param normalOp Normal mode operation
     * @param rootOp Root mode operation (receives FileOpsClient)
     * @param adbOp ADB mode operation (receives FileOpsClient)
     * @return Result of the operation
     */
    private suspend fun <T> executeWithModeSelection(
        mode: Mode,
        operation: String,
        path: LocalPath,
        forWriting: Boolean,
        normalOp: suspend () -> T,
        rootOp: suspend (FileOpsClient) -> T,
        adbOp: suspend (FileOpsClient) -> T
    ): T = runIO {
        when (mode) {
            Mode.NORMAL -> {
                log(TAG, VERBOSE) { "$operation(NORMAL) -> $path" }
                normalOp()
            }
            Mode.ROOT -> {
                log(TAG, VERBOSE) { "$operation(ROOT) -> $path" }
                rootOps { rootOp(it) }
            }
            Mode.ADB -> {
                log(TAG, VERBOSE) { "$operation(ADB) -> $path" }
                adbOps { adbOp(it) }
            }
            Mode.AUTO -> {
                suspend fun escalation(): T = when {
                    hasRoot() -> {
                        log(TAG, VERBOSE) { "$operation(AUTO:ROOT) -> $path" }
                        rootOps { rootOp(it) }
                    }
                    hasAdb() -> {
                        log(TAG, VERBOSE) { "$operation(AUTO:ADB) -> $path" }
                        adbOps { adbOp(it) }
                    }
                    else -> throw IllegalStateException("No matching mode available.")
                }
                if (accessibilityChecker.shouldTryNormalAccess(path, forWriting)) {
                    try {
                        normalOp().also { log(TAG, VERBOSE) { "$operation(AUTO:NORMAL) -> $path" } }
                    } catch (e: IOException) {
                        log(TAG, VERBOSE) { "$operation(AUTO) failed: ${e.message}" }
                        try {
                            escalation()
                        } catch (_: IllegalStateException) {
                            throw e
                        }
                    }
                } else {
                    escalation()
                }
            }
        }
    }


    override suspend fun createDir(path: LocalPath): Unit = createDir(path, Mode.AUTO)

    suspend fun createDir(path: LocalPath, mode: Mode = Mode.AUTO): Unit = executeWithModeSelection(
        mode = mode,
        operation = "createDir",
        path = path,
        forWriting = true,
        normalOp = { fileSystemOps.createDir(path) },
        rootOp = { it.createDir(path) },
        adbOp = { it.createDir(path) }
    )


    override suspend fun createFile(path: LocalPath): Unit = createFile(path, Mode.AUTO)

    suspend fun createFile(path: LocalPath, mode: Mode = Mode.AUTO): Unit = executeWithModeSelection(
        mode = mode,
        operation = "createFile",
        path = path,
        forWriting = true,
        normalOp = { fileSystemOps.createFile(path) },
        rootOp = { it.createFile(path) },
        adbOp = { it.createFile(path) }
    )

    override suspend fun createSymlink(linkPath: LocalPath, targetPath: LocalPath): Boolean =
        createSymlink(linkPath, targetPath, Mode.AUTO)

    suspend fun createSymlink(
        linkPath: LocalPath,
        targetPath: LocalPath,
        mode: Mode = Mode.AUTO
    ): Boolean = executeWithModeSelection(
        mode = mode,
        operation = "createSymlink",
        path = linkPath,
        forWriting = true,
        normalOp = { fileSystemOps.createSymlink(linkPath, targetPath) },
        rootOp = { it.createSymlink(linkPath, targetPath) },
        adbOp = { it.createSymlink(linkPath, targetPath) }
    )

    override suspend fun lookup(path: LocalPath): LocalPathLookup = lookup(path, Mode.AUTO)

    suspend fun lookup(path: LocalPath, mode: Mode = Mode.AUTO): LocalPathLookup = executeWithModeSelection(
        mode = mode,
        operation = "lookup",
        path = path,
        forWriting = false,
        normalOp = { fileSystemOps.lookup(path) },
        rootOp = { it.lookup(path) },
        adbOp = { it.lookup(path) }
    )


    override suspend fun listFiles(path: LocalPath): List<LocalPath> = listFiles(path, Mode.AUTO)

    suspend fun listFiles(path: LocalPath, mode: Mode = Mode.AUTO): List<LocalPath> = executeWithModeSelection(
        mode = mode,
        operation = "listFiles",
        path = path,
        forWriting = false,
        normalOp = { fileSystemOps.listFiles(path) },
        rootOp = { it.listFiles(path) },
        adbOp = { it.listFiles(path) }
    )


    override suspend fun lookupExtended(path: LocalPath): LocalPathLookupExtended = lookupExtended(path, Mode.AUTO)

    suspend fun lookupExtended(
        path: LocalPath,
        mode: Mode = Mode.AUTO
    ): LocalPathLookupExtended = executeWithModeSelection(
        mode = mode,
        operation = "lookupExtended",
        path = path,
        forWriting = false,
        normalOp = { fileSystemOps.lookupExtended(path) },
        rootOp = { TODO() },
        adbOp = { TODO() }
    )

    override suspend fun lookupFiles(path: LocalPath): List<LocalPathLookup> = lookupFiles(path, Mode.AUTO)

    suspend fun lookupFiles(path: LocalPath, mode: Mode = Mode.AUTO): List<LocalPathLookup> = executeWithModeSelection(
        mode = mode,
        operation = "lookupFiles",
        path = path,
        forWriting = false,
        normalOp = { fileSystemOps.lookupFiles(path) },
        rootOp = { TODO() },
        adbOp = { TODO() }
    )

    override suspend fun lookupFilesExtended(
        path: LocalPath
    ): List<LocalPathLookupExtended> = lookupFilesExtended(path, Mode.AUTO)

    suspend fun lookupFilesExtended(
        path: LocalPath,
        mode: Mode = Mode.AUTO
    ): List<LocalPathLookupExtended> = executeWithModeSelection(
        mode = mode,
        operation = "lookupFilesExtended",
        path = path,
        forWriting = false,
        normalOp = { fileSystemOps.lookupFilesExtended(path) },
        rootOp = { rootOps { it.lookupFilesExtendedStream(path) } },
        adbOp = { adbOps { it.lookupFilesExtendedStream(path) } }
    )

    override suspend fun walk(
        path: LocalPath,
        options: APathGateway.WalkOptions<LocalPath, LocalPathLookup>,
    ): Flow<LocalPathLookup> = walk(path, options, Mode.AUTO)

    suspend fun walk(
        path: LocalPath,
        options: APathGateway.WalkOptions<LocalPath, LocalPathLookup>,
        mode: Mode = Mode.AUTO,
    ): Flow<LocalPathLookup> =
        executeWithModeSelection(
            mode = mode,
            operation = "walk",
            path = path,
            forWriting = false,
            normalOp = {
                DirectLocalWalker(
                    fileSystemOps = fileSystemOps,
                    start = path,
                    onFilter = { lookup -> options.onFilter?.invoke(lookup) ?: true },
                    onError = { lookup, exception -> options.onError?.invoke(lookup, exception) ?: true },
                )
            },
            rootOp = {
                if (options.isDirect) {
                    log(TAG, VERBOSE) { "walk(${R.attr.mode}->ROOT, direct): ${R.attr.path}" }
                    // We need to keep the resource alive until the caller is done with the Flow
                    val resource = rootManager.serviceClient.get()
                    rootOps { it.walk(path, options).onCompletion { resource.close() } }
                } else {
                    log(TAG, VERBOSE) { "walk(${R.attr.mode}->ROOT, indirect): ${R.attr.path}" }
                    // Can't pass functions via IPC
                    IndirectLocalWalker(
                        gateway = this@LocalGateway,
                        mode = Mode.ROOT,
                        start = path,
                        onFilter = { lookup -> options.onFilter?.invoke(lookup) ?: true },
                        onError = { lookup, exception -> options.onError?.invoke(lookup, exception) ?: true },
                    )
                }
            },
            adbOp = {
                if (options.isDirect) {
                    log(TAG, VERBOSE) { "walk(${R.attr.mode}->ADB, direct): ${R.attr.path}" }
                    // We need to keep the resource alive until the caller is done with the Flow
                    val resource = adbManager.serviceClient.get()
                    adbOps { it.walk(path, options).onCompletion { resource.close() } }
                } else {
                    log(TAG, VERBOSE) { "walk(${R.attr.mode}->ADB, indirect): ${R.attr.path}" }
                    // Can't pass functions via IPC
                    IndirectLocalWalker(
                        gateway = this@LocalGateway,
                        mode = Mode.ADB,
                        start = path,
                        onFilter = { lookup -> options.onFilter?.invoke(lookup) ?: true },
                        onError = { lookup, exception -> options.onError?.invoke(lookup, exception) ?: true },
                    )
                }
            }
        )

    override suspend fun du(
        path: LocalPath,
        options: APathGateway.DuOptions<LocalPath, LocalPathLookup>,
    ): Long = du(path, options, Mode.AUTO)

    suspend fun du(
        path: LocalPath,
        options: APathGateway.DuOptions<LocalPath, LocalPathLookup> = APathGateway.DuOptions(),
        mode: Mode = Mode.AUTO,
    ): Long = executeWithModeSelection(
        mode = mode,
        operation = "du",
        path = path,
        forWriting = false,
        normalOp = { fileSystemOps.du(path) },
        rootOp = { it.du(path) },
        adbOp = { it.du(path) }
    )


    override suspend fun exists(path: LocalPath): Boolean = exists(path, Mode.AUTO)

    suspend fun exists(
        path: LocalPath,
        mode: Mode = Mode.AUTO
    ): Boolean = executeWithModeSelection(
        mode = mode,
        operation = "exists",
        path = path,
        forWriting = false,
        normalOp = { fileSystemOps.exists(path) },
        rootOp = { rootOps { it.exists(path) } },
        adbOp = { adbOps { it.exists(path) } }
    )

    override suspend fun canWrite(path: LocalPath): Boolean = canWrite(path, Mode.AUTO)

    suspend fun canWrite(
        path: LocalPath,
        mode: Mode = Mode.AUTO
    ): Boolean = executeWithModeSelection(
        mode = mode,
        operation = "canWrite",
        path = path,
        forWriting = false,
        normalOp = { fileSystemOps.canWrite(path) },
        rootOp = { rootOps { it.canWrite(path) } },
        adbOp = { adbOps { it.canWrite(path) } }
    )

    override suspend fun canRead(path: LocalPath): Boolean = canRead(path, Mode.AUTO)

    suspend fun canRead(
        path: LocalPath,
        mode: Mode = Mode.AUTO
    ): Boolean = executeWithModeSelection(
        mode = mode,
        operation = "canRead",
        path = path,
        forWriting = false,
        normalOp = { fileSystemOps.canRead(path) },
        rootOp = { rootOps { it.canRead(path) } },
        adbOp = { adbOps { it.canRead(path) } }
    )

    override suspend fun file(path: LocalPath, readWrite: Boolean): FileHandle = file(path, readWrite, Mode.AUTO)

    suspend fun file(
        path: LocalPath,
        readWrite: Boolean,
        mode: Mode = Mode.AUTO
    ): FileHandle = executeWithModeSelection(
        mode = mode,
        operation = "file",
        path = path,
        forWriting = readWrite,
        normalOp = { fileSystemOps.file(path, readWrite) },
        rootOp = {
            val resource = rootManager.serviceClient.get()
            rootOps {
                it.file(path, readWrite).callbacks {
                    resource.close()
                    log(TAG, VERBOSE) { "file(ROOT, RW=$readWrite): Closing resource for $path" }
                }
            }
        },
        adbOp = {
            val resource = adbManager.serviceClient.get()
            adbOps {
                it.file(path, readWrite).callbacks {
                    resource.close()
                    log(TAG, VERBOSE) { "file(ADB, RW=$readWrite): Closing resource for $path" }
                }
            }
        }
    )

    override suspend fun openInputStream(path: LocalPath): InputStream = openInputStream(path, Mode.AUTO)

    suspend fun openInputStream(
        path: LocalPath,
        mode: Mode = Mode.AUTO
    ): InputStream = executeWithModeSelection(
        mode = mode,
        operation = "openInputStream",
        path = path,
        forWriting = false,
        normalOp = { fileSystemOps.openInputStream(path) },
        rootOp = {
            rootOps { client ->
                client.file(path, readWrite = false).source().buffer().inputStream()
            }
        },
        adbOp = {
            adbOps { client ->
                client.file(path, readWrite = false).source().buffer().inputStream()
            }
        }
    )

    override suspend fun openOutputStream(path: LocalPath, append: Boolean): OutputStream =
        openOutputStream(path, append, Mode.AUTO)

    suspend fun openOutputStream(
        path: LocalPath,
        append: Boolean = false,
        mode: Mode = Mode.AUTO
    ): OutputStream = executeWithModeSelection(
        mode = mode,
        operation = "openOutputStream",
        path = path,
        forWriting = true,
        normalOp = { fileSystemOps.openOutputStream(path, append) },
        rootOp = {
            it.file(path, readWrite = true).sink().buffer().outputStream()
        },
        adbOp = {
            it.file(path, readWrite = true).sink().buffer().outputStream()
        }
    )


    override suspend fun setModifiedAt(path: LocalPath, modifiedAt: Instant): Boolean = setModifiedAt(
        path,
        modifiedAt,
        Mode.AUTO
    )

    suspend fun setModifiedAt(
        path: LocalPath,
        modifiedAt: Instant,
        mode: Mode = Mode.AUTO,
    ): Boolean = executeWithModeSelection(
        mode = mode,
        operation = "setModifiedAt",
        path = path,
        forWriting = true,
        normalOp = { fileSystemOps.setModifiedAt(path, modifiedAt) },
        rootOp = { rootOps { it.setModifiedAt(path, modifiedAt) } },
        adbOp = { adbOps { it.setModifiedAt(path, modifiedAt) } }
    )

    override suspend fun setPermissions(path: LocalPath, permissions: Permissions): Boolean =
        setPermissions(path, permissions, Mode.AUTO)

    suspend fun setPermissions(
        path: LocalPath,
        permissions: Permissions,
        mode: Mode = Mode.AUTO
    ): Boolean = executeWithModeSelection(
        mode = mode,
        operation = "setPermissions",
        path = path,
        forWriting = true,
        normalOp = { fileSystemOps.setPermissions(path, permissions) },
        rootOp = { rootOps { it.setPermissions(path, permissions) } },
        adbOp = { adbOps { it.setPermissions(path, permissions) } }
    )

    override suspend fun setOwnership(
        path: LocalPath,
        ownership: Ownership
    ): Boolean = setOwnership(path, ownership, Mode.AUTO)

    suspend fun setOwnership(
        path: LocalPath,
        ownership: Ownership,
        mode: Mode = Mode.AUTO
    ): Boolean = executeWithModeSelection(
        mode = mode,
        operation = "setOwnership",
        path = path,
        forWriting = true,
        normalOp = { fileSystemOps.setOwnership(path, ownership) },
        rootOp = { rootOps { it.setOwnership(path, ownership) } },
        adbOp = { adbOps { it.setOwnership(path, ownership) } }
    )

    override suspend fun getInfo(path: LocalPath): FileSystemInfo = getInfo(path, Mode.AUTO)

    suspend fun getInfo(
        path: LocalPath,
        mode: Mode
    ): FileSystemInfo = executeWithModeSelection(
        mode = mode,
        operation = "getInfo",
        path = path,
        forWriting = false,
        normalOp = { fileSystemOps.getInfo(path) },
        rootOp = { TODO() },
        adbOp = { TODO() },
    )

    /**
     * Delete a single file or directory (primitive operation).
     *
     * This is a low-level primitive from [FileSystemOps] used internally by operations
     * like Move/Copy for overwrite scenarios. It provides simple Boolean success/failure
     * with no progress tracking or error handling.
     *
     * For user-facing deletions with progress updates, error handling, and "apply to all"
     * conflict resolution, use [delete] with Set<LocalPath> and Options instead.
     *
     * @param path The file or directory to delete
     * @param recursive If true, recursively delete directory contents (children before parents)
     * @return true if deleted successfully, false if path didn't exist
     * @see delete For high-level delete operation with progress tracking
     */
    override suspend fun delete(path: LocalPath, recursive: Boolean): Boolean = delete(path, recursive, Mode.AUTO)

    suspend fun delete(
        path: LocalPath,
        recursive: Boolean = false,
        mode: Mode = Mode.AUTO
    ): Boolean = executeWithModeSelection(
        mode = mode,
        operation = "delete",
        path = path,
        forWriting = true,
        normalOp = {
            fileSystemOps.delete(path, recursive)
            true
        },
        rootOp = { rootOps { it.delete(path, recursive) } },
        adbOp = { adbOps { it.delete(path, recursive) } }
    )

    /**
     * Delete multiple files and directories with progress tracking and error handling.
     *
     * This is the high-level operation from [DeleteAction] used for user-initiated deletions.
     * It orchestrates deletion via [GenericPathDelete], providing:
     * - Real-time progress updates via Flow
     * - "Apply to all" conflict resolution
     * - Error handling with skip/retry/cancel options
     * - Recursive directory traversal with post-order deletion
     *
     * For internal/primitive deletion (e.g., in Move/Copy overwrite scenarios) without
     * progress tracking, use [delete] with LocalPath instead.
     *
     * @param targets Set of files/directories to delete
     * @param options Deletion options (recursive, ignoreMissing, issue handler)
     * @return Flow of State updates (Progress and final Result)
     * @see delete For low-level primitive deletion without progress tracking
     */
    override suspend fun delete(
        targets: Set<LocalPath>,
        options: DeleteAction.Options<LocalPath>
    ): Flow<DeleteAction.State<LocalPath, LocalPathLookup>> = delete(targets, options, Mode.AUTO)

    fun delete(
        targets: Set<LocalPath>,
        options: DeleteAction.Options<LocalPath>,
        mode: Mode,
    ): Flow<DeleteAction.State<LocalPath, LocalPathLookup>> = flow {
        log(TAG, VERBOSE) { "delete(): ${targets.size} targets" }
        val result = when {
            hasRoot() && (mode == Mode.ROOT || mode == Mode.AUTO) -> {
                log(TAG, VERBOSE) { "delete($mode->ROOT): $path" }
                rootOps {
                    TODO()
//                        val success = it.delete(targets, recursive = true)
//                        if (!success) throw IOException("Root delete() call returned false")
                }
            }

            hasAdb() && (mode == Mode.ADB || mode == Mode.AUTO) -> {
                log(TAG, VERBOSE) { "delete($mode->ADB): $path" }
                adbOps {
                    TODO()
//                        val success = it.delete(targets, recursive = true)
//                        if (!success) throw IOException("ADB delete() call returned false")
                }
            }

            mode == Mode.NORMAL || mode == Mode.AUTO -> {
                log(TAG, VERBOSE) { "delete($mode->NORMAL): $path" }
                targets.delete(
                    recursive = options.recursive,
                    ignoreMissing = options.ignoreMissing,
                    onIssue = options.onIssue,
                    onProgress = { progress -> emit(progress) }
                )
            }

            else -> throw IOException("No matching mode available.")
        }

        log(TAG, INFO) { "delete(): Finished, deleted ${result.deleted} items" }
        emit(result)
    }.flowOn(dispatcherProvider.IO)


    override suspend fun copy(
        sources: Set<LocalPath>,
        destination: LocalPath,
        options: CopyAction.Options<LocalPath>
    ): Flow<CopyAction.State<LocalPath, LocalPathLookup>> = copy(sources, destination, options, Mode.AUTO)

    fun copy(
        sources: Set<LocalPath>,
        destination: LocalPath,
        options: CopyAction.Options<LocalPath>,
        mode: Mode = Mode.AUTO
    ): Flow<CopyAction.State<LocalPath, LocalPathLookup>> = flow {
        log(TAG, VERBOSE) { "copy(): ${sources.size} sources to $destination" }

        val result = when {
            hasRoot() && (mode == Mode.ROOT || mode == Mode.AUTO) -> {
                log(TAG, VERBOSE) { "copy($mode->ROOT): To $destination" }
                rootOps {
                    TODO()
//                        val success = it.delete(targets, recursive = true)
//                        if (!success) throw IOException("Root delete() call returned false")
                }
            }

            hasAdb() && (mode == Mode.ADB || mode == Mode.AUTO) -> {
                log(TAG, VERBOSE) { "copy($mode->ADB): To $destination" }
                adbOps {
                    TODO()
//                        val success = it.delete(targets, recursive = true)
//                        if (!success) throw IOException("ADB delete() call returned false")
                }
            }

            mode == Mode.NORMAL || mode == Mode.AUTO -> {
                log(TAG, VERBOSE) { "copy($mode->NORMAL): To $destination" }
                sources.copy(
                    destination,
                    onIssue = options.onIssue,
                    onProgress = { progress -> emit(progress) }
                )
            }

            else -> throw IOException("No matching mode available.")
        }

        log(TAG, INFO) { "copy(): Finished, copied ${result.copied} items" }
        emit(result)
    }.flowOn(dispatcherProvider.IO)

    override suspend fun move(
        sources: Set<LocalPath>,
        destination: LocalPath,
        options: MoveAction.Options<LocalPath>
    ): Flow<MoveAction.State<LocalPath, LocalPathLookup>> = move(sources, destination, options, Mode.AUTO)

    fun move(
        sources: Set<LocalPath>,
        destination: LocalPath,
        options: MoveAction.Options<LocalPath>,
        mode: Mode = Mode.AUTO,
    ): Flow<MoveAction.State<LocalPath, LocalPathLookup>> = flow {
        log(TAG, VERBOSE) { "move(): ${sources.size} sources to $destination" }
        val result = when {
            hasRoot() && (mode == Mode.ROOT || mode == Mode.AUTO) -> {
                log(TAG, VERBOSE) { "move($mode->ROOT): To $destination" }
                rootOps {
                    TODO("Root move implementation")
                }
            }

            hasAdb() && (mode == Mode.ADB || mode == Mode.AUTO) -> {
                log(TAG, VERBOSE) { "move($mode->ADB): To $destination" }
                adbOps {
                    TODO("ADB move implementation")
                }
            }

            mode == Mode.NORMAL || mode == Mode.AUTO -> {
                log(TAG, VERBOSE) { "move($mode->NORMAL): To $destination" }
                sources.move(
                    destination,
                    options,
                    onProgress = { progress -> emit(progress) },
                    onIssue = options.onIssue,
                )
            }

            else -> throw IOException("No matching mode available.")
        }

        log(TAG, INFO) { "move(): Finished, moved ${result.movedFiles} items" }
        emit(result)
    }.flowOn(dispatcherProvider.IO)

    enum class Mode {
        AUTO, NORMAL, ROOT, ADB
    }

    companion object {
        val TAG = logTag("Gateway", "Local")
    }
}