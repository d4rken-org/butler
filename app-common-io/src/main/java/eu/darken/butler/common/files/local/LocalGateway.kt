package eu.darken.butler.common.files.local

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
import eu.darken.butler.common.files.core.local.isReadable
import eu.darken.butler.common.files.core.local.listFiles2
import eu.darken.butler.common.files.core.local.parentsInclusive
import eu.darken.butler.common.files.errors.ReadException
import eu.darken.butler.common.files.extensions.toFile
import eu.darken.butler.common.files.io.callbacks
import eu.darken.butler.common.files.local.accessibility.LocalPathAccessibilityChecker
import eu.darken.butler.common.files.local.ipc.FileOpsClient
import eu.darken.butler.common.files.local.walkers.DirectLocalWalker
import eu.darken.butler.common.files.local.walkers.EscalatingWalker
import eu.darken.butler.common.files.local.walkers.IndirectLocalWalker
import eu.darken.butler.common.files.metadata.FileSystemInfo
import eu.darken.butler.common.files.metadata.Ownership
import eu.darken.butler.common.files.metadata.Permissions
import eu.darken.butler.common.hasApiLevel
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
    private val accessibilityChecker: LocalPathAccessibilityChecker,
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
        path: LocalPath? = null,
        normalOp: suspend () -> T,
        rootOp: suspend (FileOpsClient) -> T,
        adbOp: suspend (FileOpsClient) -> T
    ): T {
        val pathStr = path?.let { ": $it" } ?: ""

        return when (mode) {
            Mode.NORMAL -> {
                log(TAG, VERBOSE) { "$operation(NORMAL)$pathStr" }
                normalOp()
            }
            Mode.ROOT -> {
                log(TAG, VERBOSE) { "$operation(ROOT)$pathStr" }
                rootOps { rootOp(it) }
            }
            Mode.ADB -> {
                log(TAG, VERBOSE) { "$operation(ADB)$pathStr" }
                adbOps { adbOp(it) }
            }
            Mode.AUTO -> {
                // Optimization: skip normal attempt for definitely inaccessible paths
                if (path != null && accessibilityChecker.isDefinitelyInaccessible(path, forWriting = true) && hasRoot()) {
                    log(TAG, VERBOSE) { "$operation(AUTO->ROOT, inaccessible)$pathStr" }
                    rootOps { rootOp(it) }
                } else {
                    // Default: try normal first (critical for correct ownership)
                    try {
                        normalOp().also {
                            log(TAG, VERBOSE) { "$operation(AUTO->NORMAL)$pathStr" }
                        }
                    } catch (e: IOException) {
                        log(TAG, VERBOSE) { "$operation normal failed: ${e.message}" }
                        when {
                            hasRoot() -> {
                                log(TAG, VERBOSE) { "$operation(AUTO->ROOT)$pathStr" }
                                rootOps { rootOp(it) }
                            }
                            hasAdb() -> {
                                log(TAG, VERBOSE) { "$operation(AUTO->ADB)$pathStr" }
                                adbOps { adbOp(it) }
                            }
                            else -> throw e
                        }
                    }
                }
            }
        }
    }


    override suspend fun createDir(path: LocalPath): Unit = createDir(path, Mode.AUTO)

    suspend fun createDir(path: LocalPath, mode: Mode = Mode.AUTO): Unit = runIO {
        executeWithModeSelection(
            mode = mode,
            operation = "createDir",
            path = path,
            normalOp = { fileSystemOps.createDir(path) },
            rootOp = { it.createDir(path) },
            adbOp = { it.createDir(path) }
        )
    }

    override suspend fun createFile(path: LocalPath): Unit = createFile(path, Mode.AUTO)

    suspend fun createFile(path: LocalPath, mode: Mode = Mode.AUTO): Unit = runIO {
        executeWithModeSelection(
            mode = mode,
            operation = "createFile",
            path = path,
            normalOp = { fileSystemOps.createFile(path) },
            rootOp = { it.createFile(path) },
            adbOp = { it.createFile(path) }
        )
    }

    override suspend fun lookup(path: LocalPath): LocalPathLookup = lookup(path, Mode.AUTO)

    suspend fun lookup(path: LocalPath, mode: Mode = Mode.AUTO): LocalPathLookup = runIO {
        executeWithModeSelection(
            mode = mode,
            operation = "lookup",
            path = path,
            normalOp = { fileSystemOps.lookup(path) },
            rootOp = { it.lookup(path) },
            adbOp = { it.lookup(path) }
        )
    }

    override suspend fun listFiles(path: LocalPath): List<LocalPath> = listFiles(path, Mode.AUTO)

    suspend fun listFiles(path: LocalPath, mode: Mode = Mode.AUTO): List<LocalPath> = runIO {
        executeWithModeSelection(
            mode = mode,
            operation = "listFiles",
            path = path,
            normalOp = { fileSystemOps.listFiles(path) },
            rootOp = { it.listFiles(path) },
            adbOp = { it.listFiles(path) }
        )
    }

    override suspend fun lookupExtended(path: LocalPath): LocalPathLookupExtended = lookupExtended(path, Mode.AUTO)

    suspend fun lookupExtended(path: LocalPath, mode: Mode = Mode.AUTO): LocalPathLookupExtended = runIO {
        when {
            hasRoot() && (mode == Mode.ROOT || mode == Mode.AUTO) -> {
                TODO()
            }
            hasAdb() && (mode == Mode.ADB || mode == Mode.AUTO) -> {
                TODO()
            }
            mode == Mode.NORMAL || mode == Mode.AUTO -> {
                fileSystemOps.lookupExtended(path)
            }
            else -> throw IOException("No matching mode available.")
        }
    }

    override suspend fun lookupFiles(path: LocalPath): List<LocalPathLookup> = lookupFiles(path, Mode.AUTO)

    suspend fun lookupFiles(path: LocalPath, mode: Mode = Mode.AUTO): List<LocalPathLookup> = runIO {

        when {
            hasRoot() && (mode == Mode.ROOT || mode == Mode.AUTO) -> {
                TODO()
            }
            hasAdb() && (mode == Mode.ADB || mode == Mode.AUTO) -> {
                TODO()
            }
            mode == Mode.NORMAL || mode == Mode.AUTO -> {
                fileSystemOps.lookupFiles(path)
            }
            else -> throw IOException("No matching mode available.")
        }
    }

    override suspend fun lookupFilesExtended(
        path: LocalPath
    ): List<LocalPathLookupExtended> = lookupFilesExtended(path, Mode.AUTO)

    suspend fun lookupFilesExtended(
        path: LocalPath,
        mode: Mode = Mode.AUTO
    ): List<LocalPathLookupExtended> = runIO {
        when {
            hasRoot() && (mode == Mode.ROOT || mode == Mode.AUTO) -> {
                rootOps { it.lookupFilesExtendedStream(path) }
            }
            hasAdb() && (mode == Mode.ADB || mode == Mode.AUTO) -> {
                adbOps { it.lookupFilesExtendedStream(path) }
            }
            mode == Mode.NORMAL || mode == Mode.AUTO -> {
                fileSystemOps.lookupFilesExtended(path)
            }
            else -> throw IOException("No matching mode available.")
        }
    }

    override suspend fun walk(
        path: LocalPath,
        options: APathGateway.WalkOptions<LocalPath, LocalPathLookup>,
    ): Flow<LocalPathLookup> = walk(path, options, Mode.AUTO)

    suspend fun walk(
        path: LocalPath,
        options: APathGateway.WalkOptions<LocalPath, LocalPathLookup>,
        mode: Mode = Mode.AUTO,
    ): Flow<LocalPathLookup> = runIO {
        val javaFile = path.toFile()
        val canRead = when (mode) {
            Mode.AUTO, Mode.NORMAL -> if (javaFile.canRead()) {
                try {
                    javaFile.listFiles2()
                    true
                } catch (e: IOException) {
                    false
                }
            } else {
                false
            }

            else -> false
        }

        when {
            mode == Mode.NORMAL -> {
                log(TAG, VERBOSE) { "walk($mode->NORMAL, direct): $path" }
                if (!canRead) throw ReadException(path = path)
                DirectLocalWalker(
                    fileSystemOps = fileSystemOps,
                    start = path,
                    onFilter = { lookup -> options.onFilter?.invoke(lookup) ?: true },
                    onError = { lookup, exception -> options.onError?.invoke(lookup, exception) ?: true },
                )
            }

            canRead && mode == Mode.AUTO -> {
                log(TAG, VERBOSE) { "walk($mode->NORMAL, escalating): $path" }
                EscalatingWalker(
                    gateway = this@LocalGateway,
                    start = path,
                    options = options,
                )
            }

            hasRoot() && (mode == Mode.ROOT || !canRead && mode == Mode.AUTO) -> {
                if (options.isDirect) {
                    log(TAG, VERBOSE) { "walk($mode->ROOT, direct): $path" }
                    // We need to keep the resource alive until the caller is done with the Flow
                    val resource = rootManager.serviceClient.get()
                    rootOps { it.walk(path, options).onCompletion { resource.close() } }
                } else {
                    log(TAG, VERBOSE) { "walk($mode->ROOT, indirect): $path" }
                    // Can't pass functions via IPC
                    IndirectLocalWalker(
                        gateway = this@LocalGateway,
                        mode = Mode.ROOT,
                        start = path,
                        onFilter = { lookup -> options.onFilter?.invoke(lookup) ?: true },
                        onError = { lookup, exception -> options.onError?.invoke(lookup, exception) ?: true },
                    )
                }
            }

            hasAdb() && (mode == Mode.ADB || !canRead && mode == Mode.AUTO) -> {
                if (options.isDirect) {
                    log(TAG, VERBOSE) { "walk($mode->ADB, direct): $path" }
                    // We need to keep the resource alive until the caller is done with the Flow
                    val resource = adbManager.serviceClient.get()
                    adbOps { it.walk(path, options).onCompletion { resource.close() } }
                } else {
                    log(TAG, VERBOSE) { "walk($mode->ADB, indirect): $path" }
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

            else -> throw IOException("No matching mode available.")
        }
    }

    override suspend fun du(
        path: LocalPath,
        options: APathGateway.DuOptions<LocalPath, LocalPathLookup>,
    ): Long = du(path, options, Mode.AUTO)

    suspend fun du(
        path: LocalPath,
        options: APathGateway.DuOptions<LocalPath, LocalPathLookup> = APathGateway.DuOptions(),
        mode: Mode = Mode.AUTO,
    ): Long = runIO {
        executeWithModeSelection(
            mode = mode,
            operation = "du",
            path = path,
            normalOp = { fileSystemOps.du(path) },
            rootOp = { it.du(path) },
            adbOp = { it.du(path) }
        )
    }

    override suspend fun exists(path: LocalPath): Boolean = exists(path, Mode.AUTO)

    suspend fun exists(path: LocalPath, mode: Mode = Mode.AUTO): Boolean = runIO {
        val javaFile = path.toFile()
        val javaFileParent = javaFile.parentFile

        val canCheckNormal = when (mode) {
            Mode.ROOT -> false
            Mode.ADB -> false
            else -> when {
                // exists() = true is never a false positive
                javaFile.exists() -> true
                // This is a bit iffy, but checking readability on the parent has proven reliable
                javaFileParent?.exists() == true && javaFileParent.canRead() -> true
                // On Android 12+ Android/data isn't accessible anymore via normal java file access.
                hasApiLevel(32) && storageEnvironment.publicDataDirs.any { it.isAncestorOf(path) } -> false
                // If the file path is on public storage, and it wasn't Android/data then, assume true
                else -> storageEnvironment.externalDirs
                    .firstOrNull { it.isAncestorOf(path) }
                    ?.toFile()
                    ?.canRead() ?: false
            }
        }

        when {
            mode == Mode.NORMAL || mode == Mode.AUTO && canCheckNormal -> {
                log(TAG, VERBOSE) { "exists($mode->NORMAL): $path" }
                fileSystemOps.exists(path)
            }

            hasRoot() && (mode == Mode.ROOT || mode == Mode.AUTO) -> {
                log(TAG, VERBOSE) { "exists($mode->ROOT): $path" }
                rootOps { it.exists(path) }
            }

            hasAdb() && (mode == Mode.ADB || mode == Mode.AUTO) -> {
                log(TAG, VERBOSE) { "exists($mode->ADB): $path" }
                adbOps { it.exists(path) }
            }

            else -> throw IOException("No matching mode available.")
        }
    }

    override suspend fun canWrite(path: LocalPath): Boolean = canWrite(path, Mode.AUTO)

    suspend fun canWrite(path: LocalPath, mode: Mode = Mode.AUTO): Boolean = runIO {
        val file = path.toFile()
        val canNormalWrite = when (mode) {
            Mode.ROOT -> false
            Mode.ADB -> false
            else -> file.exists() && file.parentsInclusive.firstOrNull { it.exists() }?.canWrite() ?: false
        }

        when {
            mode == Mode.NORMAL || mode == Mode.AUTO && canNormalWrite -> {
                log(TAG, VERBOSE) { "canWrite($mode->NORMAL): $path" }
                canNormalWrite
            }

            hasRoot() && (mode == Mode.ROOT || mode == Mode.AUTO) -> {
                log(TAG, VERBOSE) { "canWrite($mode->ROOT): $path" }
                rootOps { it.canWrite(path) }
            }

            hasAdb() && (mode == Mode.ADB || mode == Mode.AUTO) -> {
                log(TAG, VERBOSE) { "canWrite($mode->ADB): $path" }
                adbOps { it.canWrite(path) }
            }

            else -> false
        }
    }

    override suspend fun canRead(path: LocalPath): Boolean = canRead(path, Mode.AUTO)

    suspend fun canRead(path: LocalPath, mode: Mode = Mode.AUTO): Boolean = runIO {
        val file = path.toFile()
        val canNormalOpen = when (mode) {
            Mode.ROOT -> false
            Mode.ADB -> false
            // TODO This isn't a great way to check readability
            else -> file.exists() && file.parentsInclusive.firstOrNull { it.exists() }?.isReadable() ?: false
        }

        when {
            mode == Mode.NORMAL || mode == Mode.AUTO && canNormalOpen -> {
                log(TAG, VERBOSE) { "canRead($mode->NORMAL): $path" }
                canNormalOpen
            }

            hasRoot() && (mode == Mode.ROOT || mode == Mode.AUTO) -> {
                log(TAG, VERBOSE) { "canRead($mode->ROOT): $path" }
                rootOps { it.canRead(path) }
            }

            hasAdb() && (mode == Mode.ADB || mode == Mode.AUTO) -> {
                log(TAG, VERBOSE) { "canRead($mode->ADB): $path" }
                adbOps { it.canRead(path) }
            }

            else -> false
        }
    }

    override suspend fun file(path: LocalPath, readWrite: Boolean): FileHandle = file(path, readWrite, Mode.AUTO)

    suspend fun file(path: LocalPath, readWrite: Boolean, mode: Mode = Mode.AUTO): FileHandle = runIO {
        when (mode) {
            Mode.NORMAL -> {
                log(TAG, VERBOSE) { "file(NORMAL, RW=$readWrite): $path" }
                fileSystemOps.file(path, readWrite)
            }
            Mode.ROOT -> {
                log(TAG, VERBOSE) { "file(ROOT, RW=$readWrite): $path" }
                val resource = rootManager.serviceClient.get()
                rootOps {
                    it.file(path, readWrite).callbacks {
                        resource.close()
                        log(TAG, VERBOSE) { "file(ROOT, RW=$readWrite): Closing resource for $path" }
                    }
                }
            }
            Mode.ADB -> {
                log(TAG, VERBOSE) { "file(ADB, RW=$readWrite): $path" }
                val resource = adbManager.serviceClient.get()
                adbOps {
                    it.file(path, readWrite).callbacks {
                        resource.close()
                        log(TAG, VERBOSE) { "file(ADB, RW=$readWrite): Closing resource for $path" }
                    }
                }
            }
            Mode.AUTO -> {
                if (accessibilityChecker.isDefinitelyInaccessible(path, forWriting = readWrite) && hasRoot()) {
                    log(TAG, VERBOSE) { "file(AUTO->ROOT, inaccessible, RW=$readWrite): $path" }
                    val resource = rootManager.serviceClient.get()
                    rootOps {
                        it.file(path, readWrite).callbacks {
                            resource.close()
                            log(TAG, VERBOSE) { "file(ROOT, RW=$readWrite): Closing resource for $path" }
                        }
                    }
                } else {
                    try {
                        fileSystemOps.file(path, readWrite).also {
                            log(TAG, VERBOSE) { "file(AUTO->NORMAL, RW=$readWrite): $path" }
                        }
                    } catch (e: IOException) {
                        log(TAG, VERBOSE) { "file normal failed: ${e.message}" }
                        when {
                            hasRoot() -> {
                                log(TAG, VERBOSE) { "file(AUTO->ROOT, RW=$readWrite): $path" }
                                val resource = rootManager.serviceClient.get()
                                rootOps {
                                    it.file(path, readWrite).callbacks {
                                        resource.close()
                                        log(TAG, VERBOSE) { "file(ROOT, RW=$readWrite): Closing resource for $path" }
                                    }
                                }
                            }
                            hasAdb() -> {
                                log(TAG, VERBOSE) { "file(AUTO->ADB, RW=$readWrite): $path" }
                                val resource = adbManager.serviceClient.get()
                                adbOps {
                                    it.file(path, readWrite).callbacks {
                                        resource.close()
                                        log(TAG, VERBOSE) { "file(ADB, RW=$readWrite): Closing resource for $path" }
                                    }
                                }
                            }
                            else -> throw e
                        }
                    }
                }
            }
        }
    }

    override suspend fun openInputStream(path: LocalPath): InputStream = openInputStream(path, Mode.AUTO)

    suspend fun openInputStream(path: LocalPath, mode: Mode = Mode.AUTO): InputStream = runIO {
        val javaFile = path.toFile()
        when {
            mode == Mode.NORMAL || (mode == Mode.AUTO && javaFile.canRead()) -> {
                fileSystemOps.openInputStream(path)
            }

            hasRoot() && (mode == Mode.ROOT || mode == Mode.AUTO) -> {
                log(TAG, VERBOSE) { "openInputStream($mode->ROOT): $path" }
                rootOps { client ->
                    client.file(path, readWrite = false).source().buffer().inputStream()
                }
            }

            hasAdb() && (mode == Mode.ADB || mode == Mode.AUTO) -> {
                log(TAG, VERBOSE) { "openInputStream($mode->ADB): $path" }
                adbOps { client ->
                    client.file(path, readWrite = false).source().buffer().inputStream()
                }
            }

            else -> throw IOException("No matching mode available for openInputStream")
        }
    }

    override suspend fun openOutputStream(path: LocalPath, append: Boolean): OutputStream =
        openOutputStream(path, append, Mode.AUTO)

    suspend fun openOutputStream(
        path: LocalPath,
        append: Boolean = false,
        mode: Mode = Mode.AUTO
    ): OutputStream = runIO {
        executeWithModeSelection(
            mode = mode,
            operation = "openOutputStream",
            path = path,
            normalOp = { fileSystemOps.openOutputStream(path, append) },
            rootOp = {
                if (append) throw UnsupportedOperationException("Append mode not supported via root/ADB")
                it.file(path, readWrite = true).sink().buffer().outputStream()
            },
            adbOp = {
                if (append) throw UnsupportedOperationException("Append mode not supported via root/ADB")
                it.file(path, readWrite = true).sink().buffer().outputStream()
            }
        )
    }

    override suspend fun createSymlink(linkPath: LocalPath, targetPath: LocalPath): Boolean =
        createSymlink(linkPath, targetPath, Mode.AUTO)

    suspend fun createSymlink(linkPath: LocalPath, targetPath: LocalPath, mode: Mode = Mode.AUTO): Boolean = runIO {
        executeWithModeSelection(
            mode = mode,
            operation = "createSymlink",
            path = linkPath,
            normalOp = { fileSystemOps.createSymlink(linkPath, targetPath) },
            rootOp = { it.createSymlink(linkPath, targetPath) },
            adbOp = { it.createSymlink(linkPath, targetPath) }
        )
    }

    override suspend fun setModifiedAt(path: LocalPath, modifiedAt: Instant): Boolean = setModifiedAt(
        path,
        modifiedAt,
        Mode.AUTO
    )

    suspend fun setModifiedAt(path: LocalPath, modifiedAt: Instant, mode: Mode = Mode.AUTO): Boolean = runIO {
        val canNormalWrite = when (mode) {
            Mode.ROOT -> false
            Mode.ADB -> false
            else -> path.file.canWrite()
        }
        when {
            mode == Mode.NORMAL || mode == Mode.AUTO && canNormalWrite -> {
                log(TAG, VERBOSE) { "setModifiedAt($mode->NORMAL): $path" }
                fileSystemOps.setModifiedAt(path, modifiedAt)
            }

            hasRoot() && (mode == Mode.ROOT || mode == Mode.AUTO) -> {
                log(TAG, VERBOSE) { "setModifiedAt($mode->ROOT): $path" }
                rootOps { it.setModifiedAt(path, modifiedAt) }
            }

            hasAdb() && (mode == Mode.ADB || mode == Mode.AUTO) -> {
                log(TAG, VERBOSE) { "setModifiedAt($mode->ADB): $path" }
                adbOps { it.setModifiedAt(path, modifiedAt) }
            }

            else -> throw IOException("No matching mode available.")
        }
    }

    override suspend fun setPermissions(path: LocalPath, permissions: Permissions): Boolean =
        setPermissions(path, permissions, Mode.AUTO)

    suspend fun setPermissions(path: LocalPath, permissions: Permissions, mode: Mode = Mode.AUTO): Boolean = runIO {
        val canNormalWrite = when (mode) {
            Mode.ROOT -> false
            Mode.ADB -> false
            else -> path.file.canWrite()
        }

        when {
            mode == Mode.NORMAL || mode == Mode.AUTO && canNormalWrite -> {
                log(TAG, VERBOSE) { "setPermissions($mode->NORMAL): $path" }
                fileSystemOps.setPermissions(path, permissions)
            }

            hasRoot() && (mode == Mode.ROOT || mode == Mode.AUTO) -> {
                log(TAG, VERBOSE) { "setPermissions($mode->ROOT): $path" }
                rootOps { it.setPermissions(path, permissions) }
            }


            hasAdb() && (mode == Mode.ADB || mode == Mode.AUTO) -> {
                log(TAG, VERBOSE) { "setPermissions($mode->ADB): $path" }
                adbOps { it.setPermissions(path, permissions) }
            }

            else -> throw IOException("No matching mode available.")
        }
    }

    override suspend fun setOwnership(
        path: LocalPath,
        ownership: Ownership
    ): Boolean = setOwnership(path, ownership, Mode.AUTO)

    suspend fun setOwnership(
        path: LocalPath,
        ownership: Ownership,
        mode: Mode = Mode.AUTO
    ): Boolean = runIO {
        val canNormalWrite = when (mode) {
            Mode.ROOT -> false
            Mode.ADB -> false
            else -> path.file.canWrite()
        }

        when {
            mode == Mode.NORMAL || mode == Mode.AUTO && canNormalWrite -> {
                log(TAG, VERBOSE) { "setOwnership($mode->NORMAL): $path" }
                fileSystemOps.setOwnership(path, ownership)
            }

            hasRoot() && (mode == Mode.ROOT || mode == Mode.AUTO) -> {
                log(TAG, VERBOSE) { "setOwnership($mode->ROOT): $path" }
                rootOps { it.setOwnership(path, ownership) }
            }

            hasAdb() && (mode == Mode.ADB || mode == Mode.AUTO) -> {
                log(TAG, VERBOSE) { "setOwnership($mode->ADB): $path" }
                adbOps { it.setOwnership(path, ownership) }
            }

            else -> throw IOException("No matching mode available.")
        }
    }

    override suspend fun getInfo(path: LocalPath): FileSystemInfo = getInfo(path, Mode.AUTO)

    suspend fun getInfo(path: LocalPath, mode: Mode): FileSystemInfo = runIO {
        log(TAG, VERBOSE) { "getInfo(): $path" }
        val canNormalRead = when (mode) {
            Mode.ROOT -> false
            Mode.ADB -> false
            else -> path.file.canRead()
        }

        when {
            mode == Mode.NORMAL || mode == Mode.AUTO && canNormalRead -> {
                log(TAG, VERBOSE) { "getInfo($mode->NORMAL): $path" }
                fileSystemOps.getInfo(path)
            }

            hasRoot() && (mode == Mode.ROOT || mode == Mode.AUTO) -> {
                log(TAG, VERBOSE) { "getInfo($mode->ROOT): $path" }
                rootOps { TODO() }
            }

            hasAdb() && (mode == Mode.ADB || mode == Mode.AUTO) -> {
                log(TAG, VERBOSE) { "getInfo($mode->ADB): $path" }
                adbOps { TODO() }
            }

            else -> throw IOException("No matching mode available.")
        }
    }

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

    suspend fun delete(path: LocalPath, recursive: Boolean = false, mode: Mode = Mode.AUTO): Boolean = runIO {
        val javaFile = path.toFile()
        when {
            mode == Mode.NORMAL || (mode == Mode.AUTO && javaFile.canWrite()) -> {
                fileSystemOps.delete(path, recursive)
                true
            }

            hasRoot() && (mode == Mode.ROOT || mode == Mode.AUTO) -> {
                log(TAG, VERBOSE) { "delete($mode->ROOT, recursive=$recursive): $path" }
                rootOps { it.delete(path, recursive) }
            }

            hasAdb() && (mode == Mode.ADB || mode == Mode.AUTO) -> {
                log(TAG, VERBOSE) { "delete($mode->ADB, recursive=$recursive): $path" }
                adbOps { it.delete(path, recursive) }
            }

            else -> throw IOException("No matching mode available for delete")
        }
    }

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