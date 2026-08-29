package eu.darken.butler.common.files.operations

import eu.darken.butler.common.files.APath
import eu.darken.butler.common.files.APathLookup
import eu.darken.butler.common.files.Existence
import eu.darken.butler.common.files.FileSystemOps
import eu.darken.butler.common.files.LookupOptions
import eu.darken.butler.common.files.MoveOutcome
import eu.darken.butler.common.files.errors.PathAlreadyExistsException
import eu.darken.butler.common.files.errors.ReadException
import eu.darken.butler.common.files.metadata.FileType
import eu.darken.butler.common.files.metadata.Ownership
import eu.darken.butler.common.files.metadata.Permissions
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.InputStream
import java.io.OutputStream
import java.nio.file.NoSuchFileException
import kotlin.time.Instant

/**
 * In-memory mock implementation of FileSystemOps for testing.
 *
 * This class enables testing generic path operations (Copy/Move/Delete) without:
 * - Android framework (no Context, ContentResolver)
 * - Real file system access
 * - Slow I/O operations
 *
 * All operations are performed on in-memory data structures, making tests:
 * - **Fast**: No disk I/O
 * - **Reliable**: No file system state dependencies
 * - **Controllable**: Full control over mock behavior
 * - **Platform-independent**: Works in unit tests without Android
 *
 * ## Usage Example
 *
 * ```kotlin
 * @Test
 * fun `copy file preserves content`() = runTest {
 *     val mockOps = MockFileSystemOps<LocalPath, LocalPathLookup> { path, type, size ->
 *         LocalPathLookup(
 *             lookedUp = path,
 *             fileType = type,
 *             size = size,
 *             // ... other fields
 *         )
 *     }
 *
 *     // Setup mock files
 *     mockOps.addMockFile("/source.txt", "Hello World".toByteArray())
 *     mockOps.addMockDir("/dest")
 *
 *     // Run operation (no Android framework needed!)
 *     val result = setOf(LocalPath.build("/source.txt")).copyGeneric(
 *         destination = LocalPath.build("/dest"),
 *         fileSystemOps = mockOps,
 *         strategy = mockStrategy
 *     )
 *
 *     // Verify
 *     mockOps.getFileContent("/dest/source.txt") shouldBe "Hello World".toByteArray()
 * }
 * ```
 *
 * @param P The path type (LocalPath, SAFPath, etc.)
 * @param PL The path lookup type (LocalPathLookup, SAFPathLookup, etc.)
 * @param lookupFactory Factory function to create path lookups from mock data
 */
open class MockFileSystemOps<P : APath<P>, PL : APathLookup<P>>(
    private val lookupFactory: (path: P, type: FileType, size: Long?, modifiedAt: Instant?, permissions: Permissions?, ownership: Ownership?, createdAt: Instant?) -> PL
) : FileSystemOps<P, PL> {

    /**
     * Mock file entry in the in-memory file system.
     */
    data class MockFile(
        val type: FileType,
        var size: Long?,
        val content: ByteArray = ByteArray(0),
        val children: MutableList<String> = mutableListOf(),
        var modifiedAt: Instant? = null,
        var permissions: Permissions? = null,
        var ownership: Ownership? = null,
    ) {
        override fun equals(other: Any?): Boolean {
            if (this === other) return true
            if (other !is MockFile) return false
            if (type != other.type) return false
            if (size != other.size) return false
            if (!content.contentEquals(other.content)) return false
            if (children != other.children) return false
            if (modifiedAt != other.modifiedAt) return false
            if (permissions != other.permissions) return false
            if (ownership != other.ownership) return false
            return true
        }

        override fun hashCode(): Int {
            var result = type.hashCode()
            result = 31 * result + size.hashCode()
            result = 31 * result + content.contentHashCode()
            result = 31 * result + children.hashCode()
            result = 31 * result + (modifiedAt?.hashCode() ?: 0)
            result = 31 * result + (permissions?.hashCode() ?: 0)
            result = 31 * result + (ownership?.hashCode() ?: 0)
            return result
        }
    }

    /**
     * In-memory file system: path string -> file metadata
     */
    val files = mutableMapOf<String, MockFile>()

    /**
     * Track operation calls for verification in tests.
     */
    val lookupCalls = mutableListOf<String>()
    val listFilesCalls = mutableListOf<String>()
    val existsCalls = mutableListOf<String>()
    val existsStrictCalls = mutableListOf<String>()
    val deleteCalls = mutableListOf<String>()
    val createDirCalls = mutableListOf<String>()
    val createFileCalls = mutableListOf<String>()

    /**
     * Answers for [existsStrict], per path and as a fallback. Deliberately not derived from the
     * in-memory files: a mock that maps "no such entry" to ABSENT reproduces exactly the conflation
     * the strict probe exists to avoid, so a test states which of the two it means.
     */
    val existsStrictAnswers = mutableMapOf<String, Existence>()
    var defaultExistsStrict: Existence = Existence.UNKNOWN

    /**
     * Failure injection for testing retry scenarios.
     */
    private var failOpenInputStreamCount = 0
    private var failOpenInputStreamException: (() -> Exception)? = null
    private var failOpenOutputStreamCount = 0
    private var failOpenOutputStreamException: (() -> Exception)? = null
    private var failDeleteCount = 0
    private var failDeleteException: (() -> Exception)? = null
    private var failCreateDirCount = 0
    private var failCreateDirException: (() -> Exception)? = null
    private var failCreateFileCount = 0
    private var failCreateFileException: (() -> Exception)? = null
    private var failListFilesCount = 0
    private var failListFilesException: (() -> Exception)? = null

    suspend fun lookup(path: P) = lookup(path, LookupOptions.BASE)

    override suspend fun lookup(path: P, options: LookupOptions): PL {
        lookupCalls.add(path.path)

        val mockFile = files[path.path]

        // Handle fallbackToUnknown option (matches LocalFileSystemOps behavior)
        if (mockFile == null) {
            if (options.fallbackToUnknown) {
                return lookupFactory(
                    path,
                    FileType.UNKNOWN,
                    null, null, null, null, null
                )
            }
            throw NoSuchFileException(path.path)
        }

        return lookupFactory(
            path,
            mockFile.type,
            if (options.fetchSize) mockFile.size else null,
            if (options.fetchModifiedAt) mockFile.modifiedAt else null,
            if (options.fetchPermissions) mockFile.permissions else null,
            if (options.fetchOwnership) mockFile.ownership else null,
            if (options.fetchCreatedAt) null else null // Not tracked in MockFile currently
        )
    }

    override suspend fun listFiles(path: P): List<P> {
        listFilesCalls.add(path.path)

        // Check for injected failure
        if (failListFilesCount > 0) {
            failListFilesCount--
            throw failListFilesException?.invoke() ?: SecurityException("Injected failure")
        }

        val mockFile = files[path.path]
            ?: throw NoSuchFileException(path.path)

        if (mockFile.type != FileType.DIRECTORY) {
            throw IllegalStateException("Not a directory: ${path.path}")
        }

        @Suppress("UNCHECKED_CAST")
        return mockFile.children.map { childName ->
            "${path.path}/$childName".replace("//", "/")
            path.child(childName) as P
        }
    }

    override suspend fun lookupFiles(path: P, options: LookupOptions): List<PL> {
        return listFiles(path).map { lookup(it, options) }
    }

    override suspend fun exists(path: P): Boolean {
        existsCalls.add(path.path)
        return files.containsKey(path.path)
    }

    override suspend fun existsStrict(path: P): Existence {
        existsStrictCalls.add(path.path)
        return existsStrictAnswers[path.path] ?: defaultExistsStrict
    }

    override suspend fun canWrite(path: P): Boolean {
        if (files.containsKey(path.path)) return true
        val parentPath = path.path.substringBeforeLast('/', "")
        return parentPath.isEmpty() || files.containsKey(parentPath)
    }

    override suspend fun delete(path: P, recursive: Boolean): Boolean {
        deleteCalls.add(path.path)

        // Check for injected failure
        if (failDeleteCount > 0) {
            failDeleteCount--
            throw failDeleteException?.invoke() ?: java.io.IOException("Injected failure")
        }

        val mockFile = files[path.path] ?: return false

        // If recursive, delete children first (post-order)
        if (recursive && mockFile.type == FileType.DIRECTORY) {
            val children = mockFile.children.toList() // Copy to avoid ConcurrentModificationException
            children.forEach { childName ->
                @Suppress("UNCHECKED_CAST")
                val childPath = path.child(childName) as P
                delete(childPath, recursive = true)
            }
        }

        // Check if directory is empty when not recursive
        if (!recursive && mockFile.type == FileType.DIRECTORY && mockFile.children.isNotEmpty()) {
            throw IllegalStateException("Directory not empty: ${path.path}")
        }

        // Remove from parent's children list
        val parentPath = path.path.substringBeforeLast('/', "")
        if (parentPath.isNotEmpty()) {
            val parent = files[parentPath]
            parent?.children?.remove(path.name)
        }

        files.remove(path.path)
        return true
    }

    override suspend fun createDir(path: P, createParents: Boolean) {
        createDirCalls.add(path.path)

        // Check for injected failure
        if (failCreateDirCount > 0) {
            failCreateDirCount--
            throw failCreateDirException?.invoke() ?: java.io.IOException("Injected failure")
        }

        if (files.containsKey(path.path)) {
            val existing = files[path.path]!!
            if (existing.type != FileType.DIRECTORY) {
                throw PathAlreadyExistsException(
                    message = "File exists but is not a directory: ${path.path}",
                    path = path
                )
            }
            // Already exists - idempotent
            return
        }

        // Create parent directories if needed
        val parentPath = path.path.substringBeforeLast('/', "")
        if (parentPath.isNotEmpty() && !files.containsKey(parentPath)) {
            if (createParents) {
                @Suppress("UNCHECKED_CAST")
                createDir(path.child("..") as P, createParents = true) // Simplified parent creation
            } else {
                throw NoSuchFileException("Parent directory does not exist: $parentPath")
            }
        }

        // Add to parent's children list
        if (parentPath.isNotEmpty()) {
            val parent = files[parentPath]
            if (parent != null && parent.type == FileType.DIRECTORY) {
                if (!parent.children.contains(path.name)) {
                    parent.children.add(path.name)
                }
            }
        }

        files[path.path] = MockFile(
            type = FileType.DIRECTORY,
            size = 0,
            children = mutableListOf()
        )
    }

    override suspend fun createFile(path: P, createParents: Boolean) {
        createFileCalls.add(path.path)

        // Check for injected failure
        if (failCreateFileCount > 0) {
            failCreateFileCount--
            throw failCreateFileException?.invoke() ?: java.io.IOException("Injected failure")
        }

        if (files.containsKey(path.path)) {
            throw PathAlreadyExistsException(path = path)
        }

        // Ensure parent directory exists
        val parentPath = path.path.substringBeforeLast('/', "")
        if (parentPath.isNotEmpty()) {
            if (!files.containsKey(parentPath)) {
                if (createParents) {
                    @Suppress("UNCHECKED_CAST")
                    createDir(path.child("..") as P, createParents = true)
                } else {
                    throw NoSuchFileException("Parent directory does not exist: $parentPath")
                }
            }

            // Add to parent's children list
            val parent = files[parentPath]!!
            if (!parent.children.contains(path.name)) {
                parent.children.add(path.name)
            }
        }

        files[path.path] = MockFile(
            type = FileType.FILE,
            size = 0,
            content = ByteArray(0)
        )
    }

    override suspend fun openInputStream(path: P): InputStream {
        // Check for injected failure
        if (failOpenInputStreamCount > 0) {
            failOpenInputStreamCount--
            throw failOpenInputStreamException?.invoke() ?: java.io.IOException("Injected failure")
        }

        val mockFile = files[path.path]
            ?: throw NoSuchFileException(path.path)

        if (mockFile.type == FileType.DIRECTORY) {
            throw IllegalStateException("Cannot open input stream for directory: ${path.path}")
        }

        return ByteArrayInputStream(mockFile.content)
    }

    override suspend fun openOutputStream(path: P, append: Boolean): OutputStream {
        // Check for injected failure
        if (failOpenOutputStreamCount > 0) {
            failOpenOutputStreamCount--
            throw failOpenOutputStreamException?.invoke() ?: java.io.IOException("Injected failure")
        }

        // Ensure parent exists
        val parentPath = path.path.substringBeforeLast('/', "")
        if (parentPath.isNotEmpty() && !files.containsKey(parentPath)) {
            throw NoSuchFileException("Parent directory does not exist: $parentPath")
        }

        // Add to parent's children list if new file
        if (!files.containsKey(path.path) && parentPath.isNotEmpty()) {
            val parent = files[parentPath]!!
            if (!parent.children.contains(path.name)) {
                parent.children.add(path.name)
            }
        }

        val existingContent = if (append) {
            files[path.path]?.content ?: ByteArray(0)
        } else {
            ByteArray(0)
        }

        return object : ByteArrayOutputStream() {
            override fun close() {
                super.close()
                val newContent = if (append) {
                    existingContent + toByteArray()
                } else {
                    toByteArray()
                }

                files[path.path] = MockFile(
                    type = FileType.FILE,
                    size = newContent.size.toLong(),
                    content = newContent
                )
            }
        }
    }

    override suspend fun setModifiedAt(path: P, modifiedAt: Instant): Boolean {
        val mockFile = files[path.path] ?: return false
        mockFile.modifiedAt = modifiedAt
        return true
    }

    override suspend fun setPermissions(path: P, permissions: Permissions): Boolean {
        val mockFile = files[path.path] ?: return false
        mockFile.permissions = permissions
        return true
    }

    override suspend fun setOwnership(path: P, ownership: Ownership): Boolean {
        val mockFile = files[path.path] ?: return false
        mockFile.ownership = ownership
        return true
    }

    override suspend fun createSymlink(linkPath: P, targetPath: P): Boolean {
        // Store target path in content field for later retrieval
        addMockSymlink(linkPath.path, targetPath.path)
        return true
    }

    // Identity: the mock can't faithfully resolve symlink chains; follow-semantics are tested on a real FS.
    override suspend fun canonicalize(path: P): P = path

    override suspend fun readSymbolicLink(linkPath: P): P {
        val mockFile = files[linkPath.path]
            ?: throw NoSuchFileException(linkPath.path)

        if (mockFile.type != FileType.SYMBOLIC_LINK) {
            throw IllegalStateException("Not a symbolic link: ${linkPath.path}")
        }

        // Target path stored in content field
        val targetPathString = String(mockFile.content)

        // Build path from the target string - handle both absolute and relative paths
        @Suppress("UNCHECKED_CAST")
        return if (targetPathString.startsWith("/")) {
            // Absolute path - create directly
            linkPath.child(targetPathString.removePrefix("/")) as P
        } else {
            // Relative path - resolve relative to link's parent
            val linkParent = linkPath.path.substringBeforeLast('/', "")
            val targetPath = if (linkParent.isNotEmpty()) {
                "$linkParent/$targetPathString".replace("//", "/")
            } else {
                targetPathString
            }
            linkPath.child(targetPath.removePrefix("/")) as P
        }
    }

    override suspend fun move(source: P, destination: P): MoveOutcome {
        // Contract: missing source is an error, not a "not supported" fallback signal
        val mockFile = files[source.path]
            ?: throw ReadException("Source does not exist", source)

        // Check if destination already exists
        if (files.containsKey(destination.path)) {
            throw PathAlreadyExistsException(
                message = "Destination already exists: ${destination.path}",
                path = destination
            )
        }

        // Remove from source parent's children list
        val sourceParentPath = source.path.substringBeforeLast('/', "")
        if (sourceParentPath.isNotEmpty()) {
            val sourceParent = files[sourceParentPath]
            sourceParent?.children?.remove(source.name)
        }

        // Remove from source location
        files.remove(source.path)

        // Add to destination location
        files[destination.path] = mockFile

        // Add to destination parent's children list
        val destParentPath = destination.path.substringBeforeLast('/', "")
        if (destParentPath.isNotEmpty()) {
            val destParent = files[destParentPath]
            if (destParent != null && !destParent.children.contains(destination.name)) {
                destParent.children.add(destination.name)
            }
        }

        return MoveOutcome.Moved
    }

    override suspend fun file(path: P, readWrite: Boolean): okio.FileHandle {
        throw UnsupportedOperationException("file() not implemented in MockFileSystemOps")
    }

    override suspend fun getFileSystem(path: P): eu.darken.butler.common.files.metadata.FileSystem {
        throw UnsupportedOperationException("getFileSystem() not implemented in MockFileSystemOps")
    }

    // Test helper methods

    /**
     * Add a mock file with content to the in-memory file system.
     */
    fun addMockFile(path: String, content: ByteArray, modifiedAt: Instant? = null) {
        // Ensure parent directory exists
        val parentPath = path.substringBeforeLast('/', "")
        if (parentPath.isNotEmpty() && !files.containsKey(parentPath)) {
            addMockDir(parentPath)
        }

        // Add to parent's children list
        if (parentPath.isNotEmpty()) {
            val parent = files[parentPath]!!
            val name = path.substringAfterLast('/')
            if (!parent.children.contains(name)) {
                parent.children.add(name)
            }
        }

        files[path] = MockFile(
            type = FileType.FILE,
            size = content.size.toLong(),
            content = content,
            modifiedAt = modifiedAt
        )
    }

    /**
     * Add a mock directory to the in-memory file system.
     */
    fun addMockDir(path: String) {
        if (files.containsKey(path)) {
            return // Already exists
        }

        // Recursively create parent directories
        val parentPath = path.substringBeforeLast('/', "")
        if (parentPath.isNotEmpty() && !files.containsKey(parentPath)) {
            addMockDir(parentPath)
        }

        // Add to parent's children list
        if (parentPath.isNotEmpty()) {
            val parent = files[parentPath]
            val name = path.substringAfterLast('/')
            if (parent != null && !parent.children.contains(name)) {
                parent.children.add(name)
            }
        }

        files[path] = MockFile(
            type = FileType.DIRECTORY,
            size = 0,
            children = mutableListOf()
        )
    }

    /**
     * Add a mock symlink to the in-memory file system.
     */
    fun addMockSymlink(path: String, targetPath: String) {
        val parentPath = path.substringBeforeLast('/', "")
        if (parentPath.isNotEmpty() && !files.containsKey(parentPath)) {
            addMockDir(parentPath)
        }

        if (parentPath.isNotEmpty()) {
            val parent = files[parentPath]!!
            val name = path.substringAfterLast('/')
            if (!parent.children.contains(name)) {
                parent.children.add(name)
            }
        }

        files[path] = MockFile(
            type = FileType.SYMBOLIC_LINK,
            size = targetPath.length.toLong(),
            content = targetPath.toByteArray()
        )
    }

    /**
     * Get file content for verification.
     */
    fun getFileContent(path: String): ByteArray? {
        return files[path]?.content
    }

    /**
     * Check if path exists in mock file system.
     */
    fun hasFile(path: String): Boolean {
        return files.containsKey(path)
    }

    /**
     * Get file type for verification.
     */
    fun getFileType(path: String): FileType? {
        return files[path]?.type
    }

    /**
     * Clear all mock files (for test cleanup).
     */
    fun clear() {
        files.clear()
        lookupCalls.clear()
        listFilesCalls.clear()
        existsCalls.clear()
        existsStrictCalls.clear()
        existsStrictAnswers.clear()
        defaultExistsStrict = Existence.UNKNOWN
        deleteCalls.clear()
        createDirCalls.clear()
        createFileCalls.clear()
        clearFailureInjection()
    }

    /**
     * Configure openInputStream to fail the next N times with specified exception.
     */
    fun setFailOpenInputStream(
        count: Int,
        exceptionFactory: () -> Exception = { java.io.IOException("Temporary failure") }
    ) {
        failOpenInputStreamCount = count
        failOpenInputStreamException = exceptionFactory
    }

    /**
     * Configure openOutputStream to fail the next N times with specified exception.
     */
    fun setFailOpenOutputStream(
        count: Int,
        exceptionFactory: () -> Exception = { java.io.IOException("Temporary failure") }
    ) {
        failOpenOutputStreamCount = count
        failOpenOutputStreamException = exceptionFactory
    }

    /**
     * Configure delete to fail the next N times with specified exception.
     */
    fun setFailDelete(count: Int, exceptionFactory: () -> Exception = { java.io.IOException("Temporary failure") }) {
        failDeleteCount = count
        failDeleteException = exceptionFactory
    }

    /**
     * Configure createDir to fail the next N times with specified exception.
     */
    fun setFailCreateDir(count: Int, exceptionFactory: () -> Exception = { java.io.IOException("Temporary failure") }) {
        failCreateDirCount = count
        failCreateDirException = exceptionFactory
    }

    /**
     * Configure createFile to fail the next N times with specified exception.
     */
    fun setFailCreateFile(
        count: Int,
        exceptionFactory: () -> Exception = { java.io.IOException("Temporary failure") }
    ) {
        failCreateFileCount = count
        failCreateFileException = exceptionFactory
    }

    /**
     * Convenience method to fail createFile exactly once with specified exception.
     */
    fun failCreateFileOnce(exceptionFactory: () -> Exception = { java.io.IOException("Temporary failure") }) {
        setFailCreateFile(1, exceptionFactory)
    }

    /**
     * Configure listFiles to fail the next N times with specified exception.
     */
    fun setFailListFiles(count: Int, exceptionFactory: () -> Exception = { SecurityException("Permission denied") }) {
        failListFilesCount = count
        failListFilesException = exceptionFactory
    }

    /**
     * Clear all failure injection settings.
     */
    fun clearFailureInjection() {
        failOpenInputStreamCount = 0
        failOpenInputStreamException = null
        failOpenOutputStreamCount = 0
        failOpenOutputStreamException = null
        failDeleteCount = 0
        failDeleteException = null
        failCreateDirCount = 0
        failCreateDirException = null
        failCreateFileCount = 0
        failCreateFileException = null
        failListFilesCount = 0
        failListFilesException = null
    }

    /**
     * Dump file system state for debugging.
     */
    fun dump(): String {
        return buildString {
            appendLine("MockFileSystemOps state:")
            files.keys.sorted().forEach { path ->
                val file = files[path]!!
                appendLine("  $path -> ${file.type} (${file.size} bytes)")
                if (file.type == FileType.DIRECTORY) {
                    appendLine("    children: ${file.children}")
                }
            }
        }
    }

    /**
     * Set size to null for a specific path (simulates permission errors during stat()).
     */
    fun setNullSize(path: String) {
        val mockFile = files[path] ?: error("Path not found: $path")
        files[path] = mockFile.copy(size = null)
    }

    /**
     * Set modifiedAt to null for a specific path (simulates permission errors during stat()).
     */
    fun setNullModifiedAt(path: String) {
        val mockFile = files[path] ?: error("Path not found: $path")
        files[path] = mockFile.copy(modifiedAt = null)
    }
}
