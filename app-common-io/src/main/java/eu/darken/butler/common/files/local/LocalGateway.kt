package eu.darken.butler.common.files.local

import eu.darken.butler.common.adb.AdbManager
import eu.darken.butler.common.adb.AdbUnavailableException
import eu.darken.butler.common.adb.canUseAdbNow
import eu.darken.butler.common.adb.service.runModuleAction
import eu.darken.butler.common.coroutine.AppScope
import eu.darken.butler.common.coroutine.DispatcherProvider
import eu.darken.butler.common.debug.logging.Logging.Priority.*
import eu.darken.butler.common.debug.logging.asLog
import eu.darken.butler.common.debug.logging.log
import eu.darken.butler.common.debug.logging.logTag
import eu.darken.butler.common.error.causeChain
import eu.darken.butler.common.files.APathGateway
import eu.darken.butler.common.files.Existence
import eu.darken.butler.common.files.FileSystemOps
import eu.darken.butler.common.files.LocalPath
import eu.darken.butler.common.files.LookupOptions
import eu.darken.butler.common.files.MoveOutcome
import eu.darken.butler.common.files.actions.CopyAction
import eu.darken.butler.common.files.actions.CreateAction
import eu.darken.butler.common.files.actions.DeleteAction
import eu.darken.butler.common.files.actions.MoveAction
import eu.darken.butler.common.files.actions.PathActionIssue
import eu.darken.butler.common.files.errors.PathException
import eu.darken.butler.common.files.errors.PathPermissionDeniedException
import eu.darken.butler.common.files.errors.PathPermissionDeniedException.Reason
import eu.darken.butler.common.files.errors.ServiceConnectionLostException
import eu.darken.butler.common.files.permissions.PermissionErrorClassifier
import eu.darken.butler.common.files.io.callbacks
import eu.darken.butler.common.files.local.accessibility.LocalPathAccessChecker
import eu.darken.butler.common.files.local.ipc.FileOpsClient
import eu.darken.butler.common.files.local.operations.strategies.LocalPathCopyStrategy
import eu.darken.butler.common.files.local.operations.strategies.LocalPathMoveStrategy
import eu.darken.butler.common.files.local.routing.AccessIntent
import eu.darken.butler.common.files.local.routing.CapabilitySnapshot
import eu.darken.butler.common.files.local.routing.ModeSessionFactory
import eu.darken.butler.common.files.local.routing.ModeSessionRegistry
import eu.darken.butler.common.files.local.routing.LocalPathRoutingPolicy
import eu.darken.butler.common.files.local.routing.RoutedLocalFileSystemOps
import eu.darken.butler.common.files.local.routing.RouteUnavailableException
import eu.darken.butler.common.files.local.routing.StaticLocalRouteRouter
import eu.darken.butler.common.files.local.service.IsolatedServiceClient
import eu.darken.butler.common.files.local.service.IsolatedServiceClient.*
import eu.darken.butler.common.files.local.service.runModuleAction
import eu.darken.butler.common.files.local.walkers.DirectLocalWalker
import eu.darken.butler.common.files.local.walkers.IndirectLocalWalker
import eu.darken.butler.common.files.local.walkers.RoutedLocalWalker
import eu.darken.butler.common.files.metadata.FileSystem
import eu.darken.butler.common.files.metadata.Ownership
import eu.darken.butler.common.files.metadata.Permissions
import eu.darken.butler.common.files.operations.createGeneric
import eu.darken.butler.common.files.operations.copyGeneric
import eu.darken.butler.common.files.operations.deleteGeneric
import eu.darken.butler.common.files.operations.moveGeneric
import eu.darken.butler.common.files.operations.TransferStrategy
import eu.darken.butler.common.root.RootManager
import eu.darken.butler.common.root.RootUnavailableException
import eu.darken.butler.common.root.canUseRootNow
import eu.darken.butler.common.root.service.runModuleAction
import eu.darken.butler.common.sharedresource.SharedResource
import eu.darken.butler.common.sharedresource.keepResourcesAlive
import eu.darken.butler.common.storage.StorageManager2
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.catch
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
    private val isolatedServiceClient: IsolatedServiceClient,
    private val storageManager: StorageManager2,
    private val routingPolicy: LocalPathRoutingPolicy,
    private val modeSessionFactory: ModeSessionFactory,
) : APathGateway<LocalPath, LocalPathLookup> {

    // Represents the resource that keeps the gateway resources alive
    // Internal resources should add themselfes as child to this
    override val sharedResource = SharedResource.createKeepAlive(TAG, appScope + dispatcherProvider.IO)

    private suspend fun <T> isolatedOps(action: suspend (FileOpsClient) -> T): T {
        return try {
            keepResourcesAlive(isolatedServiceClient) {
                isolatedServiceClient.runModuleAction(FileOpsClient::class.java) { action(it) }
            }
        } catch (e: ServiceProcessDiedException) {
            throw ServiceConnectionLostException(cause = e)
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

    private suspend fun <T> clientOps(mode: Mode, action: suspend (FileOpsClient) -> T): T = when (mode) {
        Mode.ISOLATED -> isolatedOps(action)
        Mode.ROOT -> rootOps(action)
        Mode.ADB -> adbOps(action)
        Mode.DIRECT, Mode.AUTO -> error("clientOps does not support mode $mode")
    }

    private suspend fun selectEscalationModeOrNull(): Mode? = when {
        hasRoot() -> Mode.ROOT
        hasAdb() -> Mode.ADB
        else -> null
    }

    private suspend fun <T> withRouting(action: suspend (StaticLocalRouteRouter) -> T): T {
        val registry = ModeSessionRegistry(modeSessionFactory)
        return try {
            val router = StaticLocalRouteRouter(
                policy = routingPolicy,
                caps = CapabilitySnapshot(
                    rootProvider = { hasRoot() },
                    adbProvider = { hasAdb() },
                ),
                sessions = registry,
            )
            action(router)
        } finally {
            registry.close()
        }
    }

    // The service resource must stay alive until the returned Flow completes
    private suspend fun walkViaIpc(
        mode: Mode,
        path: LocalPath,
        lookupOptions: LookupOptions,
        walkOptions: APathGateway.WalkOptions<LocalPath, LocalPathLookup>,
    ): Flow<LocalPathLookup> = when (mode) {
        Mode.ISOLATED -> {
            val resource = isolatedServiceClient.get()
            try {
                isolatedServiceClient.runModuleAction(FileOpsClient::class.java) {
                    it.walk(path, lookupOptions, walkOptions).onCompletion { resource.close() }
                }
            } catch (e: Throwable) {
                resource.close()
                throw e
            }
        }
        Mode.ROOT -> {
            val resource = rootManager.serviceClient.get()
            try {
                rootManager.serviceClient.runModuleAction(FileOpsClient::class.java) {
                    it.walk(path, lookupOptions, walkOptions).onCompletion { resource.close() }
                }
            } catch (e: Throwable) {
                resource.close()
                throw e
            }
        }
        Mode.ADB -> {
            val resource = adbManager.serviceClient.get()
            try {
                adbManager.serviceClient.runModuleAction(FileOpsClient::class.java) {
                    it.walk(path, lookupOptions, walkOptions).onCompletion { resource.close() }
                }
            } catch (e: Throwable) {
                resource.close()
                throw e
            }
        }
        Mode.DIRECT, Mode.AUTO -> error("walkViaIpc does not support mode $mode")
    }

    // The service resource must stay alive until the caller closes the FileHandle
    private suspend fun fileViaIpc(mode: Mode, path: LocalPath, readWrite: Boolean): FileHandle = when (mode) {
        Mode.ISOLATED -> isolatedOps { client ->
            val resource = isolatedServiceClient.get()
            try {
                client.file(path, readWrite).callbacks {
                    resource.close()
                    log(TAG, VERBOSE) { "file(ISOLATED, RW=$readWrite): Closing resource for $path" }
                }
            } catch (e: Throwable) {
                resource.close()
                throw e
            }
        }
        Mode.ROOT -> rootOps { client ->
            val resource = rootManager.serviceClient.get()
            try {
                client.file(path, readWrite).callbacks {
                    resource.close()
                    log(TAG, VERBOSE) { "file(ROOT, RW=$readWrite): Closing resource for $path" }
                }
            } catch (e: Throwable) {
                resource.close()
                throw e
            }
        }
        Mode.ADB -> adbOps { client ->
            val resource = adbManager.serviceClient.get()
            try {
                client.file(path, readWrite).callbacks {
                    resource.close()
                    log(TAG, VERBOSE) { "file(ADB, RW=$readWrite): Closing resource for $path" }
                }
            } catch (e: Throwable) {
                resource.close()
                throw e
            }
        }
        Mode.DIRECT, Mode.AUTO -> error("fileViaIpc does not support mode $mode")
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

    private fun mapPermissionError(
        path: LocalPath,
        operation: String,
        error: Throwable,
    ): Throwable {
        if (error is PathPermissionDeniedException) return error

        val routeUnavailable = error.causeChain.filterIsInstance<RouteUnavailableException>().firstOrNull()
        val reason = PermissionErrorClassifier.classify(error)
            ?: routeUnavailable?.let { Reason.NO_MECHANISM }
            ?: return error

        val specificPath = routeUnavailable?.path
            ?: error.causeChain.filterIsInstance<PathException>().firstOrNull()?.path as? LocalPath

        return PathPermissionDeniedException(
            path = specificPath ?: path,
            operation = operation,
            reason = reason,
            cause = error,
        )
    }

    private suspend fun <T> mappingPermissions(
        path: LocalPath,
        operation: String,
        block: suspend () -> T,
    ): T = try {
        block()
    } catch (e: Exception) {
        throw mapPermissionError(path, operation, e)
    }

    /**
     * Executes a file operation with automatic mode selection and escalation.
     *
     * For Mode.AUTO: Tries normal mode first (ensures correct ownership), escalates to root/ADB on IOException.
     * For explicit modes: Executes directly without fallback.
     *
     * @param mode The requested execution mode
     * @param operation Operation name for logging (e.g., "createDir")
     * @param path Path for logging and access checks
     * @param directOp Direct mode operation (no IPC)
     * @param modeOp Per-mode operation for ISOLATED/ROOT/ADB
     * @return Result of the operation
     */
    private suspend fun <T> executeWithMode(
        mode: Mode,
        operation: String,
        path: LocalPath,
        forWriting: Boolean,
        directOp: suspend () -> T,
        modeOp: suspend (Mode) -> T,
    ): T = runIO {
        mappingPermissions(path, operation) {
            when (mode) {
                Mode.DIRECT -> {
                    log(TAG, VERBOSE) { "$operation(DIRECT) -> $path" }
                    directOp()
                }
                Mode.ISOLATED, Mode.ROOT, Mode.ADB -> {
                    log(TAG, VERBOSE) { "$operation($mode) -> $path" }
                    modeOp(mode)
                }
                Mode.AUTO -> {
                    // For removable storage, prefer ISOLATED mode to protect against sudden disconnect
                    if (isOnRemovableStorage(path)) {
                        try {
                            log(TAG, VERBOSE) { "$operation(AUTO:ISOLATED) -> $path [removable storage]" }
                            return@mappingPermissions modeOp(Mode.ISOLATED)
                        } catch (e: ServiceBindException) {
                            // ISOLATED failed - fall back to DIRECT (same privilege level, no crash isolation)
                            log(TAG, WARN) { "$operation: IsolatedService unavailable for removable storage, falling back to DIRECT" }
                            return@mappingPermissions directOp().also {
                                log(TAG, VERBOSE) { "$operation(AUTO:DIRECT) -> $path [ISOLATED fallback]" }
                            }
                        }
                    }

                    // Standard handling for internal storage
                    suspend fun escalation(): T = when (val escMode = selectEscalationModeOrNull()) {
                        null -> throw PathPermissionDeniedException(path, operation, Reason.NO_MECHANISM)
                        else -> {
                            log(TAG, VERBOSE) { "$operation(AUTO:$escMode) -> $path" }
                            modeOp(escMode)
                        }
                    }
                    if (accessChecker.shouldTryNormalAccess(path, forWriting)) {
                        try {
                            directOp().also { log(TAG, VERBOSE) { "$operation(AUTO:DIRECT) -> $path" } }
                        } catch (e: IOException) {
                            log(TAG, VERBOSE) { "$operation(AUTO) failed: ${e.message}" }
                            try {
                                escalation()
                            } catch (escErr: PathPermissionDeniedException) {
                                if (escErr.reason == Reason.NO_MECHANISM) throw e else throw escErr
                            }
                        }
                    } else {
                        escalation()
                    }
                }
            }
        }
    }

    private suspend fun <T> executeWithModeSelection(
        mode: Mode,
        operation: String,
        path: LocalPath,
        forWriting: Boolean,
        directOp: suspend () -> T,
        clientOp: suspend (FileOpsClient) -> T,
    ): T = executeWithMode(
        mode = mode,
        operation = operation,
        path = path,
        forWriting = forWriting,
        directOp = directOp,
        modeOp = { m -> clientOps(m) { clientOp(it) } },
    )

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
        directOp = { fileSystemOps.createDir(path, createParents) },
        clientOp = { it.createDir(path, createParents) },
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
        directOp = { fileSystemOps.createFile(path, createParents) },
        clientOp = { it.createFile(path, createParents) },
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
        directOp = { fileSystemOps.createSymlink(linkPath, targetPath) },
        clientOp = { it.createSymlink(linkPath, targetPath) },
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
        directOp = { fileSystemOps.readSymbolicLink(linkPath) },
        clientOp = { it.readSymbolicLink(linkPath) },
    )

    override suspend fun canonicalize(path: LocalPath): LocalPath =
        canonicalize(path, Mode.AUTO)

    suspend fun canonicalize(
        path: LocalPath,
        mode: Mode = Mode.AUTO
    ): LocalPath = executeWithModeSelection(
        mode = mode,
        operation = "canonicalize",
        path = path,
        forWriting = false,
        directOp = { fileSystemOps.canonicalize(path) },
        clientOp = { it.canonicalize(path) },
    )

    override suspend fun move(source: LocalPath, destination: LocalPath): MoveOutcome =
        move(source, destination, Mode.AUTO)

    suspend fun move(
        source: LocalPath,
        destination: LocalPath,
        mode: Mode = Mode.AUTO
    ): MoveOutcome = executeWithModeSelection(
        mode = mode,
        operation = "move",
        path = source,
        forWriting = true,
        directOp = { fileSystemOps.move(source, destination) },
        clientOp = { it.move(source, destination) },
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
        directOp = { fileSystemOps.lookup(path, options) },
        clientOp = { it.lookup(path, options) },
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
        directOp = { fileSystemOps.listFiles(path) },
        clientOp = { it.listFiles(path) },
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
        directOp = { fileSystemOps.lookupFiles(path, options) },
        clientOp = { it.lookupFiles(path, options) },
    )


    override suspend fun walk(
        path: LocalPath,
        lookupOptions: LookupOptions,
        walkOptions: APathGateway.WalkOptions<LocalPath, LocalPathLookup>,
    ): Flow<LocalPathLookup> = walk(path, lookupOptions, walkOptions, Mode.AUTO)

    /**
     * Recursively walks the directory tree starting at [path].
     *
     * **Important:** The returned Flow MUST be collected (or cancelled) to ensure proper resource cleanup.
     * For IPC-based modes (ISOLATED, ROOT, ADB), service connections are held open until the flow completes.
     * Failure to collect the flow will leak service resources.
     */
    suspend fun walk(
        path: LocalPath,
        lookupOptions: LookupOptions,
        walkOptions: APathGateway.WalkOptions<LocalPath, LocalPathLookup>,
        mode: Mode = Mode.AUTO,
    ): Flow<LocalPathLookup> {
        fun directWalker(): Flow<LocalPathLookup> = DirectLocalWalker(
            fileSystemOps = fileSystemOps,
            lookupOptions = lookupOptions,
            start = path,
            onFilter = { lookup -> walkOptions.onFilter?.invoke(lookup) ?: true },
            onError = { lookup, exception -> walkOptions.onError?.invoke(lookup, exception) ?: true },
            followSymlinks = walkOptions.followSymlinks,
        )

        // Routes each subtree to the mode the policy assigns it, escalating at boundaries and on
        // runtime failures — a walk over mixed-access trees no longer silently skips subtrees.
        fun routedWalker(): Flow<LocalPathLookup> = RoutedLocalWalker(
            routingPolicy = routingPolicy,
            sessionFactory = modeSessionFactory,
            caps = CapabilitySnapshot(
                rootProvider = { hasRoot() },
                adbProvider = { hasAdb() },
            ),
            start = path,
            lookupOptions = lookupOptions,
            pathDoesNotContain = walkOptions.pathDoesNotContain,
            onFilter = { lookup -> walkOptions.onFilter?.invoke(lookup) ?: true },
            onError = { lookup, exception -> walkOptions.onError?.invoke(lookup, exception) ?: true },
            followSymlinks = walkOptions.followSymlinks,
            streamingEligible = walkOptions.isStreamable,
        )

        // Can't pass functions via IPC
        fun indirectWalker(walkMode: Mode): Flow<LocalPathLookup> = IndirectLocalWalker(
            gateway = this,
            mode = walkMode,
            start = path,
            lookupOptions = lookupOptions,
            onFilter = { lookup -> walkOptions.onFilter?.invoke(lookup) ?: true },
            onError = { lookup, exception -> walkOptions.onError?.invoke(lookup, exception) ?: true },
            followSymlinks = walkOptions.followSymlinks,
        )

        suspend fun walkVia(walkMode: Mode): Flow<LocalPathLookup> = if (walkOptions.isStreamable) {
            log(TAG, VERBOSE) { "walk($walkMode, direct): $path" }
            walkViaIpc(walkMode, path, lookupOptions, walkOptions)
        } else {
            log(TAG, VERBOSE) { "walk($walkMode, indirect): $path" }
            indirectWalker(walkMode)
        }

        if (mode == Mode.AUTO) {
            log(TAG, VERBOSE) { "walk(AUTO, routed): $path" }
            return routedWalker().catch { throw mapPermissionError(path, "walk", it) }
        }

        return executeWithMode(
            mode = mode,
            operation = "walk",
            path = path,
            forWriting = false,
            directOp = { directWalker() },
            modeOp = { walkVia(it) },
        ).catch { throw mapPermissionError(path, "walk", it) }
    }

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
        directOp = { fileSystemOps.du(path) },
        clientOp = { it.du(path) },
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
        directOp = { fileSystemOps.exists(path) },
        clientOp = { it.exists(path) },
    )

    override suspend fun existsStrict(path: LocalPath): Existence = existsStrict(path, Mode.AUTO)

    /**
     * Existence keeping "not there" apart from "could not look", so it cannot ride on
     * [executeWithModeSelection]: that escalates on a thrown [IOException], and an UNKNOWN answer
     * is a return value, not a throw. Escalation happens on UNKNOWN here instead, and a mode that
     * cannot be reached at all is UNKNOWN rather than an error.
     */
    suspend fun existsStrict(
        path: LocalPath,
        mode: Mode = Mode.AUTO
    ): Existence = runIO {
        suspend fun direct(): Existence = probeExistence("existsStrict(DIRECT) -> $path") {
            fileSystemOps.existsStrict(path)
        }

        suspend fun viaClient(clientMode: Mode): Existence =
            probeExistence("existsStrict($clientMode) -> $path") {
                clientOps(clientMode) { it.existsStrict(path) }
            }

        when (mode) {
            Mode.DIRECT -> direct()
            Mode.ISOLATED, Mode.ROOT, Mode.ADB -> viaClient(mode)
            Mode.AUTO -> {
                // For removable storage, prefer ISOLATED mode to protect against sudden disconnect
                if (isOnRemovableStorage(path)) {
                    return@runIO try {
                        clientOps(Mode.ISOLATED) { it.existsStrict(path) }
                    } catch (e: CancellationException) {
                        throw e
                    } catch (e: ServiceBindException) {
                        log(TAG, WARN) {
                            "existsStrict: IsolatedService unavailable for removable storage, falling back to DIRECT"
                        }
                        direct()
                    } catch (e: Exception) {
                        log(TAG, WARN) { "existsStrict(AUTO:ISOLATED) -> $path failed: ${e.asLog()}" }
                        Existence.UNKNOWN
                    }
                }

                val directAnswer = when {
                    accessChecker.shouldTryNormalAccess(path, false) -> direct()
                    else -> Existence.UNKNOWN
                }
                if (directAnswer != Existence.UNKNOWN) return@runIO directAnswer

                when (val escMode = selectEscalationModeOrNull()) {
                    null -> Existence.UNKNOWN
                    else -> viaClient(escMode)
                }
            }
        }
    }

    private suspend fun probeExistence(label: String, block: suspend () -> Existence): Existence = try {
        block()
    } catch (e: CancellationException) {
        throw e
    } catch (e: Exception) {
        log(TAG, WARN) { "$label failed: ${e.asLog()}" }
        Existence.UNKNOWN
    }

    override suspend fun canWrite(path: LocalPath): Boolean = canWrite(path, Mode.AUTO)

    suspend fun canWrite(
        path: LocalPath,
        mode: Mode = Mode.AUTO
    ): Boolean = executeWithModeSelection(
        mode = mode,
        operation = "canWrite",
        path = path,
        forWriting = false,
        directOp = { fileSystemOps.canWrite(path) },
        clientOp = { it.canWrite(path) },
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
        directOp = { fileSystemOps.canRead(path) },
        clientOp = { it.canRead(path) },
    )

    override suspend fun file(path: LocalPath, readWrite: Boolean): FileHandle = file(path, readWrite, Mode.AUTO)

    suspend fun file(
        path: LocalPath,
        readWrite: Boolean,
        mode: Mode = Mode.AUTO
    ): FileHandle = executeWithMode(
        mode = mode,
        operation = "file",
        path = path,
        forWriting = readWrite,
        directOp = { fileSystemOps.file(path, readWrite) },
        modeOp = { fileViaIpc(it, path, readWrite) },
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
        directOp = { fileSystemOps.openInputStream(path) },
        clientOp = { it.openInputStream(path) },
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
        directOp = { fileSystemOps.openOutputStream(path, append) },
        clientOp = { it.openOutputStream(path, append) },
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
        directOp = { fileSystemOps.setModifiedAt(path, modifiedAt) },
        clientOp = { it.setModifiedAt(path, modifiedAt) },
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
        directOp = { fileSystemOps.setPermissions(path, permissions) },
        clientOp = { it.setPermissions(path, permissions) },
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
        directOp = { fileSystemOps.setOwnership(path, ownership) },
        clientOp = { it.setOwnership(path, ownership) },
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
        directOp = { fileSystemOps.getFileSystem(path) },
        clientOp = { it.getFileSystem(path) },
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
        directOp = {
            fileSystemOps.delete(path, recursive)
            true
        },
        clientOp = { it.delete(path, recursive) },
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

        suspend fun relay(states: Flow<DeleteAction.State<LocalPath, LocalPathLookup>>) = states.collect { state ->
            emit(state)
            if (state is DeleteAction.State.Completed) {
                log(TAG, INFO) { "delete(): Finished, deleted ${state.deleted.size} items" }
            }
        }

        when (mode) {
            Mode.DIRECT -> {
                log(TAG, VERBOSE) { "delete(DIRECT): ${targets.size} targets" }
                relay(
                    targets.delete(
                        fileSystemOps,
                        recursive = options.recursive,
                        ignoreMissing = options.ignoreMissing,
                        onIssue = options.onIssue,
                    )
                )
            }

            Mode.ISOLATED, Mode.ROOT, Mode.ADB -> {
                log(TAG, VERBOSE) { "delete($mode): ${targets.size} targets" }
                clientOps(mode) { client ->
                    relay(client.delete(targets = targets, options = options))
                }
            }

            Mode.AUTO -> {
                log(TAG, VERBOSE) { "delete(AUTO:routed): ${targets.size} targets" }
                withRouting { router ->
                    val routedOps = RoutedLocalFileSystemOps(router, AccessIntent.Delete)

                    targets.forEach { routedOps.ensurePlanned(it, AccessIntent.Delete) }

                    relay(
                        targets.deleteGeneric(
                            fileSystemOps = routedOps,
                            recursive = options.recursive,
                            ignoreMissing = options.ignoreMissing,
                            onIssue = options.onIssue,
                        )
                    )
                }
            }
        }
    }
        .catch { e ->
            val fallback = targets.firstOrNull() ?: throw e
            throw mapPermissionError(fallback, "delete", e)
        }
        .flowOn(dispatcherProvider.IO)

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

        suspend fun relay(
            states: Flow<CopyAction.State<LocalPath, LocalPathLookup, LocalPath, LocalPathLookup>>,
        ) = states.collect { state ->
            emit(state)
            if (state is CopyAction.State.Completed) {
                log(TAG, INFO) { "copy(): Finished, copied ${state.copied.size} items" }
            }
        }

        when (mode) {
            Mode.DIRECT -> {
                log(TAG, VERBOSE) { "copy(DIRECT): To $destination" }
                relay(
                    sources.copy(
                        fileSystemOps = fileSystemOps,
                        destination = destination,
                        options = options,
                        onIssue = onIssue,
                    )
                )
            }

            Mode.ISOLATED, Mode.ROOT, Mode.ADB -> {
                log(TAG, VERBOSE) { "copy($mode): To $destination" }
                clientOps(mode) { client ->
                    relay(
                        client.copy(
                            sources = sources,
                            destination = destination,
                            onIssue = onIssue,
                            options = options
                        )
                    )
                }
            }

            Mode.AUTO -> {
                log(TAG, VERBOSE) { "copy(AUTO:routed): To $destination" }
                withRouting { router ->
                    val sourceOps = RoutedLocalFileSystemOps(router, AccessIntent.Read)
                    val destOps = RoutedLocalFileSystemOps(router, AccessIntent.Write)

                    sources.forEach { sourceOps.ensurePlanned(it, AccessIntent.Read) }
                    destOps.ensurePlanned(destination, AccessIntent.Write)

                    val transferOptions = TransferStrategy.Options(
                        preserveAttributes = options.preserveAttributes,
                        followSymlinks = options.followSymlinks,
                        overwrite = options.overwrite,
                    )

                    relay(
                        sources.copyGeneric(
                            destination = destination,
                            sourceOps = sourceOps,
                            destOps = destOps,
                            options = transferOptions,
                            strategy = LocalPathCopyStrategy(fileSystemOps),
                            onIssue = onIssue,
                        )
                    )
                }
            }
        }
    }
        .catch { throw mapPermissionError(destination, "copy", it) }
        .flowOn(dispatcherProvider.IO)

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

        suspend fun relay(
            states: Flow<MoveAction.State<LocalPath, LocalPathLookup, LocalPath, LocalPathLookup>>,
        ) = states.collect { state ->
            emit(state)
            if (state is MoveAction.State.Completed<*, *, *, *>) {
                log(TAG, INFO) { "move(): Finished, moved ${state.movedFiles.size} items" }
            }
        }

        when (mode) {
            Mode.DIRECT -> {
                log(TAG, VERBOSE) { "move(DIRECT): To $destination" }
                relay(
                    sources.move(
                        fileSystemOps,
                        destination,
                        options,
                        onIssue = onIssue,
                    )
                )
            }

            Mode.ISOLATED, Mode.ROOT, Mode.ADB -> {
                log(TAG, VERBOSE) { "move($mode): To $destination" }
                clientOps(mode) { client ->
                    relay(
                        client.move(
                            sources = sources,
                            destination = destination,
                            onIssue = onIssue,
                            options = options
                        )
                    )
                }
            }

            Mode.AUTO -> {
                log(TAG, VERBOSE) { "move(AUTO:routed): To $destination" }
                withRouting { router ->
                    val sourceOps = RoutedLocalFileSystemOps(router, AccessIntent.Delete)
                    val destOps = RoutedLocalFileSystemOps(router, AccessIntent.Write)

                    sources.forEach { sourceOps.ensurePlanned(it, AccessIntent.Delete) }
                    destOps.ensurePlanned(destination, AccessIntent.Write)

                    val transferOptions = TransferStrategy.Options(
                        preserveAttributes = options.preserveAttributes,
                        followSymlinks = false,
                        overwrite = options.overwrite,
                        attemptAtomicMove = false,
                    )

                    relay(
                        sources.moveGeneric(
                            destination = destination,
                            sourceOps = sourceOps,
                            destOps = destOps,
                            strategy = LocalPathMoveStrategy(fileSystemOps),
                            options = transferOptions,
                            onIssue = onIssue,
                        )
                    )
                }
            }
        }
    }
        .catch { throw mapPermissionError(destination, "move", it) }
        .flowOn(dispatcherProvider.IO)

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

        suspend fun relayCreate(
            ops: FileSystemOps<LocalPath, LocalPathLookup>,
            onIssue: (suspend (PathActionIssue) -> PathActionIssue.Resolution)?,
        ) = target.createGeneric(
            fileSystemOps = ops,
            type = type,
            onIssue = onIssue,
        ).collect { state ->
            emit(state)
            if (state is CreateAction.State.Completed<*, *>) {
                log(TAG, INFO) { "create(): Finished, created ${state.created}" }
            }
        }

        when (mode) {
            Mode.DIRECT -> {
                log(TAG, VERBOSE) { "create(DIRECT): $target" }
                relayCreate(fileSystemOps, options.onIssue)
            }

            Mode.ISOLATED, Mode.ROOT, Mode.ADB -> {
                log(TAG, VERBOSE) { "create($mode): $target (type=$type)" }
                clientOps(mode) { relayCreate(it, options.onIssue) }
            }

            Mode.AUTO -> {
                // Use isolated process for removable storage to survive sudden disconnection
                if (isOnRemovableStorage(target)) {
                    try {
                        log(TAG, VERBOSE) { "create(AUTO:ISOLATED): $target [removable storage]" }
                        isolatedOps { relayCreate(it, options.onIssue) }
                        return@flow
                    } catch (e: ServiceBindException) {
                        log(TAG, WARN) { "create: IsolatedService unavailable for removable storage, falling back to DIRECT" }
                        relayCreate(fileSystemOps, options.onIssue)
                        log(TAG, VERBOSE) { "create(AUTO:DIRECT) [ISOLATED fallback]" }
                        return@flow
                    }
                }

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
                            relayCreate(fileSystemOps, escalationAwareOnIssue)
                        } catch (e: Exception) {
                            log(TAG, VERBOSE) { "create(AUTO->NORMAL): Error: ${e.message}" }
                            val escMode = when {
                                PermissionErrorClassifier.isPermissionError(e) && hasEscalated -> selectEscalationModeOrNull()
                                else -> null
                            }
                            // No escalation possible: surface the original error, never NO_MECHANISM
                            if (escMode == null) throw e
                            log(TAG, INFO) { "create(AUTO->NORMAL->ROOT/ADB): Escalating after permission error" }
                            log(TAG, VERBOSE) { "create(AUTO->NORMAL->$escMode): $target (type=$type)" }
                            clientOps(escMode) { relayCreate(it, escalationAwareOnIssue) }
                        }
                    }
                    else -> {
                        val escMode = selectEscalationModeOrNull()
                            ?: throw PathPermissionDeniedException(target, "create", Reason.NO_MECHANISM)
                        log(TAG, VERBOSE) { "create(AUTO->$escMode): $target (type=$type)" }
                        clientOps(escMode) { relayCreate(it, options.onIssue) }
                    }
                }
            }
        }
    }
        .catch { throw mapPermissionError(target, "create", it) }
        .flowOn(dispatcherProvider.IO)

    private fun PathActionIssue.isPermissionIssue(): Boolean = when (this) {
        is PathActionIssue.InsufficientPermission -> true
        is PathActionIssue.UnknownError -> PermissionErrorClassifier.isPermissionError(exception)
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
        AUTO, DIRECT, ISOLATED, ROOT, ADB
    }

    companion object {
        val TAG = logTag("Gateway", "Local")
    }
}
