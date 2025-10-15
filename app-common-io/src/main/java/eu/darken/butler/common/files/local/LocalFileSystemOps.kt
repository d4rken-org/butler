package eu.darken.butler.common.files.local

import android.os.StatFs
import android.system.Os
import android.system.StructStat
import eu.darken.butler.common.debug.logging.Logging.Priority.*
import eu.darken.butler.common.debug.logging.asLog
import eu.darken.butler.common.debug.logging.log
import eu.darken.butler.common.debug.logging.logTag
import eu.darken.butler.common.files.LocalPath
import eu.darken.butler.common.files.core.local.readLink
import eu.darken.butler.common.files.errors.PathAlreadyExistsException
import eu.darken.butler.common.files.errors.ReadException
import eu.darken.butler.common.files.errors.WriteException
import eu.darken.butler.common.files.extensions.toFile
import eu.darken.butler.common.files.metadata.FileSystem
import eu.darken.butler.common.files.metadata.FileType
import eu.darken.butler.common.files.metadata.Ownership
import eu.darken.butler.common.files.metadata.Permissions
import eu.darken.butler.common.files.operations.FileSystemOps
import eu.darken.butler.common.ipc.fileHandle
import eu.darken.butler.common.pkgs.pkgops.LibcoreTool
import okio.FileHandle
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.nio.file.FileAlreadyExistsException
import java.nio.file.FileVisitResult
import java.nio.file.Files
import java.nio.file.NoSuchFileException
import java.nio.file.Path
import java.nio.file.SimpleFileVisitor
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
    private val libcoreTool: LibcoreTool,
) : FileSystemOps<LocalPath, LocalPathLookup, LocalPathLookupExtended> {

    override suspend fun lookup(path: LocalPath): LocalPathLookup = try {
        // In cases like reading "/" we can still get the file type for restricted items
        val fileType: FileType =
            path.file.getAPathFileType() ?: throw ReadException("Does not exist or can't be read", path)

        var size = -1L
        var modifiedAt = Instant.DISTANT_PAST
        var target: LocalPath? = null
        val errors = mutableListOf<String>()

        try {
            size = path.file.length()
            modifiedAt = Instant.fromEpochMilliseconds(path.file.lastModified())
        } catch (e: Exception) {
            errors.add("Attributes: ${e.message}")
        }

        if (fileType == FileType.SYMBOLIC_LINK) {
            try {
                target = path.file.readLink()?.let { LocalPath.build(it) }
            } catch (e: Exception) {
                errors.add("Link target: ${e.message}")
            }
        }

        LocalPathLookup(
            lookedUp = path,
            fileType = fileType,
            size = size,
            modifiedAt = modifiedAt,
            target = target,
            error = errors.joinToString("; ").ifEmpty { null }
        )
    } catch (e: Exception) {
        throw ReadException(path = path, cause = e)
    }

    override suspend fun lookupExtended(path: LocalPath): LocalPathLookupExtended = try {
        val basicLookup = lookup(path)

        val fstat: StructStat? = try {
            Os.lstat(path.file.path)
        } catch (e: Exception) {
            log(LocalGateway.TAG, WARN) { "fstat failed on $this: ${e.asLog()}" }
            null
        }

        val ownership = fstat?.let {
            val uid = it.st_uid
            val gid = it.st_gid

            val userName: String? = libcoreTool.getNameForUid(uid)
            val groupName: String? = libcoreTool.getNameForGid(gid)

            // TODO use Files.readAttributes as fallback?

            Ownership(uid, gid, userName, groupName)
        }

        val basicAttributes = try {
            Files.readAttributes(
                path.toNioPath(),
                BasicFileAttributes::class.java
            )
        } catch (e: Exception) {
            log(LocalGateway.TAG, WARN) { "BasicFileAttributes failed on $this: ${e.asLog()}" }
            null
        }

        val permissions = fstat?.let {
            Permissions(mode = it.st_mode)
        }

        LocalPathLookupExtended(
            lookup = basicLookup,
            ownership = ownership,
            permissions = permissions,
            createdAt = basicAttributes?.let { Instant.fromEpochMilliseconds(it.creationTime().toMillis()) },
        )
    } catch (e: Exception) {
        throw ReadException(path = path, cause = e)
    }

    override suspend fun listFiles(path: LocalPath): List<LocalPath> {
        return try {
            Files.newDirectoryStream(path.toNioPath()).use { ds ->
                ds.map { LocalPath.build(it.toFile()) }.toList()
            }
        } catch (e: NoSuchFileException) {
            throw ReadException("Directory does not exist", path, e)
        } catch (e: IOException) {
            throw ReadException(path = path, cause = e)
        }
    }

    override suspend fun exists(path: LocalPath): Boolean {
        return Files.exists(path.toNioPath())
    }

    override suspend fun delete(path: LocalPath, recursive: Boolean): Boolean {
        return try {
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
        } catch (e: NoSuchFileException) {
            false // File doesn't exist - return false (not an error)
        } catch (e: IOException) {
            throw WriteException(path = path, cause = e)
        }
    }

    override suspend fun createDir(path: LocalPath) {
        try {
            Files.createDirectories(path.toNioPath())
        } catch (e: FileAlreadyExistsException) {
            throw PathAlreadyExistsException(
                message = "Path exists but is not a directory",
                path = path,
                cause = e
            )
        } catch (e: IOException) {
            throw WriteException(path = path, cause = e)
        }
    }

    override suspend fun createFile(path: LocalPath) {
        try {
            // Ensure parent exists
            path.file.parentFile?.let { parent ->
                if (!parent.exists()) {
                    parent.mkdirs()
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

    override suspend fun openInputStream(path: LocalPath): InputStream {
        return try {
            Files.newInputStream(path.toNioPath())
        } catch (e: IOException) {
            throw ReadException(path = path, cause = e)
        }
    }

    override suspend fun openOutputStream(path: LocalPath, append: Boolean): OutputStream {
        return try {
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
        } catch (e: IOException) {
            throw WriteException(path = path, cause = e)
        }
    }

    override suspend fun setModifiedAt(path: LocalPath, modifiedAt: Instant): Boolean {
        return try {
            path.file.setLastModified(modifiedAt.toEpochMilliseconds())
        } catch (e: Exception) {
            false
        }
    }

    override suspend fun setPermissions(path: LocalPath, permissions: Permissions): Boolean {
        return try {
            path.file.setPermissions(permissions)
        } catch (e: Exception) {
            false
        }
    }

    override suspend fun setOwnership(path: LocalPath, ownership: Ownership): Boolean {
        return try {
            path.file.setOwnership(ownership)
        } catch (e: Exception) {
            false
        }
    }

    override suspend fun createSymlink(linkPath: LocalPath, targetPath: LocalPath): Boolean {
        return try {
            // targetPath can be absolute or relative
            // If relative, it will be resolved relative to linkPath's parent
            val linkNioPath = linkPath.toNioPath()
            val targetNioPath = targetPath.toNioPath()

            Files.createSymbolicLink(linkNioPath, targetNioPath)
            true
        } catch (e: IOException) {
            throw WriteException(path = linkPath, cause = e)
        }
    }

    override suspend fun canRead(path: LocalPath): Boolean {
        return try {
            path.file.canRead()
        } catch (e: Exception) {
            false
        }
    }

    override suspend fun canWrite(path: LocalPath): Boolean {
        return try {
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
