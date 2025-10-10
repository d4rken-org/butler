package eu.darken.butler.common.files.operations

import eu.darken.butler.common.files.APath
import eu.darken.butler.common.files.APathLookup
import eu.darken.butler.common.files.APathLookupExtended
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
 * @param PLE The path lookup extended type (LocalPathLookupExtended, SAFPathLookupExtended, etc.)
 * @param lookupFactory Factory function to create path lookups from mock data
 */
open class MockFileSystemOps<P : APath, PL : APathLookup<P>, PLE : APathLookupExtended<P>>(
    private val lookupFactory: (path: P, type: FileType, size: Long, modifiedAt: Instant?, permissions: Permissions?, ownership: Ownership?) -> PL
) : FileSystemOps<P, PL, PLE> {

    /**
     * Mock file entry in the in-memory file system.
     */
    data class MockFile(
        val type: FileType,
        val size: Long,
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
    val deleteCalls = mutableListOf<String>()
    val createDirCalls = mutableListOf<String>()
    val createFileCalls = mutableListOf<String>()

    /**
     * Wrapper class that implements APathLookupExtended for mock testing.
     */
    private inner class MockPathLookupExtended(
        private val basicLookup: PL,
        override val permissions: Permissions?,
        override val ownership: Ownership?,
        override val createdAt: Instant?
    ) : APathLookupExtended<P>, APathLookup<P> by basicLookup

    override suspend fun lookup(path: P): PL {
        lookupCalls.add(path.path)

        val mockFile = files[path.path]
            ?: throw NoSuchFileException(path.path)

        return lookupFactory(
            path,
            mockFile.type,
            mockFile.size,
            mockFile.modifiedAt,
            mockFile.permissions,
            mockFile.ownership
        )
    }

    override suspend fun lookupExtended(path: P): PLE {
        val basicLookup = lookup(path)
        val mockFile = files[path.path]!!

        @Suppress("UNCHECKED_CAST")
        return MockPathLookupExtended(
            basicLookup = basicLookup,
            permissions = mockFile.permissions,
            ownership = mockFile.ownership,
            createdAt = null // Not tracked in MockFile currently
        ) as PLE
    }

    override suspend fun listFiles(path: P): List<P> {
        listFilesCalls.add(path.path)

        val mockFile = files[path.path]
            ?: throw NoSuchFileException(path.path)

        if (mockFile.type != FileType.DIRECTORY) {
            throw IllegalStateException("Not a directory: ${path.path}")
        }

        @Suppress("UNCHECKED_CAST")
        return mockFile.children.map { childName ->
            val childPath = "${path.path}/$childName".replace("//", "/")
            path.child(childName) as P
        }
    }

    override suspend fun lookupFiles(path: P): List<PL> {
        return listFiles(path).map { lookup(it) }
    }

    override suspend fun lookupFilesExtended(path: P): List<PLE> {
        return listFiles(path).map { lookupExtended(it) }
    }

    override suspend fun exists(path: P): Boolean {
        existsCalls.add(path.path)
        return files.containsKey(path.path)
    }

    override suspend fun delete(path: P): Boolean {
        deleteCalls.add(path.path)

        val mockFile = files[path.path] ?: return false

        // Check if directory is empty
        if (mockFile.type == FileType.DIRECTORY && mockFile.children.isNotEmpty()) {
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

    override suspend fun createDir(path: P) {
        createDirCalls.add(path.path)

        if (files.containsKey(path.path)) {
            val existing = files[path.path]!!
            if (existing.type != FileType.DIRECTORY) {
                throw IllegalStateException("File exists but is not a directory: ${path.path}")
            }
            // Already exists - idempotent
            return
        }

        // Create parent directories if needed
        val parentPath = path.path.substringBeforeLast('/', "")
        if (parentPath.isNotEmpty() && !files.containsKey(parentPath)) {
            @Suppress("UNCHECKED_CAST")
            createDir(path.child("..") as P) // Simplified parent creation
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

    override suspend fun createFile(path: P) {
        createFileCalls.add(path.path)

        if (files.containsKey(path.path)) {
            throw IllegalStateException("File already exists: ${path.path}")
        }

        // Ensure parent directory exists
        val parentPath = path.path.substringBeforeLast('/', "")
        if (parentPath.isNotEmpty()) {
            if (!files.containsKey(parentPath)) {
                throw NoSuchFileException("Parent directory does not exist: $parentPath")
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
        val mockFile = files[path.path]
            ?: throw NoSuchFileException(path.path)

        if (mockFile.type == FileType.DIRECTORY) {
            throw IllegalStateException("Cannot open input stream for directory: ${path.path}")
        }

        return ByteArrayInputStream(mockFile.content)
    }

    override suspend fun openOutputStream(path: P, append: Boolean): OutputStream {
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
        // For mock purposes, create an empty file and mark it as symlink
        addMockFile(linkPath.path, ByteArray(0))
        files[linkPath.path] = files[linkPath.path]!!.copy(type = FileType.SYMBOLIC_LINK)
        return true
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
        deleteCalls.clear()
        createDirCalls.clear()
        createFileCalls.clear()
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
}
