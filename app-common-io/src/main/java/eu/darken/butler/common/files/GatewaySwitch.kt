package eu.darken.butler.common.files

import eu.darken.butler.common.coroutine.AppScope
import eu.darken.butler.common.coroutine.DispatcherProvider
import eu.darken.butler.common.debug.logging.Logging.Priority.*
import eu.darken.butler.common.debug.logging.asLog
import eu.darken.butler.common.debug.logging.log
import eu.darken.butler.common.debug.logging.logTag
import eu.darken.butler.common.files.actions.CopyAction
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
import eu.darken.butler.common.sharedresource.SharedResource
import eu.darken.butler.common.sharedresource.adoptChildResource
import eu.darken.butler.common.storage.PathMapper
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.asFlow
import kotlinx.coroutines.flow.flatMapConcat
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
    private val mapper: PathMapper,
) : APathGateway<APath<*>, APathLookup<APath<*>>, APathLookupExtended<APath<*>>> {

    private suspend fun <T : APath<T>, R> useGateway(
        path: T,
        action: suspend APathGateway<T, APathLookup<T>, APathLookupExtended<T>>.(T) -> R
    ): R {
        @Suppress("UNCHECKED_CAST")
        val targetGateway = getGateway(path) as APathGateway<T, APathLookup<T>, APathLookupExtended<T>>
        return action(targetGateway, path)
    }

    private suspend fun resolveGatewayType(path: APath<*>): APathGateway<out APath<*>, out APathLookup<*>, out APathLookupExtended<*>> {
        val gateway = when (path) {
            is SAFPath -> {
                safGateway.also { adoptChildResource(it) }
            }

            is LocalPath -> {
                localGateway.also { adoptChildResource(it) }
            }

            else -> throw IllegalArgumentException("Can't map $path to gateway")
        }
        return gateway
    }

    suspend fun getGateway(type: APath<*>): APathGateway<out APath<*>, out APathLookup<*>, out APathLookupExtended<*>> {
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

    override suspend fun lookup(path: APath<*>): APathLookup<APath<*>> {
        return lookup(path, Type.CURRENT)
    }

    suspend fun lookup(path: APath<*>, type: Type): APathLookup<APath<*>> {
        val mapped = path.toTargetType(type)
        return try {
            useGateway(mapped) { lookup(path) }
        } catch (oge: ReadException) {
            if (type != Type.AUTO) throw oge
            log(TAG, WARN) { "lookup(...): Original lookup failed, try alternative: ${oge.asLog()}" }

            val fallback = path.toAlternative()
            try {
                useGateway(fallback) { lookup(path) }
            } catch (e: ReadException) {
                log(TAG, WARN) { "lookup(...): Alternative lookup failed either: ${e.asLog()}" }
                throw oge
            }
        }
    }

    override suspend fun lookupFiles(path: APath<*>): List<APathLookup<APath<*>>> {
        return lookupFiles(path, Type.CURRENT)
    }

    suspend fun lookupFiles(path: APath<*>, type: Type): List<APathLookup<APath<*>>> {
        val mapped = path.toTargetType(type)
        return try {
            useGateway(mapped) { lookupFiles(path) }
        } catch (oge: ReadException) {
            if (type != Type.AUTO) throw oge
            log(TAG, WARN) { "lookupFiles(...): Original lookup failed, try alternative: ${oge.asLog()}" }

            val fallback = path.toAlternative()
            try {
                useGateway(fallback) { lookupFiles(path) }
            } catch (e: ReadException) {
                log(TAG, WARN) { "lookupFiles(...): Alternative lookup failed either: ${e.asLog()}" }
                throw oge
            }
        }
    }

    override suspend fun lookupExtended(path: APath<*>): APathLookupExtended<APath<*>> {
        return lookupExtended(path, Type.CURRENT)
    }

    suspend fun lookupExtended(path: APath<*>, type: Type): APathLookupExtended<APath<*>> {
        val mapped = path.toTargetType(type)
        return try {
            useGateway(mapped) { lookupExtended(path) }
        } catch (oge: ReadException) {
            if (type != Type.AUTO) throw oge
            log(TAG, WARN) { "lookupExtended(...): Original lookup failed, try alternative: ${oge.asLog()}" }

            val fallback = path.toAlternative()
            try {
                useGateway(fallback) { lookupExtended(path) }
            } catch (e: ReadException) {
                log(TAG, WARN) { "lookupExtended(...): Alternative lookup failed either: ${e.asLog()}" }
                throw oge
            }
        }
    }

    override suspend fun lookupFilesExtended(path: APath<*>): List<APathLookupExtended<APath<*>>> {
        return lookupFilesExtended(path, Type.CURRENT)
    }

    suspend fun lookupFilesExtended(path: APath<*>, type: Type): List<APathLookupExtended<APath<*>>> {
        val mapped = path.toTargetType(type)
        return try {
            useGateway(mapped) { lookupFilesExtended(path) }
        } catch (oge: ReadException) {
            if (type != Type.AUTO) throw oge
            log(TAG, WARN) { "lookupFilesExtended(...): Original lookup failed, try alternative: ${oge.asLog()}" }

            val fallback = path.toAlternative()
            try {
                useGateway(fallback) { lookupFilesExtended(path) }
            } catch (e: ReadException) {
                log(TAG, WARN) { "lookupFilesExtended(...): Alternative lookup failed either: ${e.asLog()}" }
                throw oge
            }
        }
    }

    @Suppress("UNCHECKED_CAST")
    override suspend fun walk(
        path: APath<*>,
        options: APathGateway.WalkOptions<APath<*>, APathLookup<APath<*>>>
    ): Flow<APathLookup<APath<*>>> {
        return useGateway(path) { walk(path, options) }
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
    ): Flow<DeleteAction.State<APath<*>, APathLookup<APath<*>>>> = targets
        .groupBy { it::class }.values.asFlow()
        .flatMapConcat { group ->
            useGateway(group.first()) {
                delete(group.toSet(), options)
            }
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

    private suspend fun APath<*>.toTargetType(type: Type): APath<*> = when (type) {
        Type.AUTO -> this
        Type.CURRENT -> this
        Type.FORCED_LOCAL -> when (this) {
            is LocalPath -> this
            is SAFPath -> mapper.toLocalPath(this) ?: throw IOException("Can't map $this to LOCAL")
            else -> throw IllegalArgumentException("Can't map $this to $type")
        }

        Type.FORCED_SAF -> when (this) {
            is LocalPath -> mapper.toSAFPath(this) ?: throw IOException("Can't map $this to SAF")
            is SAFPath -> this
            else -> throw IllegalArgumentException("Can't map $this to $type")
        }
    }

    private suspend fun APath<*>.toAlternative(): APath<*> = when (this) {
        is LocalPath -> mapper.toSAFPath(this) ?: throw ReadException("Can't map to SAF", this)
        is SAFPath -> mapper.toLocalPath(this) ?: throw ReadException("Can't map to LOCAL", this)
        is RawPath -> throw UnsupportedOperationException("Alternative mapping for RAW not available")
    }

    override suspend fun getFileSystem(path: APath<*>): FileSystem {
        return useGateway(path) { this.getFileSystem(it) }
    }

    override suspend fun copy(
        sources: Set<APath<*>>,
        destination: APath<*>,
        onIssue: (suspend (PathActionIssue) -> PathActionIssue.Resolution)?,
        options: CopyAction.Options<APath<*>>
    ): Flow<CopyAction.State<APath<*>, APathLookup<APath<*>>>> = flow {
        // Group sources by gateway type for optimal processing
        val sourcesByType = sources.groupBy { it::class }
        val destinationType = destination::class

        var totalBytesProcessed = 0L
        val allCopiedFiles = mutableSetOf<Pair<APath<*>, APath<*>>>()
        val allSkippedFiles = mutableSetOf<APath<*>>()

        for ((sourceType, sourcesGroup) in sourcesByType) {
            when {
                // Same gateway type - use native batch implementation
                sourceType == destinationType -> {
                    useGateway(sourcesGroup.first()) {
                        copy(sourcesGroup.toSet(), destination, onIssue, options)
                    }.collect { state ->
                        when (state) {
                            is CopyAction.State.Progress -> {
                                emit(state.copy(copiedBytes = totalBytesProcessed + state.copiedBytes))
                            }
                            is CopyAction.State.Result -> {
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
                            is CopyAction.State.Progress -> {
                                @Suppress("UNCHECKED_CAST")
                                emit(state.copy(copiedBytes = totalBytesProcessed + state.copiedBytes) as CopyAction.State.Progress<APath<*>, APathLookup<APath<*>>>)
                            }
                            is CopyAction.State.Result -> {
                                totalBytesProcessed += state.copiedBytes
                                allCopiedFiles.addAll(state.copied)
                                allSkippedFiles.addAll(state.skipped)
                            }
                        }
                    }
                }
            }
        }

        emit(
            CopyAction.State.Result(
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
        options: MoveAction.Options<APath<*>>
    ): Flow<MoveAction.State<APath<*>, APathLookup<APath<*>>>> = flow {
        // Group sources by gateway type for optimal processing
        val sourcesByType = sources.groupBy { it::class }
        val destinationType = destination::class

        var totalBytesMoved = 0L
        val allMovedFiles = mutableSetOf<Pair<APath<*>, APath<*>>>()
        val allSkippedFiles = mutableSetOf<APath<*>>()

        for ((sourceType, sourcesGroup) in sourcesByType) {
            when {
                // Same gateway type - use native batch implementation
                sourceType == destinationType -> {
                    useGateway(sourcesGroup.first()) {
                        move(sourcesGroup.toSet(), destination, onIssue, options)
                    }.collect { state ->
                        when (state) {
                            is MoveAction.State.Progress -> {
                                emit(state.copy(movedBytes = totalBytesMoved + state.movedBytes))
                            }
                            is MoveAction.State.Result -> {
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
                            is MoveAction.State.Progress -> {
                                @Suppress("UNCHECKED_CAST")
                                emit(state.copy(movedBytes = totalBytesMoved + state.movedBytes) as MoveAction.State.Progress<APath<*>, APathLookup<APath<*>>>)
                            }
                            is MoveAction.State.Result -> {
                                totalBytesMoved += state.bytesMoved
                                allMovedFiles.addAll(state.movedFiles)
                                allSkippedFiles.addAll(state.skippedFiles)
                            }
                        }
                    }
                }
            }
        }

        emit(
            MoveAction.State.Result(
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
        options: CopyAction.Options<APath<*>>
    ): Flow<CopyAction.State<*, *>> = flow {
        log(TAG, DEBUG) { "performCrossGatewayCopy(): ${sources.size} sources -> $target" }

        val transferOptions = TransferStrategy.Options(
            preserveAttributes = options.preserveAttributes,
            followSymlinks = options.followSymlinks
        )

        // Exhaustive when - compiler enforces all sealed subtypes are handled!
        when (val firstSource = sources.firstOrNull()) {
            null -> return@flow
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
            is RawPath -> throw IllegalArgumentException("RawPath does not support cross-type copy operations")
        }
    }

    private suspend fun performCrossGatewayMove(
        sources: Collection<APath<*>>,
        target: APath<*>,
        onIssue: (suspend (PathActionIssue) -> PathActionIssue.Resolution)?,
        options: MoveAction.Options<APath<*>>
    ): Flow<MoveAction.State<*, *>> = flow {
        log(TAG, DEBUG) { "performCrossGatewayMove(): ${sources.size} sources -> $target" }

        val transferOptions = TransferStrategy.Options(
            preserveAttributes = options.preserveAttributes,
            followSymlinks = false  // MoveAction doesn't have followSymlinks option
        )

        // Exhaustive when - compiler enforces all sealed subtypes are handled!
        when (val firstSource = sources.firstOrNull()) {
            null -> return@flow
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
            is RawPath -> throw IllegalArgumentException("RawPath does not support cross-type move operations")
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
    ): Flow<CopyAction.State<*, *>> = when (destination) {
        is LocalPath -> error("Same-type operations should be handled by native implementation")
        is SAFPath -> copyGeneric(
            destination = destination,
            sourceOps = localGateway,
            destOps = safGateway,
            strategy = GenericCrossTypeCopyStrategy(),
            options = options,
            onIssue = onIssue,
        )
        is RawPath -> error("RawPath does not support cross-type copy operations")
    }

    @JvmName("safPathCopyCrossType")
    private suspend fun Collection<SAFPath>.copyCrossType(
        destination: APath<*>,
        options: TransferStrategy.Options,
        onIssue: (suspend (PathActionIssue) -> PathActionIssue.Resolution)?
    ): Flow<CopyAction.State<*, *>> = when (destination) {
        is LocalPath -> copyGeneric(
            destination = destination,
            sourceOps = safGateway,
            destOps = localGateway,
            strategy = GenericCrossTypeCopyStrategy(),
            options = options,
            onIssue = onIssue,
        )
        is SAFPath -> error("Same-type operations should be handled by native implementation")
        is RawPath -> error("RawPath does not support cross-type copy operations")
    }

    // ========================================================================
    // Cross-Type Move Extensions (Receiver-based dispatch with exhaustiveness)
    // ========================================================================

    @JvmName("localPathMoveCrossType")
    private suspend fun Collection<LocalPath>.moveCrossType(
        destination: APath<*>,
        options: TransferStrategy.Options,
        onIssue: (suspend (PathActionIssue) -> PathActionIssue.Resolution)?
    ): Flow<MoveAction.State<*, *>> = when (destination) {
        is LocalPath -> error("Same-type operations should be handled by native implementation")
        is SAFPath -> moveGeneric(
            destination = destination,
            sourceOps = localGateway,
            destOps = safGateway,
            strategy = GenericCrossTypeMoveStrategy(),
            options = options,
            onIssue = onIssue,
        )
        is RawPath -> error("RawPath does not support cross-type move operations")
    }

    @JvmName("safPathMoveCrossType")
    private suspend fun Collection<SAFPath>.moveCrossType(
        destination: APath<*>,
        options: TransferStrategy.Options,
        onIssue: (suspend (PathActionIssue) -> PathActionIssue.Resolution)?
    ): Flow<MoveAction.State<*, *>> = when (destination) {
        is LocalPath -> moveGeneric(
            destination = destination,
            sourceOps = safGateway,
            destOps = localGateway,
            strategy = GenericCrossTypeMoveStrategy(),
            options = options,
            onIssue = onIssue,
        )
        is SAFPath -> error("Same-type operations should be handled by native implementation")
        is RawPath -> error("RawPath does not support cross-type move operations")
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