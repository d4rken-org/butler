package eu.darken.butler.common.files.local

import android.os.StatFs
import android.system.Os
import android.system.OsConstants
import android.system.StructStat
import eu.darken.butler.common.debug.logging.Logging.Priority.*
import eu.darken.butler.common.debug.logging.asLog
import eu.darken.butler.common.debug.logging.log
import eu.darken.butler.common.debug.logging.logTag
import eu.darken.butler.common.files.Existence
import eu.darken.butler.common.files.FileSystemOps
import eu.darken.butler.common.files.LocalPath
import eu.darken.butler.common.files.LookupOptions
import eu.darken.butler.common.files.MoveOutcome
import eu.darken.butler.common.files.errors.PathAlreadyExistsException
import eu.darken.butler.common.files.errors.ReadException
import eu.darken.butler.common.files.errors.WriteException
import eu.darken.butler.common.files.extensions.toFile
import eu.darken.butler.common.files.metadata.FileSystem
import eu.darken.butler.common.files.metadata.FileType
import eu.darken.butler.common.files.metadata.Ownership
import eu.darken.butler.common.files.metadata.OwnershipResolver
import eu.darken.butler.common.files.metadata.Permissions
import eu.darken.butler.common.ipc.fileHandle
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import okio.FileHandle
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.nio.file.AtomicMoveNotSupportedException
import java.nio.file.FileAlreadyExistsException
import java.nio.file.FileVisitResult
import java.nio.file.Files
import java.nio.file.LinkOption
import java.nio.file.NoSuchFileException
import java.nio.file.Path
import java.nio.file.SimpleFileVisitor
import java.nio.file.StandardCopyOption
import java.nio.file.StandardOpenOption
import java.nio.file.attribute.BasicFileAttributes
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.time.Instant

/**
 * FileSystemOps implementation for LocalPath using java.nio.Files API.
 *
 * This class wraps existing LocalPath extension functions and java.nio.Files operations
 * to provide a testable abstraction layer. It uses:
 * - `LocalPath.performLookup()` extension for metadata lookup
 * - `java.nio.file.Files` for file system operations
 * - `LocalPath.toNioPath()` to convert to NIO Path
 *
 * ## No External Dependencies
 *
 * Unlike SAFFileSystemOps which needs Context/ContentResolver, LocalFileSystemOps has
 * no external dependencies - it only uses the LocalPath itself and java.nio APIs.
 * This makes it simple and fast.
 *
 * ## Usage
 *
 * ```kotlin
 * val fileSystemOps = LocalFileSystemOps()
 * val lookup = fileSystemOps.lookup(somePath)
 * val children = fileSystemOps.listFiles(someDirectory)
 * ```
 */
@Singleton
class LocalFileSystemOps @Inject constructor(
    private val ownershipResolver: OwnershipResolver,
) : FileSystemOps<LocalPath, LocalPathLookup> {

    override suspend fun lookup(path: LocalPath, options: LookupOptions): LocalPathLookup = try {
        val javaFile = path.toFile()
        val nioPath = path.toNioPath()

        val errors = mutableListOf<String>()

        val fstat: StructStat? by lazy {
            try {
                Os.lstat(path.file.path)
            } catch (e: Exception) {
                log(TAG, VERBOSE) { "fstat failed on $path: $e" }
                null
            }
        }

        val fileType: FileType = when {
            // Order matters!
            try {
                Files.isSymbolicLink(nioPath)
            } catch (e: Exception) {
                log(TAG, WARN) { "Failed to check 'isSymbolicLink' on $this: $e" }
                fstat?.let { OsConstants.S_ISLNK(it.st_mode) } ?: false
            } -> FileType.SYMBOLIC_LINK

            try {
                javaFile.isDirectory
            } catch (e: Exception) {
                log(TAG, WARN) { "Failed to check 'isDirectory' on $this: $e" }
                false
            } -> FileType.DIRECTORY

            try {
                javaFile.isFile
            } catch (e: Exception) {
                log(TAG, WARN) { "Failed to check 'isFile' on $this: $e" }
                false
            } -> FileType.FILE

            try {
                javaFile.exists()
            } catch (e: Exception) {
                log(TAG, WARN) { "Failed to check 'exists' on $this: $e" }
                false
            } -> FileType.UNKNOWN

            options.fallbackToUnknown -> {
                errors.add("File does not exist or cannot be accessed")
                FileType.UNKNOWN
            }

            else -> throw ReadException("Does not exist or can't be read :(", path)
        }

        var size: Long? = null
        if (options.fetchSize) {
            try {
                size = if (fileType == FileType.SYMBOLIC_LINK) {
                    // File.length() follows the link and reports the TARGET's size; a symlink must report
                    // its own node size. Os.lstat (NOFOLLOW) provides it. If lstat is unavailable, leave
                    // size null and record an error rather than silently reporting the target's size.
                    fstat?.st_size ?: run {
                        errors.add("Size: lstat unavailable for symlink")
                        null
                    }
                } else {
                    path.file.length()
                }
            } catch (e: Exception) {
                errors.add("Size: ${e.message}")
            }
        }

        var modifiedAt: Instant? = null
        if (options.fetchModifiedAt) {
            try {
                modifiedAt = Instant.fromEpochMilliseconds(path.file.lastModified())
            } catch (e: Exception) {
                errors.add("ModifiedAt: ${e.message}")
            }
        }

        var target: LocalPath? = null
        if (fileType == FileType.SYMBOLIC_LINK) {
            try {
                // Reuse readSymbolicLink(): it resolves a relative target against the link's parent and
                // normalizes it. Raw readLink() returned the stored target verbatim, so a relative
                // "../x" became a bogus absolute "/../x" when passed to LocalPath.build().
                target = readSymbolicLink(path)
            } catch (e: Exception) {
                errors.add("Link target: ${e.message}")
            }
        }

        var ownership: Ownership? = null
        if (options.fetchOwnership && fstat != null) {
            ownership = ownershipResolver.resolve(
                userId = fstat!!.st_uid,
                groupId = fstat!!.st_gid,
            )
        }

        var permissions: Permissions? = null
        if (options.fetchPermissions && fstat != null) {
            permissions = Permissions(mode = fstat!!.st_mode)
        }

        var createdAt: Instant? = null
        if (options.fetchCreatedAt) {
            val basicAttributes = try {
                Files.readAttributes(path.toNioPath(), BasicFileAttributes::class.java)
            } catch (e: Exception) {
                log(TAG, VERBOSE) { "BasicFileAttributes failed on $path: $e" }
                null
            }

            createdAt = basicAttributes?.let { Instant.fromEpochMilliseconds(it.creationTime().toMillis()) }
        }

        LocalPathLookup(
            lookedUp = path,
            fileType = fileType,
            size = size,
            modifiedAt = modifiedAt,
            target = target,
            ownership = ownership,
            permissions = permissions,
            createdAt = createdAt,
            error = errors.takeIf { it.isNotEmpty() }?.joinToString("; "),
        )
    } catch (e: Exception) {
        throw ReadException(path = path, cause = e)
    }

    override suspend fun listFiles(path: LocalPath): List<LocalPath> = try {
        Files.newDirectoryStream(path.toNioPath()).use { ds ->
            // Check for cancellation periodically to avoid blocking on large directories
            ds.mapIndexed { index, nioPath ->
                if (index % 50 == 0) currentCoroutineContext().ensureActive()
                LocalPath.build(nioPath.toFile())
            }.toList()
        }
    } catch (e: NoSuchFileException) {
        throw ReadException("Directory does not exist", path, e)
    } catch (e: IOException) {
        throw ReadException(path = path, cause = e)
    }

    override suspend fun lookupFiles(path: LocalPath, options: LookupOptions): List<LocalPathLookup> =
        listFiles(path).mapNotNull { child ->
            try {
                lookup(child, options)
            } catch (e: kotlin.coroutines.cancellation.CancellationException) {
                throw e
            } catch (e: Exception) {
                when {
                    !options.continueOnError -> throw e
                    // Vanished between listing and lookup — benign, not a directory failure
                    runCatching { exists(child) }.getOrDefault(true) == false -> {
                        log(TAG, VERBOSE) { "lookupFiles: child vanished during listing: $child" }
                        null
                    }
                    // Still exists but can't be read: keep it visible as UNKNOWN so callers can
                    // surface the access problem instead of the entry silently disappearing
                    else -> {
                        log(TAG, WARN) { "lookupFiles: unreadable child $child: $e" }
                        LocalPathLookup.unknown(child, e.message)
                    }
                }
            }
        }

    override suspend fun exists(path: LocalPath): Boolean = try {
        Files.exists(path.toNioPath(), LinkOption.NOFOLLOW_LINKS)
    } catch (e: Exception) {
        throw ReadException(path = path, cause = e)
    }

    /**
     * `Files.exists` has no error channel - a stat it was not allowed to run reads as "not there".
     * Reading the attributes instead makes the failure available for classification.
     */
    override suspend fun existsStrict(path: LocalPath): Existence = try {
        Files.readAttributes(path.toNioPath(), BasicFileAttributes::class.java, LinkOption.NOFOLLOW_LINKS)
        Existence.PRESENT
    } catch (e: CancellationException) {
        throw e
    } catch (e: Exception) {
        classifyExistence(path, e)
    }

    internal fun classifyExistence(path: LocalPath, error: Exception): Existence = when (error) {
        is NoSuchFileException -> Existence.ABSENT
        else -> {
            log(TAG, WARN) { "existsStrict($path) could not be answered: ${error.asLog()}" }
            Existence.UNKNOWN
        }
    }

    override suspend fun delete(path: LocalPath, recursive: Boolean): Boolean = try {
        val nioPath = path.toNioPath()

        if (recursive) {
            // Use Files.walkFileTree with a visitor that deletes in post-order
            Files.walkFileTree(nioPath, object : SimpleFileVisitor<Path>() {
                override fun visitFile(file: Path, attrs: BasicFileAttributes): FileVisitResult {
                    Files.delete(file)
                    return FileVisitResult.CONTINUE
                }

                override fun postVisitDirectory(dir: Path, exc: IOException?): FileVisitResult {
                    if (exc != null) {
                        throw exc
                    }
                    Files.delete(dir)
                    return FileVisitResult.CONTINUE
                }
            })
            true
        } else {
            Files.delete(nioPath)
            true
        }
    } catch (e: Exception) {
        throw WriteException(path = path, cause = e)
    }

    override suspend fun createDir(path: LocalPath, createParents: Boolean) {
        try {
            if (createParents) {
                Files.createDirectories(path.toNioPath())
            } else {
                Files.createDirectory(path.toNioPath())
            }
        } catch (e: FileAlreadyExistsException) {
            // Check if it's a directory (idempotent) or a file (error)
            if (path.file.isDirectory) {
                // Directory already exists - this is OK, createDir is idempotent
                return
            } else {
                // Path exists but is not a directory - this is an error
                throw PathAlreadyExistsException(
                    message = "Path exists but is not a directory",
                    path = path,
                    cause = e
                )
            }
        } catch (e: IOException) {
            throw WriteException(path = path, cause = e)
        }
    }

    override suspend fun createFile(path: LocalPath, createParents: Boolean) {
        try {
            if (createParents) {
                // Ensure parent exists
                path.file.parentFile?.let { parent ->
                    if (!parent.exists()) {
                        parent.mkdirs()
                    }
                }
            }

            Files.createFile(path.toNioPath())
        } catch (e: FileAlreadyExistsException) {
            throw PathAlreadyExistsException(
                path = path,
                cause = e
            )
        } catch (e: IOException) {
            throw WriteException(path = path, cause = e)
        }
    }

    override suspend fun openInputStream(path: LocalPath): InputStream = try {
        Files.newInputStream(path.toNioPath())
    } catch (e: IOException) {
        throw ReadException(path = path, cause = e)
    }

    override suspend fun openOutputStream(path: LocalPath, append: Boolean): OutputStream = try {
        if (append) {
            Files.newOutputStream(
                path.toNioPath(),
                StandardOpenOption.CREATE,
                StandardOpenOption.APPEND
            )
        } else {
            Files.newOutputStream(
                path.toNioPath(),
                StandardOpenOption.CREATE,
                StandardOpenOption.TRUNCATE_EXISTING
            )
        }
    } catch (e: FileAlreadyExistsException) {
        throw PathAlreadyExistsException(path = path, cause = e)
    } catch (e: IOException) {
        throw WriteException(path = path, cause = e)
    }

    override suspend fun setModifiedAt(path: LocalPath, modifiedAt: Instant): Boolean {
        return try {
            path.file.setLastModified(modifiedAt.toEpochMilliseconds())
        } catch (e: Exception) {
            false
        }
    }

    override suspend fun setPermissions(path: LocalPath, permissions: Permissions): Boolean = try {
        Os.chmod(path.path, permissions.mode)
        true
    } catch (e: Exception) {
        log(TAG, VERBOSE) { "setPermissions $permissions failed on $path: $e" }
        false
    }

    override suspend fun setOwnership(path: LocalPath, ownership: Ownership): Boolean = try {
        Os.lchown(path.path, ownership.userId.toInt(), ownership.groupId.toInt())
        true
    } catch (e: Exception) {
        log(TAG, VERBOSE) { "setOwnership $ownership failed on $path: $e" }
        false
    }

    override suspend fun createSymlink(linkPath: LocalPath, targetPath: LocalPath): Boolean = try {
        // targetPath can be absolute or relative
        // If relative, it will be resolved relative to linkPath's parent
        val linkNioPath = linkPath.toNioPath()
        val targetNioPath = targetPath.toNioPath()

        Files.createSymbolicLink(linkNioPath, targetNioPath)
        true
    } catch (e: FileAlreadyExistsException) {
        throw PathAlreadyExistsException(message = "Symlink already exists", path = linkPath, cause = e)
    } catch (e: IOException) {
        throw WriteException(path = linkPath, cause = e)
    }

    override suspend fun readSymbolicLink(linkPath: LocalPath): LocalPath = try {
        val targetNioPath = Files.readSymbolicLink(linkPath.toNioPath())

        // Resolve relative paths to absolute (relative to link's parent directory)
        val absoluteTargetPath = if (targetNioPath.isAbsolute) {
            targetNioPath
        } else {
            // Resolve relative to the symlink's parent directory
            linkPath.toNioPath().parent.resolve(targetNioPath).normalize()
        }

        LocalPath.build(absoluteTargetPath.toFile())
    } catch (e: IOException) {
        throw ReadException(path = linkPath, cause = e)
    }

    override suspend fun canonicalize(path: LocalPath): LocalPath = try {
        // toRealPath() resolves the entire symlink chain and normalizes; requires the path to exist.
        LocalPath.build(path.toNioPath().toRealPath().toFile())
    } catch (e: IOException) {
        throw ReadException(path = path, cause = e)
    }

    override suspend fun move(source: LocalPath, destination: LocalPath): MoveOutcome = try {
        // ATOMIC_MOVE maps to rename(2), which silently replaces an existing destination —
        // refuse instead (mirrors SAF) so callers resolve conflicts deliberately. Best-effort:
        // NIO has no NOREPLACE+ATOMIC combination, so a racing creation can still be replaced.
        if (source != destination && Files.exists(destination.toNioPath(), LinkOption.NOFOLLOW_LINKS)) {
            MoveOutcome.NotSupported("Destination already exists: ${destination.path}")
        } else {
            Files.move(
                source.toNioPath(),
                destination.toNioPath(),
                StandardCopyOption.ATOMIC_MOVE,
                LinkOption.NOFOLLOW_LINKS
            )
            MoveOutcome.Moved
        }
    } catch (e: AtomicMoveNotSupportedException) {
        // Files.move guarantees nothing was mutated in this case
        MoveOutcome.NotSupported("AtomicMoveNotSupportedException: ${e.message}")
    } catch (e: IOException) {
        throw WriteException(path = source, cause = e)
    }

    override suspend fun canRead(path: LocalPath): Boolean = try {
        if (path.toFile().isDirectory) {
            path.toFile().canRead()
        } else {
            // canRead() may return true, while SELinux blocks open
            // type=1400 audit(0.0:12576): avc: denied { open } for path="/data/data/alinktests/subdir/symtarget" dev="sda45" ino=2754227 scontext=u:r:untrusted_app_27:s0:c100,c257,c512,c768 tcontext=u:object_r:system_data_file:s0 tclass=file permissive=0
            path.toFile().reader().use { it.read() }
            true
        }
    } catch (e: Exception) {
        false
    }

    override suspend fun canWrite(path: LocalPath): Boolean = try {
        val file = path.file
        if (file.exists()) {
            file.canWrite()
        } else {
            // Check parent directory for write permission
            file.parentFile?.canWrite() ?: false
        }
    } catch (e: Exception) {
        false
    }

    suspend fun du(path: LocalPath): Long {
        return path.toFile().walkTopDown().map { it.length() }.sum()
    }

    override suspend fun file(path: LocalPath, readWrite: Boolean): FileHandle {
        return path.toFile().fileHandle(readWrite)
    }

    override suspend fun getFileSystem(path: LocalPath): FileSystem {
        val statFs = try {
            StatFs(path.path)
        } catch (e: Exception) {
            log(TAG, ERROR) { "getInfo(): Failed on $path: ${e.asLog()}" }
            null
        }
        return FileSystem(
            freeSpace = statFs?.availableBytes,
            totalSpace = statFs?.totalBytes,
        )
    }

    companion object {
        private val TAG = logTag("Gateway", "Local", "FileSystemOps")
    }
}
