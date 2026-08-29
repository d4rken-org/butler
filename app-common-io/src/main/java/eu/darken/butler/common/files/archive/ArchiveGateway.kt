package eu.darken.butler.common.files.archive

import eu.darken.butler.common.files.ArchivePath

import eu.darken.butler.common.coroutine.AppScope
import eu.darken.butler.common.coroutine.DispatcherProvider
import eu.darken.butler.common.debug.logging.Logging.Priority.WARN
import eu.darken.butler.common.debug.logging.asLog
import eu.darken.butler.common.debug.logging.log
import eu.darken.butler.common.debug.logging.logTag
import eu.darken.butler.common.files.APathGateway
import eu.darken.butler.common.files.Existence
import eu.darken.butler.common.files.LookupOptions
import eu.darken.butler.common.files.MoveOutcome
import eu.darken.butler.common.files.actions.CopyAction
import eu.darken.butler.common.files.actions.CreateAction
import eu.darken.butler.common.files.actions.DeleteAction
import eu.darken.butler.common.files.actions.MoveAction
import eu.darken.butler.common.files.actions.PathActionIssue
import eu.darken.butler.common.files.errors.ReadException
import eu.darken.butler.common.files.errors.WriteException
import eu.darken.butler.common.files.metadata.FileSystem
import eu.darken.butler.common.files.metadata.FileType
import eu.darken.butler.common.files.metadata.Ownership
import eu.darken.butler.common.files.metadata.Permissions
import eu.darken.butler.common.ipc.fileHandle
import eu.darken.butler.common.sharedresource.SharedResource
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.plus
import kotlinx.coroutines.withContext
import okio.FileHandle
import java.io.InputStream
import java.io.OutputStream
import java.util.LinkedList
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.time.Instant

/**
 * Read-only gateway for [ArchivePath]s. Thin adapter over [ArchiveService]: listing/lookups are
 * served from the cached entry index, entry reads stream-decompress on demand, seekable access
 * ([file]) materializes the entry to scratch storage once.
 *
 * All mutating operations fail with [WriteException] — archives are never modified in place.
 */
@Singleton
class ArchiveGateway @Inject constructor(
    @AppScope private val appScope: CoroutineScope,
    private val dispatcherProvider: DispatcherProvider,
    private val service: ArchiveService,
) : APathGateway<ArchivePath, ArchivePathLookup> {

    override val sharedResource = SharedResource.createKeepAlive(TAG, appScope + dispatcherProvider.IO)

    private suspend fun <T> runIO(block: suspend () -> T): T =
        withContext(dispatcherProvider.IO) { block() }

    override suspend fun lookup(path: ArchivePath, options: LookupOptions): ArchivePathLookup = runIO {
        val index = service.index(path.container)
        if (path.segments.isEmpty()) {
            val stat = service.statContainer(path.container)
            ArchivePathLookup(
                lookedUp = path,
                fileType = FileType.DIRECTORY,
                size = stat.size,
                modifiedAt = stat.modifiedAt,
                isEncrypted = index.isEncrypted,
            )
        } else {
            val meta = index.entriesBySegments[path.segments]
                ?: throw ReadException("Entry not found in archive", path)
            meta.toLookup(index)
        }
    }

    override suspend fun listFiles(path: ArchivePath): List<ArchivePath> =
        lookupFiles(path, LookupOptions()).map { it.lookedUp }

    override suspend fun lookupFiles(path: ArchivePath, options: LookupOptions): List<ArchivePathLookup> = runIO {
        val index = service.index(path.container)
        if (path.segments.isNotEmpty()) {
            val meta = index.entriesBySegments[path.segments]
                ?: throw ReadException("Entry not found in archive", path)
            if (!meta.isDirectory) throw ReadException("Not a directory", path)
        }
        index.childrenOf(path.segments).map { it.toLookup(index) }
    }

    override suspend fun exists(path: ArchivePath): Boolean = runIO {
        try {
            val index = service.index(path.container)
            path.segments.isEmpty() || index.entriesBySegments.containsKey(path.segments)
        } catch (e: ReadException) {
            false
        }
    }

    /**
     * Indexing reads the container, so its failure alone cannot say whether the entry is there.
     * A deleted archive fails exactly like a corrupt one (indexing stats the container first), so
     * the container is probed before answering: only a container that is provably gone makes the
     * entry ABSENT.
     */
    override suspend fun existsStrict(path: ArchivePath): Existence = runIO {
        val index = try {
            service.index(path.container)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            // Not just ReadException: tar scanning propagates raw IOExceptions from commons-compress.
            return@runIO when (service.containerExistsStrict(path.container)) {
                Existence.ABSENT -> Existence.ABSENT
                else -> {
                    log(TAG, WARN) { "existsStrict($path) could not be answered: ${e.asLog()}" }
                    Existence.UNKNOWN
                }
            }
        }
        when {
            path.segments.isEmpty() -> Existence.PRESENT
            index.entriesBySegments.containsKey(path.segments) -> Existence.PRESENT
            else -> Existence.ABSENT
        }
    }

    override suspend fun walk(
        path: ArchivePath,
        lookupOptions: LookupOptions,
        walkOptions: APathGateway.WalkOptions<ArchivePath, ArchivePathLookup>,
    ): Flow<ArchivePathLookup> = flow {
        val start = lookup(path, lookupOptions)
        if (start.fileType != FileType.DIRECTORY) {
            emit(start)
            return@flow
        }
        val index = service.index(path.container)
        val queue = LinkedList(listOf(start.lookedUp.segments))
        while (queue.isNotEmpty()) {
            val current = queue.removeFirst()
            index.childrenOf(current).forEach { meta ->
                val lookup = meta.toLookup(index)
                val allowed = walkOptions.onFilter?.invoke(lookup) ?: true
                if (!allowed) return@forEach
                if (meta.isDirectory) queue.addFirst(meta.segments)
                emit(lookup)
            }
        }
    }

    override suspend fun du(
        path: ArchivePath,
        options: APathGateway.DuOptions<ArchivePath, ArchivePathLookup>,
    ): Long = runIO {
        val index = service.index(path.container)
        when {
            path.segments.isEmpty() -> index.entriesBySegments.values.sumOf { it.size ?: 0L }
            else -> {
                val meta = index.entriesBySegments[path.segments]
                    ?: throw ReadException("Entry not found in archive", path)
                if (!meta.isDirectory) {
                    meta.size ?: 0L
                } else {
                    index.entriesBySegments.values
                        .filter { it.segments.size > path.segments.size && it.segments.subList(0, path.segments.size) == path.segments }
                        .sumOf { it.size ?: 0L }
                }
            }
        }
    }

    override suspend fun openInputStream(path: ArchivePath): InputStream = service.openEntryStream(path)

    override suspend fun file(path: ArchivePath, readWrite: Boolean): FileHandle {
        if (readWrite) throw WriteException(READ_ONLY_MSG, path)
        val materialized = service.materializeEntry(path)
        return materialized.fileHandle(readWrite = false)
    }

    override suspend fun canRead(path: ArchivePath): Boolean = exists(path)

    override suspend fun canWrite(path: ArchivePath): Boolean = false

    override suspend fun canonicalize(path: ArchivePath): ArchivePath = path

    override suspend fun readSymbolicLink(linkPath: ArchivePath): ArchivePath = runIO {
        val index = service.index(linkPath.container)
        val meta = index.entriesBySegments[linkPath.segments]
            ?: throw ReadException("Entry not found in archive", linkPath)
        resolveLinkTarget(index, meta) ?: throw ReadException("Unresolvable link target", linkPath)
    }

    override suspend fun getFileSystem(path: ArchivePath): FileSystem = FileSystem()

    // region rejected write operations

    override suspend fun createDir(path: ArchivePath, createParents: Boolean): Unit =
        throw WriteException(READ_ONLY_MSG, path)

    override suspend fun createFile(path: ArchivePath, createParents: Boolean): Unit =
        throw WriteException(READ_ONLY_MSG, path)

    override suspend fun createSymlink(linkPath: ArchivePath, targetPath: ArchivePath): Boolean =
        throw WriteException(READ_ONLY_MSG, linkPath)

    override suspend fun delete(path: ArchivePath, recursive: Boolean): Boolean =
        throw WriteException(READ_ONLY_MSG, path)

    override suspend fun move(source: ArchivePath, destination: ArchivePath): MoveOutcome =
        throw WriteException(READ_ONLY_MSG, source)

    override suspend fun openOutputStream(path: ArchivePath, append: Boolean): OutputStream =
        throw WriteException(READ_ONLY_MSG, path)

    override suspend fun setModifiedAt(path: ArchivePath, modifiedAt: Instant): Boolean = false

    override suspend fun setPermissions(path: ArchivePath, permissions: Permissions): Boolean = false

    override suspend fun setOwnership(path: ArchivePath, ownership: Ownership): Boolean = false

    override suspend fun copy(
        sources: Set<ArchivePath>,
        destination: ArchivePath,
        onIssue: (suspend (PathActionIssue) -> PathActionIssue.Resolution)?,
        options: CopyAction.Options,
    ): Flow<CopyAction.State<ArchivePath, ArchivePathLookup, ArchivePath, ArchivePathLookup>> =
        flow { throw WriteException(READ_ONLY_MSG, destination) }

    override suspend fun move(
        sources: Set<ArchivePath>,
        destination: ArchivePath,
        onIssue: (suspend (PathActionIssue) -> PathActionIssue.Resolution)?,
        options: MoveAction.Options,
    ): Flow<MoveAction.State<ArchivePath, ArchivePathLookup, ArchivePath, ArchivePathLookup>> =
        flow { throw WriteException(READ_ONLY_MSG, destination) }

    override suspend fun delete(
        targets: Set<ArchivePath>,
        options: DeleteAction.Options<ArchivePath>,
    ): Flow<DeleteAction.State<ArchivePath, ArchivePathLookup>> =
        flow { throw WriteException(READ_ONLY_MSG, targets.firstOrNull()) }

    override suspend fun create(
        target: ArchivePath,
        type: CreateAction.CreateType,
        options: CreateAction.Options,
    ): Flow<CreateAction.State<ArchivePath, ArchivePathLookup>> =
        flow { throw WriteException(READ_ONLY_MSG, target) }

    // endregion

    private fun ArchiveEntryMeta.toLookup(index: ArchiveIndex): ArchivePathLookup = ArchivePathLookup(
        lookedUp = ArchivePath(index.container, segments),
        fileType = when {
            isDirectory -> FileType.DIRECTORY
            isSymlink -> FileType.SYMBOLIC_LINK
            else -> FileType.FILE
        },
        size = size,
        modifiedAt = modifiedAt,
        target = resolveLinkTarget(index, this),
        isEncrypted = isEncrypted,
    )

    /**
     * Resolves a tar symlink target to an [ArchivePath] when it is relative and stays inside
     * the archive; absolute or escaping targets resolve to null (shown as broken, never followed
     * outside the archive).
     */
    private fun resolveLinkTarget(index: ArchiveIndex, meta: ArchiveEntryMeta): ArchivePath? {
        val target = meta.linkTarget ?: return null
        if (target.startsWith("/")) return null
        val resolved = meta.segments.dropLast(1).toMutableList()
        target.split('/').forEach { segment ->
            when (segment) {
                "", "." -> Unit
                ".." -> if (resolved.isNotEmpty()) resolved.removeAt(resolved.size - 1) else return null
                else -> resolved.add(segment)
            }
        }
        if (resolved.isEmpty()) return null
        if (!index.entriesBySegments.containsKey(resolved)) return null
        return ArchivePath(index.container, resolved)
    }

    companion object {
        val TAG = logTag("Gateway", "Archive")
        private const val READ_ONLY_MSG = "Archives are read-only"
    }
}
