package eu.darken.butler.common.files.local

import eu.darken.butler.common.ElevatedAccessUnavailableException
import eu.darken.butler.common.adb.AdbManager
import eu.darken.butler.common.adb.AdbUnavailableException
import eu.darken.butler.common.adb.canUseAdbNow
import eu.darken.butler.common.adb.service.runModuleAction
import eu.darken.butler.common.coroutine.AppScope
import eu.darken.butler.common.coroutine.DispatcherProvider
import eu.darken.butler.common.debug.logging.Logging.Priority.*
import eu.darken.butler.common.debug.logging.log
import eu.darken.butler.common.debug.logging.logTag
import eu.darken.butler.common.error.causeChain
import eu.darken.butler.common.files.APathGateway
import eu.darken.butler.common.files.LocalPath
import eu.darken.butler.common.files.LookupOptions
import eu.darken.butler.common.files.actions.CopyAction
import eu.darken.butler.common.files.actions.CreateAction
import eu.darken.butler.common.files.actions.DeleteAction
import eu.darken.butler.common.files.actions.MoveAction
import eu.darken.butler.common.files.actions.PathActionIssue
import eu.darken.butler.common.files.errors.PathException
import eu.darken.butler.common.files.errors.StorageDisconnectedException
import eu.darken.butler.common.files.io.callbacks
import eu.darken.butler.common.files.local.accessibility.LocalPathAccessChecker
import eu.darken.butler.common.files.local.ipc.FileOpsClient
import eu.darken.butler.common.files.local.service.LocalServiceClient
import eu.darken.butler.common.files.local.service.LocalServiceClient.*
import eu.darken.butler.common.files.local.service.runModuleAction
import eu.darken.butler.common.files.local.walkers.DirectLocalWalker
import eu.darken.butler.common.files.local.walkers.IndirectLocalWalker
import eu.darken.butler.common.files.metadata.FileSystem
import eu.darken.butler.common.files.metadata.Ownership
import eu.darken.butler.common.files.metadata.Permissions
import eu.darken.butler.common.files.operations.createGeneric
import eu.darken.butler.common.root.RootManager
import eu.darken.butler.common.root.RootUnavailableException
import eu.darken.butler.common.root.canUseRootNow
import eu.darken.butler.common.root.service.runModuleAction
import eu.darken.butler.common.sharedresource.SharedResource
import eu.darken.butler.common.sharedresource.keepResourcesAlive
import eu.darken.butler.common.storage.StorageManager2
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.onCompletion
import kotlinx.coroutines.plus
import kotlinx.coroutines.withContext
import okio.FileHandle
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
    private val rootManager: RootManager,
    private val adbManager: AdbManager,
    private val accessChecker: LocalPathAccessChecker,
    private val localServiceClient: LocalServiceClient,
    private val storageManager: StorageManager2,
) : APathGateway<LocalPath, LocalPathLookup> {

    // Represents the resource that keeps the gateway resources alive
    // Internal resources should add themselfes as child to this
    override val sharedResource = SharedResource.createKeepAlive(TAG, appScope + dispatcherProvider.IO)

    private suspend fun <T> localOps(action: suspend (FileOpsClient) -> T): T {
        return try {
            keepResourcesAlive(localServiceClient) {
                localServiceClient.runModuleAction(FileOpsClient::class.java) { action(it) }
            }
        } catch (e: ServiceProcessDiedException) {
            throw StorageDisconnectedException(cause = e)
        }
    }

    private suspend fun <T> adbOps(action: suspend (FileOpsClient) -> T): T {
        if (!adbManager.canUseAdbNow()) throw AdbUnavailableException()
        return keepResourcesAlive(adbManager.serviceClient) {
            adbManager.serviceClient.runModuleAction(FileOpsClient::class.java) { action(it) }
        }
    }

    private suspend fun <T> rootOps(action: suspend (FileOpsClient) -> T): T {
        if (!rootManager.canUseRootNow()) throw RootUnavailableException()
        return keepResourcesAlive(rootManager.serviceClient) {
            rootManager.serviceClient.runModuleAction(FileOpsClient::class.java) { action(it) }
        }
    }

    private fun isOnRemovableStorage(path: LocalPath): Boolean {
        val volume = storageManager.storageVolumes.firstOrNull { volume ->
            volume.directory?.let { path.path.startsWith(it.path) } == true
        }
        return volume?.isRemovable == true
    }

    suspend fun hasRoot(): Boolean = rootManager.canUseRootNow()

    suspend fun hasAdb(): Boolean = adbManager.canUseAdbNow()

    private suspend fun <T> runIO(
        block: suspend CoroutineScope.() -> T
    ): T = withContext(dispatcherProvider.IO) { block() }

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
        localOp: suspend (FileOpsClient) -> T,
        rootOp: suspend (FileOpsClient) -> T,
        adbOp: suspend (FileOpsClient) -> T
    ): T = runIO {
        when (mode) {
            Mode.DIRECT -> {
                log(TAG, VERBOSE) { "$operation(DIRECT) -> $path" }
                normalOp()
            }
            Mode.LOCAL -> {
                log(TAG, VERBOSE) { "$operation(LOCAL) -> $path" }
                localOps { localOp(it) }
            }
            Mode.ADB -> {
                log(TAG, VERBOSE) { "$operation(ADB) -> $path" }
                adbOps { adbOp(it) }
            }
            Mode.ROOT -> {
                log(TAG, VERBOSE) { "$operation(ROOT) -> $path" }
                rootOps { rootOp(it) }
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
                    else -> throw ElevatedAccessUnavailableException("No matching mode available for $operation")
                }
                if (accessChecker.shouldTryNormalAccess(path, forWriting)) {
                    try {
                        normalOp().also { log(TAG, VERBOSE) { "$operation(AUTO:DIRECT) -> $path" } }
                    } catch (e: IOException) {
                        log(TAG, VERBOSE) { "$operation(AUTO) failed: ${e.message}" }
                        try {
                            escalation()
                        } catch (_: ElevatedAccessUnavailableException) {
                            throw e
                        }
                    }
                } else {
                    escalation()
                }
            }
        }
    }

    override suspend fun createDir(path: LocalPath, createParents: Boolean): Unit =
        createDir(path, createParents, Mode.AUTO)

    suspend fun createDir(
        path: LocalPath,
        createParents: Boolean = false,
        mode: Mode = Mode.AUTO
    ): Unit = executeWithModeSelection(
        mode = mode,
        operation = "createDir",
        path = path,
        forWriting = true,
        normalOp = { fileSystemOps.createDir(path, createParents) },
        localOp = { it.createDir(path, createParents) },
        rootOp = { it.createDir(path, createParents) },
        adbOp = { it.createDir(path, createParents) },
    )


    override suspend fun createFile(path: LocalPath, createParents: Boolean): Unit =
        createFile(path, createParents, Mode.AUTO)

    suspend fun createFile(
        path: LocalPath,
        createParents: Boolean = false,
        mode: Mode = Mode.AUTO
    ): Unit = executeWithModeSelection(
        mode = mode,
        operation = "createFile",
        path = path,
        forWriting = true,
        normalOp = { fileSystemOps.createFile(path, createParents) },
        localOp = { it.createFile(path, createParents) },
        rootOp = { it.createFile(path, createParents) },
        adbOp = { it.createFile(path, createParents) },
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
        localOp = { it.createSymlink(linkPath, targetPath) },
        adbOp = { it.createSymlink(linkPath, targetPath) },
        rootOp = { it.createSymlink(linkPath, targetPath) },
    )

    override suspend fun readSymbolicLink(linkPath: LocalPath): LocalPath =
        readSymbolicLink(linkPath, Mode.AUTO)

    suspend fun readSymbolicLink(
        linkPath: LocalPath,
        mode: Mode = Mode.AUTO
    ): LocalPath = executeWithModeSelection(
        mode = mode,
        operation = "readSymbolicLink",
        path = linkPath,
        forWriting = false,
        normalOp = { fileSystemOps.readSymbolicLink(linkPath) },
        localOp = { it.readSymbolicLink(linkPath) },
        adbOp = { it.readSymbolicLink(linkPath) },
        rootOp = { it.readSymbolicLink(linkPath) },
    )

    override suspend fun move(source: LocalPath, destination: LocalPath): Boolean =
        move(source, destination, Mode.AUTO)

    suspend fun move(
        source: LocalPath,
        destination: LocalPath,
        mode: Mode = Mode.AUTO
    ): Boolean = executeWithModeSelection(
        mode = mode,
        operation = "move",
        path = source,
        forWriting = true,
        normalOp = { fileSystemOps.move(source, destination) },
        localOp = { it.move(source, destination) },
        adbOp = { it.move(source, destination) },
        rootOp = { it.move(source, destination) },
    )

    override suspend fun lookup(path: LocalPath, options: LookupOptions): LocalPathLookup =
        lookup(path, options, Mode.AUTO)

    suspend fun lookup(
        path: LocalPath,
        options: LookupOptions,
        mode: Mode = Mode.AUTO
    ): LocalPathLookup = executeWithModeSelection(
        mode = mode,
        operation = "lookup",
        path = path,
        forWriting = false,
        normalOp = { fileSystemOps.lookup(path, options) },
        localOp = { it.lookup(path, options) },
        adbOp = { it.lookup(path, options) },
        rootOp = { it.lookup(path, options) },
    )


    override suspend fun listFiles(path: LocalPath): List<LocalPath> = listFiles(path, Mode.AUTO)

    suspend fun listFiles(
        path: LocalPath,
        mode: Mode = Mode.AUTO
    ): List<LocalPath> = executeWithModeSelection(
        mode = mode,
        operation = "listFiles",
        path = path,
        forWriting = false,
        normalOp = { fileSystemOps.listFiles(path) },
        localOp = { it.listFiles(path) },
        adbOp = { it.listFiles(path) },
        rootOp = { it.listFiles(path) },
    )


    override suspend fun lookupFiles(path: LocalPath, options: LookupOptions): List<LocalPathLookup> =
        lookupFiles(path, options, Mode.AUTO)

    suspend fun lookupFiles(
        path: LocalPath,
        options: LookupOptions,
        mode: Mode = Mode.AUTO
    ): List<LocalPathLookup> = executeWithModeSelection(
        mode = mode,
        operation = "lookupFiles",
        path = path,
        forWriting = false,
        normalOp = { fileSystemOps.lookupFiles(path, options) },
        localOp = { it.lookupFiles(path, options) },
        adbOp = { it.lookupFiles(path, options) },
        rootOp = { it.lookupFiles(path, options) },
    )


    override suspend fun walk(
        path: LocalPath,
        lookupOptions: LookupOptions,
        walkOptions: APathGateway.WalkOptions<LocalPath, LocalPathLookup>,
    ): Flow<LocalPathLookup> = walk(path, lookupOptions, walkOptions, Mode.AUTO)

    suspend fun walk(
        path: LocalPath,
        lookupOptions: LookupOptions,
        walkOptions: APathGateway.WalkOptions<LocalPath, LocalPathLookup>,
        mode: Mode = Mode.AUTO,
    ): Flow<LocalPathLookup> = executeWithModeSelection(
        mode = mode,
        operation = "walk",
        path = path,
        forWriting = false,
        normalOp = {
            DirectLocalWalker(
                fileSystemOps = fileSystemOps,
                lookupOptions = lookupOptions,
                start = path,
                onFilter = { lookup -> walkOptions.onFilter?.invoke(lookup) ?: true },
                onError = { lookup, exception -> walkOptions.onError?.invoke(lookup, exception) ?: true },
            )
        },
        localOp = {
            if (walkOptions.isDirect) {
                log(TAG, VERBOSE) { "walk(${mode}->LOCAL, direct): $path" }
                // We need to keep the resource alive until the caller is done with the Flow
                val resource = localServiceClient.get()
                it.walk(path, lookupOptions, walkOptions).onCompletion { resource.close() }
            } else {
                log(TAG, VERBOSE) { "walk(${mode}->LOCAL, indirect): $path" }
                // Can't pass functions via IPC
                IndirectLocalWalker(
                    gateway = this@LocalGateway,
                    mode = Mode.LOCAL,
                    start = path,
                    lookupOptions = lookupOptions,
                    onFilter = { lookup -> walkOptions.onFilter?.invoke(lookup) ?: true },
                    onError = { lookup, exception -> walkOptions.onError?.invoke(lookup, exception) ?: true },
                )
            }
        },
        adbOp = {
            if (walkOptions.isDirect) {
                log(TAG, VERBOSE) { "walk(${mode}->ADB, direct): $path" }
                // We need to keep the resource alive until the caller is done with the Flow
                val resource = adbManager.serviceClient.get()
                 it.walk(path, lookupOptions, walkOptions).onCompletion { resource.close() }
            } else {
                log(TAG, VERBOSE) { "walk(${mode}->ADB, indirect): $path" }
                // Can't pass functions via IPC
                IndirectLocalWalker(
                    gateway = this@LocalGateway,
                    mode = Mode.ADB,
                    start = path,
                    lookupOptions = lookupOptions,
                    onFilter = { lookup -> walkOptions.onFilter?.invoke(lookup) ?: true },
                    onError = { lookup, exception -> walkOptions.onError?.invoke(lookup, exception) ?: true },
                )
            }
        },
        rootOp = {
            if (walkOptions.isDirect) {
                log(TAG, VERBOSE) { "walk(${mode}->ROOT, direct): $path" }
                // We need to keep the resource alive until the caller is done with the Flow
                val resource = rootManager.serviceClient.get()
                 it.walk(path, lookupOptions, walkOptions).onCompletion { resource.close() }
            } else {
                log(TAG, VERBOSE) { "walk(${mode}->ROOT, indirect): $path" }
                // Can't pass functions via IPC
                IndirectLocalWalker(
                    gateway = this@LocalGateway,
                    mode = Mode.ROOT,
                    start = path,
                    lookupOptions = lookupOptions,
                    onFilter = { lookup -> walkOptions.onFilter?.invoke(lookup) ?: true },
                    onError = { lookup, exception -> walkOptions.onError?.invoke(lookup, exception) ?: true },
                )
            }
        },
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
        localOp = { it.du(path) },
        adbOp = { it.du(path) },
        rootOp = { it.du(path) },
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
        localOp = { it.exists(path) },
        adbOp = { it.exists(path) },
        rootOp = { it.exists(path) },
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
        localOp = { it.canWrite(path) },
        adbOp = { it.canWrite(path) },
        rootOp = { it.canWrite(path) },
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
        localOp = { it.canRead(path) },
        adbOp = { it.canRead(path) },
        rootOp = { it.canRead(path) },
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
        localOp = {
            val resource = localServiceClient.get()

            it.file(path, readWrite).callbacks {
                resource.close()
                log(TAG, VERBOSE) { "file(LOCAL, RW=$readWrite): Closing resource for $path" }
            }
        },
        adbOp = {
            val resource = adbManager.serviceClient.get()

            it.file(path, readWrite).callbacks {
                resource.close()
                log(TAG, VERBOSE) { "file(ADB, RW=$readWrite): Closing resource for $path" }
            }
        },
        rootOp = {
            val resource = rootManager.serviceClient.get()

            it.file(path, readWrite).callbacks {
                resource.close()
                log(TAG, VERBOSE) { "file(ROOT, RW=$readWrite): Closing resource for $path" }
            }
        },
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
        localOp = { it.openInputStream(path) },
        adbOp = { it.openInputStream(path) },
        rootOp = { it.openInputStream(path) },
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
        localOp = { it.openOutputStream(path, append) },
        adbOp = { it.openOutputStream(path, append) },
        rootOp = { it.openOutputStream(path, append) },
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
        localOp = { it.setModifiedAt(path, modifiedAt) },
        adbOp = { it.setModifiedAt(path, modifiedAt) },
        rootOp = { it.setModifiedAt(path, modifiedAt) },
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
        localOp = { it.setPermissions(path, permissions) },
        adbOp = { it.setPermissions(path, permissions) },
        rootOp = { it.setPermissions(path, permissions) },
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
        localOp = { it.setOwnership(path, ownership) },
        adbOp = { it.setOwnership(path, ownership) },
        rootOp = { it.setOwnership(path, ownership) },
    )

    override suspend fun getFileSystem(path: LocalPath): FileSystem = getFileSystem(path, Mode.AUTO)

    suspend fun getFileSystem(
        path: LocalPath,
        mode: Mode
    ): FileSystem = executeWithModeSelection(
        mode = mode,
        operation = "getInfo",
        path = path,
        forWriting = false,
        normalOp = { fileSystemOps.getFileSystem(path) },
        localOp = { it.getFileSystem(path) },
        adbOp = { it.getFileSystem(path) },
        rootOp = { it.getFileSystem(path) },
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
        localOp = { it.delete(path, recursive) },
        adbOp = { it.delete(path, recursive) },
        rootOp = { it.delete(path, recursive) },
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
        when (mode) {
            Mode.DIRECT -> {
                log(TAG, VERBOSE) { "delete(DIRECT): ${targets.size} targets" }
                targets.delete(
                    fileSystemOps,
                    recursive = options.recursive,
                    ignoreMissing = options.ignoreMissing,
                    onIssue = options.onIssue,
                ).collect { state ->
                    emit(state)
                    if (state is DeleteAction.State.Completed) {
                        log(TAG, INFO) { "delete(): Finished, deleted ${state.deleted.size} items" }
                    }
                }
            }

            Mode.ROOT -> {
                log(TAG, VERBOSE) { "delete(ROOT): ${targets.size} targets" }
                rootOps { client ->
                    client.delete(
                        targets = targets,
                        options = options
                    ).collect { state ->
                        emit(state)
                        if (state is DeleteAction.State.Completed) {
                            log(TAG, INFO) { "delete(): Finished, deleted ${state.deleted.size} items" }
                        }
                    }
                }
            }

            Mode.ADB -> {
                log(TAG, VERBOSE) { "delete(ADB): ${targets.size} targets" }
                adbOps { client ->
                    client.delete(
                        targets = targets,
                        options = options
                    ).collect { state ->
                        emit(state)
                        if (state is DeleteAction.State.Completed) {
                            log(TAG, INFO) { "delete(): Finished, deleted ${state.deleted.size} items" }
                        }
                    }
                }
            }

            Mode.LOCAL -> {
                log(TAG, VERBOSE) { "delete(LOCAL): ${targets.size} targets" }
                localOps { client ->
                    client.delete(
                        targets = targets,
                        options = options
                    ).collect { state ->
                        emit(state)
                        if (state is DeleteAction.State.Completed) {
                            log(TAG, INFO) { "delete(): Finished, deleted ${state.deleted.size} items" }
                        }
                    }
                }
            }

            Mode.AUTO -> {
                // Use isolated process for removable storage to survive sudden disconnection
                val useIsolatedProcess = targets.any { isOnRemovableStorage(it) }
                if (useIsolatedProcess && !hasRoot() && !hasAdb()) {
                    log(TAG, VERBOSE) { "delete(AUTO->LOCAL_SERVICE): Removable storage detected" }
                    localOps { client ->
                        client.delete(targets, options).collect { emit(it) }
                    }
                    return@flow
                }

                val shouldTry = accessChecker.shouldTryNormalAccess(targets.first(), forWriting = true)
                when {
                    shouldTry || (!hasAdb() && !hasRoot()) -> {
                        var hasEscalated = false
                        val escalationAwareOnIssue = createEscalationAwareOnIssue(
                            operationName = "delete()",
                            originalOnIssue = options.onIssue,
                            hasEscalatedRef = { hasEscalated },
                            markEscalated = { hasEscalated = true }
                        )

                        try {
                            log(TAG, VERBOSE) { "delete(AUTO->NORMAL, $shouldTry): ${targets.size} targets" }
                            targets.delete(
                                fileSystemOps,
                                recursive = options.recursive,
                                ignoreMissing = options.ignoreMissing,
                                onIssue = escalationAwareOnIssue,
                            ).collect { state ->
                                emit(state)
                                if (state is DeleteAction.State.Completed) {
                                    log(TAG, INFO) { "delete(): Finished, deleted ${state.deleted.size} items" }
                                }
                            }
                        } catch (e: Exception) {
                            log(TAG, VERBOSE) { "delete(AUTO->NORMAL): Error: ${e.message}" }
                            if (e.isPermissionError() && hasEscalated && (hasRoot() || hasAdb())) {
                                log(TAG, INFO) { "delete(AUTO->NORMAL->ROOT/ADB): Escalating after permission error" }
                                when {
                                    hasRoot() -> {
                                        log(TAG, VERBOSE) { "delete(AUTO->NORMAL->ROOT): ${targets.size} targets" }
                                        rootOps { client ->
                                            client.delete(
                                                targets = targets,
                                                options = options.copy(onIssue = escalationAwareOnIssue)
                                            ).collect { emit(it) }
                                        }
                                    }
                                    hasAdb() -> {
                                        log(TAG, VERBOSE) { "delete(AUTO->NORMAL->ADB): ${targets.size} targets" }
                                        adbOps { client ->
                                            client.delete(
                                                targets = targets,
                                                options = options.copy(onIssue = escalationAwareOnIssue)
                                            ).collect { emit(it) }
                                        }
                                    }
                                }
                            } else {
                                throw e
                            }
                        }
                    }
                    hasRoot() -> {
                        log(TAG, VERBOSE) { "delete(AUTO->ROOT): ${targets.size} targets" }
                        rootOps { client ->
                            client.delete(targets = targets, options = options).collect { emit(it) }
                        }
                    }
                    hasAdb() -> {
                        log(TAG, VERBOSE) { "delete(AUTO->ADB): ${targets.size} targets" }
                        adbOps { client ->
                            client.delete(targets = targets, options = options).collect { emit(it) }
                        }
                    }
                    else -> throw ElevatedAccessUnavailableException("No matching mode available.")
                }
            }
        }
    }.flowOn(dispatcherProvider.IO)

    override suspend fun copy(
        sources: Set<LocalPath>,
        destination: LocalPath,
        onIssue: (suspend (PathActionIssue) -> PathActionIssue.Resolution)?,
        options: CopyAction.Options
    ): Flow<CopyAction.State<LocalPath, LocalPathLookup, LocalPath, LocalPathLookup>> =
        copy(sources, destination, onIssue, options, Mode.AUTO)

    fun copy(
        sources: Set<LocalPath>,
        destination: LocalPath,
        onIssue: (suspend (PathActionIssue) -> PathActionIssue.Resolution)?,
        options: CopyAction.Options,
        mode: Mode = Mode.AUTO
    ): Flow<CopyAction.State<LocalPath, LocalPathLookup, LocalPath, LocalPathLookup>> = flow {
        log(TAG, VERBOSE) { "copy(): ${sources.size} sources to $destination" }

        when (mode) {
            Mode.DIRECT -> {
                log(TAG, VERBOSE) { "copy(DIRECT): To $destination" }
                sources.copy(
                    fileSystemOps = fileSystemOps,
                    destination = destination,
                    options = options,
                    onIssue = onIssue,
                ).collect { state ->
                    emit(state)
                    if (state is CopyAction.State.Completed<*, *, *, *>) {
                        log(TAG, INFO) { "copy(): Finished, copied ${state.copied.size} items" }
                    }
                }
            }

            Mode.ROOT -> {
                log(TAG, VERBOSE) { "copy(ROOT): To $destination" }
                rootOps { client ->
                    client.copy(
                        sources = sources,
                        destination = destination,
                        onIssue = onIssue,
                        options = options
                    ).collect { state ->
                        emit(state)
                        if (state is CopyAction.State.Completed) {
                            log(TAG, INFO) { "copy(): Finished, copied ${state.copied.size} items" }
                        }
                    }
                }
            }

            Mode.ADB -> {
                log(TAG, VERBOSE) { "copy(ADB): To $destination" }
                adbOps { client ->
                    client.copy(
                        sources = sources,
                        destination = destination,
                        onIssue = onIssue,
                        options = options
                    ).collect { state ->
                        emit(state)
                        if (state is CopyAction.State.Completed) {
                            log(TAG, INFO) { "copy(): Finished, copied ${state.copied.size} items" }
                        }
                    }
                }
            }

            Mode.LOCAL -> {
                log(TAG, VERBOSE) { "copy(LOCAL): To $destination" }
                localOps { client ->
                    client.copy(
                        sources = sources,
                        destination = destination,
                        onIssue = onIssue,
                        options = options
                    ).collect { state ->
                        emit(state)
                        if (state is CopyAction.State.Completed) {
                            log(TAG, INFO) { "copy(): Finished, copied ${state.copied.size} items" }
                        }
                    }
                }
            }

            Mode.AUTO -> {
                // Use isolated process for removable storage to survive sudden disconnection
                val useIsolatedProcess = isOnRemovableStorage(destination) ||
                    sources.any { isOnRemovableStorage(it) }
                if (useIsolatedProcess && !hasRoot() && !hasAdb()) {
                    log(TAG, VERBOSE) { "copy(AUTO->LOCAL_SERVICE): Removable storage detected" }
                    localOps { client ->
                        client.copy(sources, destination, onIssue, options).collect { emit(it) }
                    }
                    return@flow
                }

                val shouldTry = accessChecker.shouldTryNormalAccess(destination, forWriting = true)
                when {
                    shouldTry || (!hasAdb() && !hasRoot()) -> {
                        var hasEscalated = false
                        val escalationAwareOnIssue = createEscalationAwareOnIssue(
                            operationName = "copy()",
                            originalOnIssue = onIssue,
                            hasEscalatedRef = { hasEscalated },
                            markEscalated = { hasEscalated = true }
                        )

                        try {
                            log(TAG, VERBOSE) { "copy(AUTO->NORMAL, $shouldTry): To $destination" }
                            sources.copy(
                                fileSystemOps = fileSystemOps,
                                destination = destination,
                                options = options,
                                onIssue = escalationAwareOnIssue,
                            ).collect { state ->
                                emit(state)
                                if (state is CopyAction.State.Completed) {
                                    log(TAG, INFO) { "copy(): Finished, copied ${state.copied.size} items" }
                                }
                            }
                        } catch (e: Exception) {
                            log(TAG, VERBOSE) { "copy(AUTO->NORMAL): Error: ${e.message}" }
                            if (e.isPermissionError() && hasEscalated && (hasRoot() || hasAdb())) {
                                log(TAG, INFO) { "copy(AUTO->NORMAL->ROOT/ADB): Escalating after permission error" }
                                when {
                                    hasRoot() -> {
                                        log(TAG, VERBOSE) { "copy(AUTO->NORMAL->ROOT): To $destination" }
                                        rootOps { client ->
                                            client.copy(sources, destination, escalationAwareOnIssue, options)
                                                .collect { emit(it) }
                                        }
                                    }
                                    hasAdb() -> {
                                        log(TAG, VERBOSE) { "copy(AUTO->NORMAL->ADB): To $destination" }
                                        adbOps { client ->
                                            client.copy(sources, destination, escalationAwareOnIssue, options)
                                                .collect { emit(it) }
                                        }
                                    }
                                }
                            } else {
                                throw e
                            }
                        }
                    }
                    hasRoot() -> {
                        log(TAG, VERBOSE) { "copy(AUTO->ROOT): To $destination" }
                        rootOps { client ->
                            client.copy(sources, destination, onIssue, options).collect { emit(it) }
                        }
                    }
                    hasAdb() -> {
                        log(TAG, VERBOSE) { "copy(AUTO->ADB): To $destination" }
                        adbOps { client ->
                            client.copy(sources, destination, onIssue, options).collect { emit(it) }
                        }
                    }
                    else -> throw ElevatedAccessUnavailableException("No matching mode available.")
                }
            }
        }
    }.flowOn(dispatcherProvider.IO)

    override suspend fun move(
        sources: Set<LocalPath>,
        destination: LocalPath,
        onIssue: (suspend (PathActionIssue) -> PathActionIssue.Resolution)?,
        options: MoveAction.Options
    ): Flow<MoveAction.State<LocalPath, LocalPathLookup, LocalPath, LocalPathLookup>> =
        move(sources, destination, onIssue, options, Mode.AUTO)

    fun move(
        sources: Set<LocalPath>,
        destination: LocalPath,
        onIssue: (suspend (PathActionIssue) -> PathActionIssue.Resolution)?,
        options: MoveAction.Options,
        mode: Mode = Mode.AUTO,
    ): Flow<MoveAction.State<LocalPath, LocalPathLookup, LocalPath, LocalPathLookup>> = flow {
        log(TAG, VERBOSE) { "move(): ${sources.size} sources to $destination" }
        when (mode) {
            Mode.DIRECT -> {
                log(TAG, VERBOSE) { "move(DIRECT): To $destination" }
                sources.move(
                    fileSystemOps,
                    destination,
                    options,
                    onIssue = onIssue,
                ).collect { state ->
                    emit(state)
                    if (state is MoveAction.State.Completed<*, *, *, *>) {
                        log(TAG, INFO) { "move(): Finished, moved ${state.movedFiles.size} items" }
                    }
                }
            }

            Mode.ROOT -> {
                log(TAG, VERBOSE) { "move(ROOT): To $destination" }
                rootOps { client ->
                    client.move(
                        sources = sources,
                        destination = destination,
                        onIssue = onIssue,
                        options = options
                    ).collect { state ->
                        emit(state)
                        if (state is MoveAction.State.Completed<*, *, *, *>) {
                            log(TAG, INFO) { "move(): Finished, moved ${state.movedFiles.size} items" }
                        }
                    }
                }
            }

            Mode.ADB -> {
                log(TAG, VERBOSE) { "move(ADB): To $destination" }
                adbOps { client ->
                    client.move(
                        sources = sources,
                        destination = destination,
                        onIssue = onIssue,
                        options = options
                    ).collect { state ->
                        emit(state)
                        if (state is MoveAction.State.Completed<*, *, *, *>) {
                            log(TAG, INFO) { "move(): Finished, moved ${state.movedFiles.size} items" }
                        }
                    }
                }
            }

            Mode.LOCAL -> {
                log(TAG, VERBOSE) { "move(LOCAL): To $destination" }
                localOps { client ->
                    client.move(
                        sources = sources,
                        destination = destination,
                        onIssue = onIssue,
                        options = options
                    ).collect { state ->
                        emit(state)
                        if (state is MoveAction.State.Completed<*, *, *, *>) {
                            log(TAG, INFO) { "move(): Finished, moved ${state.movedFiles.size} items" }
                        }
                    }
                }
            }

            Mode.AUTO -> {
                // Use isolated process for removable storage to survive sudden disconnection
                val useIsolatedProcess = isOnRemovableStorage(destination) ||
                    sources.any { isOnRemovableStorage(it) }
                if (useIsolatedProcess && !hasRoot() && !hasAdb()) {
                    log(TAG, VERBOSE) { "move(AUTO->LOCAL_SERVICE): Removable storage detected" }
                    localOps { client ->
                        client.move(sources, destination, onIssue, options).collect { emit(it) }
                    }
                    return@flow
                }

                val shouldTry = accessChecker.shouldTryNormalAccess(destination, forWriting = true)
                when {
                    shouldTry || (!hasAdb() && !hasRoot()) -> {
                        var hasEscalated = false
                        val escalationAwareOnIssue = createEscalationAwareOnIssue(
                            operationName = "move()",
                            originalOnIssue = onIssue,
                            hasEscalatedRef = { hasEscalated },
                            markEscalated = { hasEscalated = true }
                        )

                        try {
                            log(TAG, VERBOSE) { "move(AUTO->NORMAL, $shouldTry): To $destination" }
                            sources.move(
                                fileSystemOps,
                                destination,
                                options,
                                onIssue = escalationAwareOnIssue,
                            ).collect { state ->
                                emit(state)
                                if (state is MoveAction.State.Completed<*, *, *, *>) {
                                    log(TAG, INFO) { "move(): Finished, moved ${state.movedFiles.size} items" }
                                }
                            }
                        } catch (e: Exception) {
                            log(TAG, VERBOSE) { "move(AUTO->NORMAL): Error: ${e.message}" }
                            if (e.isPermissionError() && hasEscalated && (hasRoot() || hasAdb())) {
                                log(TAG, INFO) { "move(AUTO->NORMAL->ROOT/ADB): Escalating after permission error" }
                                when {
                                    hasRoot() -> {
                                        log(TAG, VERBOSE) { "move(AUTO->NORMAL->ROOT): To $destination" }
                                        rootOps { client ->
                                            client.move(sources, destination, escalationAwareOnIssue, options)
                                                .collect { emit(it) }
                                        }
                                    }
                                    hasAdb() -> {
                                        log(TAG, VERBOSE) { "move(AUTO->NORMAL->ADB): To $destination" }
                                        adbOps { client ->
                                            client.move(sources, destination, escalationAwareOnIssue, options)
                                                .collect { emit(it) }
                                        }
                                    }
                                }
                            } else {
                                throw e
                            }
                        }
                    }
                    hasRoot() -> {
                        log(TAG, VERBOSE) { "move(AUTO->ROOT): To $destination" }
                        rootOps { client ->
                            client.move(sources, destination, onIssue, options).collect { emit(it) }
                        }
                    }
                    hasAdb() -> {
                        log(TAG, VERBOSE) { "move(AUTO->ADB): To $destination" }
                        adbOps { client ->
                            client.move(sources, destination, onIssue, options).collect { emit(it) }
                        }
                    }
                    else -> throw ElevatedAccessUnavailableException("No matching mode available.")
                }
            }
        }
    }.flowOn(dispatcherProvider.IO)

    override suspend fun create(
        target: LocalPath,
        type: CreateAction.CreateType,
        options: CreateAction.Options
    ): Flow<CreateAction.State<LocalPath, LocalPathLookup>> = create(target, type, options, Mode.AUTO)

    fun create(
        target: LocalPath,
        type: CreateAction.CreateType,
        options: CreateAction.Options,
        mode: Mode = Mode.AUTO,
    ): Flow<CreateAction.State<LocalPath, LocalPathLookup>> = flow {
        log(TAG, VERBOSE) { "create(): $target (type=$type)" }
        when (mode) {
            Mode.DIRECT -> {
                log(TAG, VERBOSE) { "create(DIRECT): $target" }
                target.createGeneric(
                    fileSystemOps = fileSystemOps,
                    type = type,
                    onIssue = options.onIssue,
                ).collect { state ->
                    emit(state)
                    if (state is CreateAction.State.Completed<*, *>) {
                        log(TAG, INFO) { "create(): Finished, created ${state.created}" }
                    }
                }
            }

            Mode.ROOT -> {
                log(TAG, VERBOSE) { "create(ROOT): $target (type=$type)" }
                rootOps { client ->
                    target.createGeneric(
                        fileSystemOps = client,
                        type = type,
                        onIssue = options.onIssue,
                    ).collect { state ->
                        emit(state)
                        if (state is CreateAction.State.Completed<*, *>) {
                            log(TAG, INFO) { "create(): Finished, created ${state.created}" }
                        }
                    }
                }
            }

            Mode.ADB -> {
                log(TAG, VERBOSE) { "create(ADB): $target (type=$type)" }
                adbOps { client ->
                    target.createGeneric(
                        fileSystemOps = client,
                        type = type,
                        onIssue = options.onIssue,
                    ).collect { state ->
                        emit(state)
                        if (state is CreateAction.State.Completed<*, *>) {
                            log(TAG, INFO) { "create(): Finished, created ${state.created}" }
                        }
                    }
                }
            }

            Mode.LOCAL -> {
                log(TAG, VERBOSE) { "create(LOCAL): $target (type=$type)" }
                localOps { client ->
                    target.createGeneric(
                        fileSystemOps = client,
                        type = type,
                        onIssue = options.onIssue,
                    ).collect { state ->
                        emit(state)
                        if (state is CreateAction.State.Completed<*, *>) {
                            log(TAG, INFO) { "create(): Finished, created ${state.created}" }
                        }
                    }
                }
            }

            Mode.AUTO -> {
                val parent = target.parent
                val shouldTry = if (parent != null) {
                    accessChecker.shouldTryNormalAccess(parent, forWriting = true)
                } else {
                    false
                }
                when {
                    shouldTry || (!hasAdb() && !hasRoot()) -> {
                        var hasEscalated = false
                        val escalationAwareOnIssue = createEscalationAwareOnIssue(
                            operationName = "create()",
                            originalOnIssue = options.onIssue,
                            hasEscalatedRef = { hasEscalated },
                            markEscalated = { hasEscalated = true }
                        )

                        try {
                            log(TAG, VERBOSE) { "create(AUTO->NORMAL, $shouldTry): $target" }
                            target.createGeneric(
                                fileSystemOps = fileSystemOps,
                                type = type,
                                onIssue = escalationAwareOnIssue,
                            ).collect { state ->
                                emit(state)
                                if (state is CreateAction.State.Completed<*, *>) {
                                    log(TAG, INFO) { "create(): Finished, created ${state.created}" }
                                }
                            }
                        } catch (e: Exception) {
                            log(TAG, VERBOSE) { "create(AUTO->NORMAL): Error: ${e.message}" }
                            if (e.isPermissionError() && hasEscalated && (hasRoot() || hasAdb())) {
                                log(TAG, INFO) { "create(AUTO->NORMAL->ROOT/ADB): Escalating after permission error" }
                                when {
                                    hasRoot() -> {
                                        log(TAG, VERBOSE) { "create(AUTO->NORMAL->ROOT): $target (type=$type)" }
                                        rootOps { client ->
                                            target.createGeneric(
                                                fileSystemOps = client,
                                                type = type,
                                                onIssue = escalationAwareOnIssue,
                                            ).collect { state ->
                                                emit(state)
                                                if (state is CreateAction.State.Completed<*, *>) {
                                                    log(TAG, INFO) { "create(): Finished, created ${state.created}" }
                                                }
                                            }
                                        }
                                    }
                                    hasAdb() -> {
                                        log(TAG, VERBOSE) { "create(AUTO->NORMAL->ADB): $target (type=$type)" }
                                        adbOps { client ->
                                            target.createGeneric(
                                                fileSystemOps = client,
                                                type = type,
                                                onIssue = escalationAwareOnIssue,
                                            ).collect { state ->
                                                emit(state)
                                                if (state is CreateAction.State.Completed<*, *>) {
                                                    log(TAG, INFO) { "create(): Finished, created ${state.created}" }
                                                }
                                            }
                                        }
                                    }
                                }
                            } else {
                                throw e
                            }
                        }
                    }
                    hasRoot() -> {
                        log(TAG, VERBOSE) { "create(AUTO->ROOT): $target (type=$type)" }
                        rootOps { client ->
                            target.createGeneric(
                                fileSystemOps = client,
                                type = type,
                                onIssue = options.onIssue,
                            ).collect { state ->
                                emit(state)
                                if (state is CreateAction.State.Completed<*, *>) {
                                    log(TAG, INFO) { "create(): Finished, created ${state.created}" }
                                }
                            }
                        }
                    }
                    hasAdb() -> {
                        log(TAG, VERBOSE) { "create(AUTO->ADB): $target (type=$type)" }
                        adbOps { client ->
                            target.createGeneric(
                                fileSystemOps = client,
                                type = type,
                                onIssue = options.onIssue,
                            ).collect { state ->
                                emit(state)
                                if (state is CreateAction.State.Completed<*, *>) {
                                    log(TAG, INFO) { "create(): Finished, created ${state.created}" }
                                }
                            }
                        }
                    }
                    else -> throw ElevatedAccessUnavailableException("No matching mode available.")
                }
            }
        }
    }.flowOn(dispatcherProvider.IO)

    private fun Throwable.isPermissionError(): Boolean =
        causeChain.any {
            it is PathException ||
                it is SecurityException ||
                it is java.nio.file.AccessDeniedException ||
                it is AccessDeniedException ||
                (it is IOException && it.message?.contains("permission", ignoreCase = true) == true)
        }

    private fun PathActionIssue.isPermissionIssue(): Boolean = when (this) {
        is PathActionIssue.InsufficientPermission -> true
        is PathActionIssue.UnknownError -> exception.isPermissionError()
        else -> false
    }

    /**
     * Creates an escalation-aware issue callback that automatically escalates to ROOT/ADB
     * on the first permission error, then delegates subsequent issues to the user.
     *
     * This wrapper tracks whether escalation has occurred and modifies behavior accordingly:
     * - First permission error: Throws exception to trigger auto-escalation to ROOT/ADB
     * - Subsequent errors: Delegates to the original callback for user decision
     *
     * @param operationName Name of the operation for logging (e.g., "delete()", "copy()")
     * @param originalOnIssue The original issue callback to delegate to after escalation
     * @param hasEscalatedRef Function to check if escalation has occurred
     * @param markEscalated Function to mark that escalation has occurred
     * @return Wrapped callback with escalation logic
     */
    private inline fun createEscalationAwareOnIssue(
        operationName: String,
        noinline originalOnIssue: (suspend (PathActionIssue) -> PathActionIssue.Resolution)?,
        crossinline hasEscalatedRef: () -> Boolean,
        crossinline markEscalated: () -> Unit
    ): suspend (PathActionIssue) -> PathActionIssue.Resolution = { issue ->
        when {
            !hasEscalatedRef() && issue.isPermissionIssue() -> {
                log(TAG, INFO) { "$operationName: Permission error, escalating" }
                markEscalated()
                throw when (issue) {
                    is PathActionIssue.UnknownError -> issue.exception
                    is PathActionIssue.InsufficientPermission ->
                        issue.exception ?: SecurityException("Permission denied: ${issue.destinationPath}")
                    else -> IllegalStateException("Unexpected permission issue type")
                }
            }
            else -> {
                if (hasEscalatedRef()) {
                    log(TAG, WARN) { "$operationName: Error persists after escalation, delegating to user" }
                }
                originalOnIssue?.invoke(issue) ?: throw IllegalStateException("No issue handler")
            }
        }
    }

    enum class Mode {
        AUTO, DIRECT, ROOT, ADB, LOCAL
    }

    companion object {
        val TAG = logTag("Gateway", "Local")
    }
}