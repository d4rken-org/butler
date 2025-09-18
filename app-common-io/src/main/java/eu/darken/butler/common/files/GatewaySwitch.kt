package eu.darken.butler.common.files

import eu.darken.butler.common.coroutine.AppScope
import eu.darken.butler.common.coroutine.DispatcherProvider
import eu.darken.butler.common.debug.logging.Logging.Priority.*
import eu.darken.butler.common.debug.logging.asLog
import eu.darken.butler.common.debug.logging.log
import eu.darken.butler.common.debug.logging.logTag
import eu.darken.butler.common.files.errors.ReadException
import eu.darken.butler.common.files.local.LocalGateway
import eu.darken.butler.common.files.metadata.Ownership
import eu.darken.butler.common.files.metadata.Permissions
import eu.darken.butler.common.files.operations.CopyOperation
import eu.darken.butler.common.files.operations.MoveOperation
import eu.darken.butler.common.files.saf.SAFGateway
import eu.darken.butler.common.sharedresource.SharedResource
import eu.darken.butler.common.sharedresource.adoptChildResource
import eu.darken.butler.common.storage.PathMapper
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.plus
import okio.FileHandle
import okio.IOException
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
) : APathGateway<APath, APathLookup<APath>, APathLookupExtended<APath>> {

    private suspend fun <T : APath, R> useGateway(
        path: T,
        action: suspend APathGateway<T, APathLookup<T>, APathLookupExtended<T>>.() -> R
    ): R {
        @Suppress("UNCHECKED_CAST")
        val targetGateway = getGateway(path) as APathGateway<T, APathLookup<T>, APathLookupExtended<T>>
        return action(targetGateway)
    }

    private suspend fun resolveGatewayType(path: APath): APathGateway<*, *, *> {
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

    suspend fun getGateway(type: APath): APathGateway<*, *, *> {
        return resolveGatewayType(type)
    }

    override val sharedResource = SharedResource.createKeepAlive(TAG, appScope + dispatcherProvider.IO)

    override suspend fun createDir(path: APath) {
        return useGateway(path) { createDir(path) }
    }

    override suspend fun createFile(path: APath) {
        return useGateway(path) { createFile(path) }
    }

    override suspend fun lookup(path: APath): APathLookup<APath> {
        return lookup(path, Type.CURRENT)
    }

    suspend fun lookup(path: APath, type: Type): APathLookup<APath> {
        val mapped = path.toTargetType(type)
        return try {
            useGateway(mapped) { lookup(mapped) }
        } catch (oge: ReadException) {
            if (type != Type.AUTO) throw oge
            log(TAG, WARN) { "lookup(...): Original lookup failed, try alternative: ${oge.asLog()}" }

            val fallback = path.toAlternative()
            try {
                useGateway(fallback) { lookup(fallback) }
            } catch (e: ReadException) {
                log(TAG, WARN) { "lookup(...): Alternative lookup failed either: ${e.asLog()}" }
                throw oge
            }
        }
    }

    override suspend fun lookupFiles(path: APath): Collection<APathLookup<APath>> {
        return lookupFiles(path, Type.CURRENT)
    }

    suspend fun lookupFiles(path: APath, type: Type): Collection<APathLookup<APath>> {
        val mapped = path.toTargetType(type)
        return try {
            useGateway(mapped) { lookupFiles(mapped) }
        } catch (oge: ReadException) {
            if (type != Type.AUTO) throw oge
            log(TAG, WARN) { "lookupFiles(...): Original lookup failed, try alternative: ${oge.asLog()}" }

            val fallback = path.toAlternative()
            try {
                useGateway(fallback) { lookupFiles(fallback) }
            } catch (e: ReadException) {
                log(TAG, WARN) { "lookupFiles(...): Alternative lookup failed either: ${e.asLog()}" }
                throw oge
            }
        }
    }

    override suspend fun lookupFilesExtended(path: APath): Collection<APathLookupExtended<APath>> {
        return lookupFilesExtended(path, Type.CURRENT)
    }

    suspend fun lookupFilesExtended(path: APath, type: Type): Collection<APathLookupExtended<APath>> {
        val mapped = path.toTargetType(type)
        return try {
            useGateway(mapped) { lookupFilesExtended(mapped) }
        } catch (oge: ReadException) {
            if (type != Type.AUTO) throw oge
            log(TAG, WARN) { "lookupFilesExtended(...): Original lookup failed, try alternative: ${oge.asLog()}" }

            val fallback = path.toAlternative()
            try {
                useGateway(fallback) { lookupFilesExtended(fallback) }
            } catch (e: ReadException) {
                log(TAG, WARN) { "lookupFilesExtended(...): Alternative lookup failed either: ${e.asLog()}" }
                throw oge
            }
        }
    }

    override suspend fun walk(
        path: APath,
        options: APathGateway.WalkOptions<APath, APathLookup<APath>>
    ): Flow<APathLookup<APath>> {
        return useGateway(path) { walk(path, options) }
    }


    override suspend fun du(
        path: APath,
        options: APathGateway.DuOptions<APath, APathLookup<APath>>
    ): Long {
        return useGateway(path) { du(path, options) }
    }

    override suspend fun listFiles(path: APath): Collection<APath> {
        return useGateway(path) { listFiles(path) }
    }

    override suspend fun exists(path: APath): Boolean {
        return exists(path, Type.CURRENT)
    }

    suspend fun exists(path: APath, type: Type): Boolean {
        val mapped = path.toTargetType(type)
        return try {
            useGateway(mapped) { exists(mapped) }
        } catch (e: ReadException) {
            if (type != Type.AUTO) throw e

            val fallback = path.toAlternative()
            useGateway(fallback) { exists(fallback) }
        }
    }

    override suspend fun canWrite(path: APath): Boolean {
        return useGateway(path) { canWrite(path) }
    }

    override suspend fun canRead(path: APath): Boolean {
        return useGateway(path) { canRead(path) }
    }

    override suspend fun file(path: APath, readWrite: Boolean): FileHandle {
        return useGateway(path) { file(path, readWrite) }
    }

    override suspend fun delete(path: APath, recursive: Boolean) {
        return useGateway(path) { delete(path, recursive = recursive) }
    }

    override suspend fun createSymlink(linkPath: APath, targetPath: APath): Boolean {
        return useGateway(linkPath) { createSymlink(linkPath, targetPath) }
    }

    override suspend fun setModifiedAt(path: APath, modifiedAt: Instant): Boolean {
        return useGateway(path) { setModifiedAt(path, modifiedAt) }
    }

    override suspend fun setPermissions(path: APath, permissions: Permissions): Boolean {
        return useGateway(path) { setPermissions(path, permissions) }
    }

    override suspend fun setOwnership(path: APath, ownership: Ownership): Boolean {
        return useGateway(path) { setOwnership(path, ownership) }
    }

    private suspend fun APath.toTargetType(type: Type): APath = when (type) {
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

    private suspend fun APath.toAlternative(): APath = when (this) {
        is LocalPath -> mapper.toSAFPath(this) ?: throw ReadException("Can't map to SAF", this)
        is SAFPath -> mapper.toLocalPath(this) ?: throw ReadException("Can't map to LOCAL", this)
        is RawPath -> throw UnsupportedOperationException("Alternative mapping for RAW not available")
    }

    override suspend fun copy(
        source: APath,
        destination: APath,
        options: CopyOperation.Options,
    ): Flow<CopyOperation.Result> = flow {
        when {
            // Same gateway type - use native implementation
            source::class == destination::class -> {
                useGateway(source) {
                    copy(source, destination, options)
                }.collect { emit(it) }
            }
            // Cross-gateway copy
            else -> {
                performCrossGatewayCopy(source, destination, options)
                    .collect { emit(it) }
            }
        }
    }

    override suspend fun move(
        source: APath,
        destination: APath,
        options: MoveOperation.Options
    ): Flow<MoveOperation.Result> = flow {
        when {
            // Same gateway type - try atomic move first
            source::class == destination::class -> {
                useGateway(source) {
                    move(source, destination, options)
                }.collect { emit(it) }
            }
            // Cross-gateway move: copy then delete
            else -> {
                performCrossGatewayMove(source, destination, options)
                    .collect { emit(it) }
            }
        }
    }

    private suspend fun performCrossGatewayCopy(
        source: APath,
        target: APath,
        options: CopyOperation.Options
    ): Flow<CopyOperation.Result> = flow {
        // TODO: Implement cross-gateway copy
        // - Handle file handle transfers between different gateway types
        // - Emit progress updates via options.onProgress
        // - Handle conflicts via options.onIssue
        throw NotImplementedError("TODO: Cross-gateway copy implementation")
    }

    private suspend fun performCrossGatewayMove(
        source: APath,
        target: APath,
        options: MoveOperation.Options
    ): Flow<MoveOperation.Result> = flow {
        // TODO: Implement cross-gateway move (copy + delete)
        // - Copy from source to target using cross-gateway copy
        // - Delete source after successful copy
        // - Handle conflicts via options.onIssue
        throw NotImplementedError("TODO: Cross-gateway move implementation")
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