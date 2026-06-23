package eu.darken.butler.common.files

import eu.darken.butler.common.coroutine.AppScope
import eu.darken.butler.common.coroutine.DispatcherProvider
import eu.darken.butler.common.debug.logging.Logging.Priority.*
import eu.darken.butler.common.debug.logging.asLog
import eu.darken.butler.common.debug.logging.log
import eu.darken.butler.common.debug.logging.logTag
import eu.darken.butler.common.files.actions.CopyAction
import eu.darken.butler.common.files.actions.CreateAction
import eu.darken.butler.common.files.actions.DeleteAction
import eu.darken.butler.common.files.actions.MoveAction
import eu.darken.butler.common.files.actions.PathActionIssue
import eu.darken.butler.common.files.errors.ReadException
import eu.darken.butler.common.files.local.LocalGateway
import eu.darken.butler.common.files.metadata.FileSystem
import eu.darken.butler.common.files.metadata.Ownership
import eu.darken.butler.common.files.metadata.Permissions
import eu.darken.butler.common.files.operations.GenericCrossTypeCopyStrategy
import eu.darken.butler.common.files.operations.GenericCrossTypeMoveStrategy
import eu.darken.butler.common.files.operations.TransferStrategy
import eu.darken.butler.common.files.operations.copyGeneric
import eu.darken.butler.common.files.operations.moveGeneric
import eu.darken.butler.common.files.saf.SAFGateway
import eu.darken.butler.common.files.saf.location.SAFLocationManager
import eu.darken.butler.common.sharedresource.SharedResource
import eu.darken.butler.common.sharedresource.adoptChildResource
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.plus
import okio.FileHandle
import okio.IOException
import java.io.InputStream
import java.io.OutputStream
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.time.Instant

@Singleton
class GatewaySwitch @Inject constructor(
    @AppScope private val appScope: CoroutineScope,
    dispatcherProvider: DispatcherProvider,
    private val safGateway: SAFGateway,
    private val localGateway: LocalGateway,
    private val safLocationManager: SAFLocationManager,
) : APathGateway<APath<*>, APathLookup<APath<*>>> {

    private suspend fun <T : APath<T>, R> useGateway(
        path: T,
        action: suspend APathGateway<T, APathLookup<T>>.(T) -> R
    ): R {
        @Suppress("UNCHECKED_CAST")
        val targetGateway = getGateway(path) as APathGateway<T, APathLookup<T>>
        return action(targetGateway, path)
    }

    private suspend fun resolveGatewayType(path: APath<*>): APathGateway<out APath<*>, out APathLookup<*>> {
        val gateway = when (path) {
            is SAFPath -> {
                safGateway.also { adoptChildResource(it) }
            }

            is LocalPath -> {
                localGateway.also { adoptChildResource(it) }
            }

        }
        return gateway
    }

    suspend fun getGateway(type: APath<*>): APathGateway<out APath<*>, out APathLookup<*>> {
        return resolveGatewayType(type)
    }

    override val sharedResource = SharedResource.createKeepAlive(TAG, appScope + dispatcherProvider.IO)

    override suspend fun createDir(path: APath<*>, createParents: Boolean) {
        return useGateway(path) { createDir(path, createParents) }
    }

    override suspend fun createFile(path: APath<*>, createParents: Boolean) {
        return useGateway(path) { createFile(path, createParents) }
    }

    override suspend fun createSymlink(linkPath: APath<*>, targetPath: APath<*>): Boolean {
        return useGateway(linkPath) { createSymlink(linkPath, targetPath) }
    }

    override suspend fun readSymbolicLink(linkPath: APath<*>): APath<*> {
        return useGateway(linkPath) { readSymbolicLink(linkPath) }
    }

    override suspend fun move(source: APath<*>, destination: APath<*>): Boolean {
        return useGateway(source) { move(source, destination) }
    }

    override suspend fun lookup(path: APath<*>, options: LookupOptions): APathLookup<APath<*>> {
        return lookup(path, options, Type.CURRENT)
    }

    suspend fun lookup(path: APath<*>, options: LookupOptions, type: Type): APathLookup<APath<*>> {
        val mapped = path.toTargetType(type)
        return try {
            useGateway(mapped) { lookup(path, options) }
        } catch (oge: ReadException) {
            if (type != Type.AUTO) throw oge
            log(TAG, WARN) { "lookup(...): Original lookup failed, try alternative: ${oge.asLog()}" }

            val fallback = path.toAlternative()
            try {
                useGateway(fallback) { lookup(path, options) }
            } catch (e: ReadException) {
                log(TAG, WARN) { "lookup(...): Alternative lookup failed either: ${e.asLog()}" }
                throw oge
            }
        }
    }

    override suspend fun lookupFiles(path: APath<*>, options: LookupOptions): List<APathLookup<APath<*>>> {
        return lookupFiles(path, options, Type.CURRENT)
    }

    suspend fun lookupFiles(path: APath<*>, options: LookupOptions, type: Type): List<APathLookup<APath<*>>> {
        val mapped = path.toTargetType(type)
        return try {
            useGateway(mapped) { lookupFiles(path, options) }
        } catch (oge: ReadException) {
            if (type != Type.AUTO) throw oge
            log(TAG, WARN) { "lookupFiles(...): Original lookup failed, try alternative: ${oge.asLog()}" }

            val fallback = path.toAlternative()
            try {
                useGateway(fallback) { lookupFiles(path, options) }
            } catch (e: ReadException) {
                log(TAG, WARN) { "lookupFiles(...): Alternative lookup failed either: ${e.asLog()}" }
                throw oge
            }
        }
    }

    @Suppress("UNCHECKED_CAST")
    override suspend fun walk(
        path: APath<*>,
        lookupOptions: LookupOptions,
        walkOptions: APathGateway.WalkOptions<APath<*>, APathLookup<APath<*>>>
    ): Flow<APathLookup<APath<*>>> {
        return useGateway(path) { walk(path, lookupOptions, walkOptions) }
    }

    @Suppress("UNCHECKED_CAST")
    override suspend fun du(
        path: APath<*>,
        options: APathGateway.DuOptions<APath<*>, APathLookup<APath<*>>>
    ): Long {
        return useGateway(path) { du(path, options) }
    }

    override suspend fun listFiles(path: APath<*>): List<APath<*>> {
        return useGateway(path) { listFiles(path) }
    }

    override suspend fun exists(path: APath<*>): Boolean {
        return exists(path, Type.CURRENT)
    }

    suspend fun exists(path: APath<*>, type: Type): Boolean {
        val mapped = path.toTargetType(type)
        return try {
            useGateway(mapped) { exists(path) }
        } catch (e: ReadException) {
            if (type != Type.AUTO) throw e

            val fallback = path.toAlternative()
            useGateway(fallback) { exists(path) }
        }
    }

    override suspend fun canWrite(path: APath<*>): Boolean {
        return useGateway(path) { canWrite(path) }
    }

    override suspend fun canRead(path: APath<*>): Boolean {
        return useGateway(path) { canRead(path) }
    }

    override suspend fun delete(path: APath<*>, recursive: Boolean): Boolean {
        return useGateway(path) { delete(path, recursive) }
    }

    override suspend fun openInputStream(path: APath<*>): InputStream {
        return useGateway(path) { openInputStream(path) }
    }

    override suspend fun openOutputStream(path: APath<*>, append: Boolean): OutputStream {
        return useGateway(path) { openOutputStream(path, append) }
    }

    override suspend fun file(path: APath<*>, readWrite: Boolean): FileHandle {
        return useGateway(path) { file(path, readWrite) }
    }

    override suspend fun delete(
        targets: Set<APath<*>>,
        options: DeleteAction.Options<APath<*>>
    ): Flow<DeleteAction.State<APath<*>, APathLookup<APath<*>>>> = flow {
        // Group targets by gateway type for optimal processing
        val targetsByType = targets.groupBy { it::class }

        var totalBytesDeleted = 0L
        val allDeletedFiles = mutableSetOf<APathLookup<APath<*>>>()
        val allSkippedFiles = mutableSetOf<APathLookup<APath<*>>>()

        for (targetsGroup in targetsByType.values) {
            // Track max observed bytes, not just Completed.bytesTotal: Active progress may include
            // items that end up skipped (e.g. ignoreMissing), and the offset must stay monotonic.
            var groupDeletedBytes = 0L
            useGateway(targetsGroup.first()) {
                delete(targetsGroup.toSet(), options)
            }.collect { state ->
                when (state) {
                    is DeleteAction.State.Active -> {
                        groupDeletedBytes = maxOf(groupDeletedBytes, state.deletedBytes)
                        emit(state.copy(deletedBytes = totalBytesDeleted + state.deletedBytes))
                    }

                    is DeleteAction.State.Completed -> {
                        groupDeletedBytes = maxOf(groupDeletedBytes, state.bytesTotal)
                        allDeletedFiles.addAll(state.deleted)
                        allSkippedFiles.addAll(state.skipped)
                    }
                }
            }
            totalBytesDeleted += groupDeletedBytes
        }

        emit(
            DeleteAction.State.Completed(
                deleted = allDeletedFiles,
                skipped = allSkippedFiles,
            )
        )
    }

    override suspend fun setModifiedAt(path: APath<*>, modifiedAt: Instant): Boolean {
        return useGateway(path) { setModifiedAt(path, modifiedAt) }
    }

    override suspend fun setPermissions(path: APath<*>, permissions: Permissions): Boolean {
        return useGateway(path) { setPermissions(path, permissions) }
    }

    override suspend fun setOwnership(path: APath<*>, ownership: Ownership): Boolean {
        return useGateway(path) { setOwnership(path, ownership) }
    }

    override suspend fun canonicalize(path: APath<*>): APath<*> {
        return useGateway(path) { canonicalize(path) }
    }

    private suspend fun APath<*>.toTargetType(type: Type): APath<*> = when (type) {
        Type.AUTO -> this
        Type.CURRENT -> this
        Type.FORCED_LOCAL -> when (this) {
            is LocalPath -> this
            is SAFPath -> safLocationManager.toLocalPath(this) ?: throw IOException("Can't map $this to LOCAL")
        }

        Type.FORCED_SAF -> when (this) {
            is LocalPath -> safLocationManager.toSAFPath(this) ?: throw IOException("Can't map $this to SAF")
            is SAFPath -> this
        }
    }

    private suspend fun APath<*>.toAlternative(): APath<*> = when (this) {
        is LocalPath -> safLocationManager.toSAFPath(this) ?: throw ReadException("Can't map to SAF", this)
        is SAFPath -> safLocationManager.toLocalPath(this) ?: throw ReadException("Can't map to LOCAL", this)
    }

    override suspend fun getFileSystem(path: APath<*>): FileSystem {
        return useGateway(path) { this.getFileSystem(it) }
    }

    override suspend fun copy(
        sources: Set<APath<*>>,
        destination: APath<*>,
        onIssue: (suspend (PathActionIssue) -> PathActionIssue.Resolution)?,
        options: CopyAction.Options
    ): Flow<CopyAction.State<APath<*>, APathLookup<APath<*>>, APath<*>, APathLookup<APath<*>>>> = flow {
        // Group sources by gateway type for optimal processing
        val sourcesByType = sources.groupBy { it::class }
        val destinationType = destination::class

        var totalBytesProcessed = 0L
        val allCopiedFiles = mutableSetOf<Pair<APathLookup<APath<*>>, APathLookup<APath<*>>>>()
        val allSkippedFiles = mutableSetOf<APathLookup<APath<*>>>()

        for ((sourceType, sourcesGroup) in sourcesByType) {
            when {
                // Same gateway type - use native batch implementation
                sourceType == destinationType -> {
                    useGateway(sourcesGroup.first()) {
                        copy(sourcesGroup.toSet(), destination, onIssue, options)
                    }.collect { state ->
                        when (state) {
                            is CopyAction.State.Active -> {
                                emit(state.copy(copiedBytes = totalBytesProcessed + state.copiedBytes))
                            }

                            is CopyAction.State.Completed -> {
                                totalBytesProcessed += state.copiedBytes
                                allCopiedFiles.addAll(state.copied)
                                allSkippedFiles.addAll(state.skipped)
                            }
                        }
                    }
                }
                // Cross-gateway copy - use batch implementation
                else -> {
                    performCrossGatewayCopy(sourcesGroup, destination, onIssue, options).collect { state ->
                        when (state) {
                            is CopyAction.State.Active -> {
                                @Suppress("UNCHECKED_CAST")
                                emit(state.copy(copiedBytes = totalBytesProcessed + state.copiedBytes) as CopyAction.State.Active<APath<*>, APathLookup<APath<*>>, APath<*>, APathLookup<APath<*>>>)
                            }

                            is CopyAction.State.Completed -> {
                                totalBytesProcessed += state.copiedBytes
                                @Suppress("UNCHECKED_CAST")
                                allCopiedFiles.addAll(state.copied as Collection<Pair<APathLookup<APath<*>>, APathLookup<APath<*>>>>)
                                @Suppress("UNCHECKED_CAST")
                                allSkippedFiles.addAll(state.skipped as Collection<APathLookup<APath<*>>>)
                            }
                        }
                    }
                }
            }
        }

        emit(
            CopyAction.State.Completed(
                copied = allCopiedFiles,
                skipped = allSkippedFiles,
                copiedBytes = totalBytesProcessed
            )
        )
    }

    override suspend fun move(
        sources: Set<APath<*>>,
        destination: APath<*>,
        onIssue: (suspend (PathActionIssue) -> PathActionIssue.Resolution)?,
        options: MoveAction.Options
    ): Flow<MoveAction.State<APath<*>, APathLookup<APath<*>>, APath<*>, APathLookup<APath<*>>>> = flow {
        // Group sources by gateway type for optimal processing
        val sourcesByType = sources.groupBy { it::class }
        val destinationType = destination::class

        var totalBytesMoved = 0L
        val allMovedFiles = mutableSetOf<Pair<APathLookup<APath<*>>, APathLookup<APath<*>>>>()
        val allSkippedFiles = mutableSetOf<APathLookup<APath<*>>>()

        for ((sourceType, sourcesGroup) in sourcesByType) {
            when {
                // Same gateway type - use native batch implementation
                sourceType == destinationType -> {
                    useGateway(sourcesGroup.first()) {
                        move(sourcesGroup.toSet(), destination, onIssue, options)
                    }.collect { state ->
                        when (state) {
                            is MoveAction.State.Active<*, *, *, *> -> {
                                @Suppress("UNCHECKED_CAST")
                                emit(state.copy(movedBytes = totalBytesMoved + state.movedBytes) as MoveAction.State.Active<APath<*>, APathLookup<APath<*>>, APath<*>, APathLookup<APath<*>>>)
                            }

                            is MoveAction.State.Completed -> {
                                totalBytesMoved += state.bytesMoved
                                allMovedFiles.addAll(state.movedFiles)
                                allSkippedFiles.addAll(state.skippedFiles)
                            }
                        }
                    }
                }
                // Cross-gateway move - use batch implementation
                else -> {
                    performCrossGatewayMove(sourcesGroup, destination, onIssue, options).collect { state ->
                        when (state) {
                            is MoveAction.State.Active<*, *, *, *> -> {
                                @Suppress("UNCHECKED_CAST")
                                emit(state.copy(movedBytes = totalBytesMoved + state.movedBytes) as MoveAction.State.Active<APath<*>, APathLookup<APath<*>>, APath<*>, APathLookup<APath<*>>>)
                            }

                            is MoveAction.State.Completed -> {
                                totalBytesMoved += state.bytesMoved
                                @Suppress("UNCHECKED_CAST")
                                allMovedFiles.addAll(state.movedFiles as Collection<Pair<APathLookup<APath<*>>, APathLookup<APath<*>>>>)
                                @Suppress("UNCHECKED_CAST")
                                allSkippedFiles.addAll(state.skippedFiles as Collection<APathLookup<APath<*>>>)
                            }
                        }
                    }
                }
            }
        }

        emit(
            MoveAction.State.Completed(
                movedFiles = allMovedFiles,
                skippedFiles = allSkippedFiles,
                bytesMoved = totalBytesMoved
            )
        )
    }

    private suspend fun performCrossGatewayCopy(
        sources: Collection<APath<*>>,
        target: APath<*>,
        onIssue: (suspend (PathActionIssue) -> PathActionIssue.Resolution)?,
        options: CopyAction.Options
    ): Flow<CopyAction.State<*, *, *, *>> = flow {
        log(TAG, DEBUG) { "performCrossGatewayCopy(): ${sources.size} sources -> $target" }

        val transferOptions = TransferStrategy.Options(
            preserveAttributes = options.preserveAttributes,
            followSymlinks = options.followSymlinks
        )

        when (sources.first()) {
            is LocalPath -> {
                @Suppress("UNCHECKED_CAST")
                (sources as Collection<LocalPath>).copyCrossType(target, transferOptions, onIssue)
                    .collect { state -> emit(state) }
            }

            is SAFPath -> {
                @Suppress("UNCHECKED_CAST")
                (sources as Collection<SAFPath>).copyCrossType(target, transferOptions, onIssue)
                    .collect { state -> emit(state) }
            }
        }
    }

    private suspend fun performCrossGatewayMove(
        sources: Collection<APath<*>>,
        target: APath<*>,
        onIssue: (suspend (PathActionIssue) -> PathActionIssue.Resolution)?,
        options: MoveAction.Options
    ): Flow<MoveAction.State<*, *, *, *>> = flow {
        log(TAG, DEBUG) { "performCrossGatewayMove(): ${sources.size} sources -> $target" }

        val transferOptions = TransferStrategy.Options(
            preserveAttributes = options.preserveAttributes,
            followSymlinks = false,  // MoveAction doesn't have followSymlinks option
            attemptAtomicMove = options.attemptAtomicMove
        )

        when (sources.first()) {
            is LocalPath -> {
                @Suppress("UNCHECKED_CAST")
                (sources as Collection<LocalPath>).moveCrossType(target, transferOptions, onIssue)
                    .collect { state -> emit(state) }
            }

            is SAFPath -> {
                @Suppress("UNCHECKED_CAST")
                (sources as Collection<SAFPath>).moveCrossType(target, transferOptions, onIssue)
                    .collect { state -> emit(state) }
            }
        }
    }

    // ========================================================================
    // Cross-Type Copy Extensions (Receiver-based dispatch with exhaustiveness)
    // ========================================================================

    @JvmName("localPathCopyCrossType")
    private suspend fun Collection<LocalPath>.copyCrossType(
        destination: APath<*>,
        options: TransferStrategy.Options,
        onIssue: (suspend (PathActionIssue) -> PathActionIssue.Resolution)?
    ): Flow<CopyAction.State<*, *, *, *>> = when (destination) {
        is LocalPath -> error("Same-type operations should be handled by native implementation")
        is SAFPath -> copyGeneric(
            destination = destination,
            sourceOps = localGateway,
            destOps = safGateway,
            strategy = GenericCrossTypeCopyStrategy(),
            options = options,
            onIssue = onIssue,
        )
    }

    @JvmName("safPathCopyCrossType")
    private suspend fun Collection<SAFPath>.copyCrossType(
        destination: APath<*>,
        options: TransferStrategy.Options,
        onIssue: (suspend (PathActionIssue) -> PathActionIssue.Resolution)?
    ): Flow<CopyAction.State<*, *, *, *>> = when (destination) {
        is LocalPath -> copyGeneric(
            destination = destination,
            sourceOps = safGateway,
            destOps = localGateway,
            strategy = GenericCrossTypeCopyStrategy(),
            options = options,
            onIssue = onIssue,
        )

        is SAFPath -> error("Same-type operations should be handled by native implementation")
    }

    // ========================================================================
    // Cross-Type Move Extensions (Receiver-based dispatch with exhaustiveness)
    // ========================================================================

    @JvmName("localPathMoveCrossType")
    private suspend fun Collection<LocalPath>.moveCrossType(
        destination: APath<*>,
        options: TransferStrategy.Options,
        onIssue: (suspend (PathActionIssue) -> PathActionIssue.Resolution)?
    ): Flow<MoveAction.State<*, *, *, *>> = when (destination) {
        is LocalPath -> error("Same-type operations should be handled by native implementation")
        is SAFPath -> moveGeneric(
            destination = destination,
            sourceOps = localGateway,
            destOps = safGateway,
            strategy = GenericCrossTypeMoveStrategy(),
            options = options,
            onIssue = onIssue,
        )
    }

    @JvmName("safPathMoveCrossType")
    private suspend fun Collection<SAFPath>.moveCrossType(
        destination: APath<*>,
        options: TransferStrategy.Options,
        onIssue: (suspend (PathActionIssue) -> PathActionIssue.Resolution)?
    ): Flow<MoveAction.State<*, *, *, *>> = when (destination) {
        is LocalPath -> moveGeneric(
            destination = destination,
            sourceOps = safGateway,
            destOps = localGateway,
            strategy = GenericCrossTypeMoveStrategy(),
            options = options,
            onIssue = onIssue,
        )

        is SAFPath -> error("Same-type operations should be handled by native implementation")
    }

    override suspend fun create(
        target: APath<*>,
        type: CreateAction.CreateType,
        options: CreateAction.Options
    ): Flow<CreateAction.State<APath<*>, APathLookup<APath<*>>>> {
        return useGateway(target) { create(target, type, options) }
    }

    enum class Type {
        CURRENT,
        FORCED_LOCAL,
        FORCED_SAF,
        AUTO
    }

    companion object {
        val TAG = logTag("Gateway", "Switch")
    }
}