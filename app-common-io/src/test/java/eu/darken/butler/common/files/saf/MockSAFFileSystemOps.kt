package eu.darken.butler.common.files.saf

import eu.darken.butler.common.files.SAFPath
import eu.darken.butler.common.files.metadata.FileType
import eu.darken.butler.common.files.operations.MockFileSystemOps
import io.mockk.every
import io.mockk.mockk
import kotlin.time.Instant

/**
 * Mock SAFFileSystemOps for testing without Android framework.
 *
 * Extends the generic MockFileSystemOps with SAF-specific test helpers.
 * Enables testing SAFPath operations without:
 * - Android Context
 * - ContentResolver
 * - URI permissions
 * - Real file system access
 *
 * ## Usage Example
 *
 * ```kotlin
 * @Test
 * fun `copy SAF file preserves content`() = runTest {
 *     val mockOps = MockSAFFileSystemOps()
 *
 *     // Setup mock SAF files (no Android needed!)
 *     mockOps.addMockDocumentFile(
 *         safPath = SAFPath.build(testUri, "source.txt"),
 *         mimeType = "text/plain",
 *         content = "Hello SAF".toByteArray()
 *     )
 *     mockOps.addMockDocumentFile(
 *         safPath = SAFPath.build(testUri, "dest"),
 *         mimeType = DocumentsContract.Document.MIME_TYPE_DIR,
 *         content = ByteArray(0)
 *     )
 *
 *     // Run operation (fully testable!)
 *     val result = setOf(SAFPath.build(testUri, "source.txt")).copyGeneric(
 *         destination = SAFPath.build(testUri, "dest"),
 *         fileSystemOps = mockOps,
 *         strategy = SAFPathCopyStrategy()
 *     )
 *
 *     // Verify
 *     result.copied shouldHaveSize 1
 *     mockOps.getFileContent("content://provider/tree/test/document/dest%2Fsource.txt")
 *         ?.decodeToString() shouldBe "Hello SAF"
 * }
 * ```
 *
 * ## SAF-Specific Features
 *
 * - Simulates URI permission checking (via `simulateMissingPermission`)
 * - Handles DocumentsContract MIME types
 * - Supports SAFPath URI structure
 * - Tracks SAFDocFile behavior
 */
class MockSAFFileSystemOps : MockFileSystemOps<SAFPath, SAFPathLookup, SAFPathLookupExtended>(
    lookupFactory = { _, _, _, _, _, _ ->
        // Placeholder - we override lookup() to bypass this factory
        // SAFDocFile requires Android Context/ContentResolver which can't be mocked easily
        throw UnsupportedOperationException("Use lookup() override instead")
    }
) {

    /**
     * Paths that should throw MissingUriPermissionException.
     * Used to test permission-denied scenarios.
     */
    private val pathsWithoutPermission = mutableSetOf<String>()

    /**
     * Add a mock document file (SAF-specific helper).
     *
     * @param safPath The SAFPath for this document
     * @param mimeType MIME type (use DocumentsContract.Document.MIME_TYPE_DIR for directories)
     * @param content File content (empty for directories)
     * @param modifiedAt Last modified timestamp
     */
    fun addMockDocumentFile(
        safPath: SAFPath,
        mimeType: String,
        content: ByteArray,
        modifiedAt: Instant? = null
    ) {
        val type = if (mimeType == android.provider.DocumentsContract.Document.MIME_TYPE_DIR) {
            FileType.DIRECTORY
        } else {
            FileType.FILE
        }

        addMockFile(safPath.path, content, modifiedAt)

        // Update type if needed
        files[safPath.path]?.let { mockFile ->
            files[safPath.path] = mockFile.copy(type = type)
        }
    }

    /**
     * Simulate missing URI permission for a path.
     *
     * When operations try to access this path, they'll get MissingUriPermissionException.
     * Useful for testing permission-denied scenarios.
     *
     * @param safPath The path to deny permission for
     */
    fun simulateMissingPermission(safPath: SAFPath) {
        pathsWithoutPermission.add(safPath.path)
    }

    /**
     * Clear permission restriction for a path.
     */
    fun clearPermissionRestriction(safPath: SAFPath) {
        pathsWithoutPermission.remove(safPath.path)
    }

    override suspend fun lookup(path: SAFPath): SAFPathLookup {
        // Check permission first
        if (pathsWithoutPermission.contains(path.path)) {
            throw MissingUriPermissionException(path = path)
        }

        // Get mock file data
        lookupCalls.add(path.path)
        val mockFile = files[path.path]
            ?: throw java.nio.file.NoSuchFileException(path.path)

        // Create a mock SAFDocFile with the necessary properties
        mockk<SAFDocFile>(relaxed = true) {
            every { uri } returns path.pathUri.toAndroidUri()
            every { name } returns path.name
            every { isDirectory } returns (mockFile.type == FileType.DIRECTORY)
            every { isFile } returns (mockFile.type == FileType.FILE)
            every { length } returns mockFile.size
            every { lastModified } returns (mockFile.modifiedAt ?: Instant.fromEpochMilliseconds(0))
            every { readable } returns true
            every { writable } returns true
            every { exists } returns true
        }

        return SAFPathLookup(
            lookedUp = path,
            fileType = mockFile.type,
            size = mockFile.size,
            modifiedAt = mockFile.modifiedAt ?: Instant.fromEpochMilliseconds(0),
        )
    }

    override suspend fun lookupExtended(path: SAFPath): SAFPathLookupExtended {
        // Check permission first
        if (pathsWithoutPermission.contains(path.path)) {
            throw MissingUriPermissionException(path = path)
        }

        val basicLookup = lookup(path)
        return SAFPathLookupExtended(
            lookup = basicLookup,
            ownership = null,
            permissions = null,
            createdAt = null,
        )
    }

    override suspend fun lookupFiles(path: SAFPath): List<SAFPathLookup> {
        // Check permission first
        if (pathsWithoutPermission.contains(path.path)) {
            throw MissingUriPermissionException(path = path)
        }

        return listFiles(path).map { lookup(it) }
    }

    override suspend fun lookupFilesExtended(path: SAFPath): List<SAFPathLookupExtended> {
        // Check permission first
        if (pathsWithoutPermission.contains(path.path)) {
            throw MissingUriPermissionException(path = path)
        }

        return listFiles(path).map { lookupExtended(it) }
    }

    override suspend fun listFiles(path: SAFPath): List<SAFPath> {
        // Check permission first
        if (pathsWithoutPermission.contains(path.path)) {
            throw MissingUriPermissionException(path = path)
        }

        return super.listFiles(path)
    }

    override suspend fun exists(path: SAFPath): Boolean {
        // Check permission - return false for missing permissions (matches SAFFileSystemOps behavior)
        if (pathsWithoutPermission.contains(path.path)) {
            return false
        }

        return super.exists(path)
    }

    override suspend fun createSymlink(linkPath: SAFPath, targetPath: SAFPath): Boolean {
        throw UnsupportedOperationException("SAF (Storage Access Framework) does not support symlinks")
    }

    override suspend fun readSymbolicLink(linkPath: SAFPath): SAFPath {
        throw UnsupportedOperationException("SAF (Storage Access Framework) does not support symlinks")
    }

    override suspend fun move(source: SAFPath, destination: SAFPath): Boolean {
        // Check permissions for both source and destination
        if (pathsWithoutPermission.contains(source.path)) {
            throw MissingUriPermissionException(path = source)
        }
        if (pathsWithoutPermission.contains(destination.path)) {
            throw MissingUriPermissionException(path = destination)
        }

        return super.move(source, destination)
    }

    /**
     * Get document MIME type for verification.
     */
    fun getDocumentMimeType(path: String): String? {
        val file = files[path] ?: return null
        return when (file.type) {
            FileType.DIRECTORY -> android.provider.DocumentsContract.Document.MIME_TYPE_DIR
            FileType.FILE -> "application/octet-stream"
            else -> "application/octet-stream"
        }
    }

    /**
     * Check if path has simulated permission.
     */
    fun hasPermission(safPath: SAFPath): Boolean {
        return !pathsWithoutPermission.contains(safPath.path)
    }

    /**
     * Dump SAF-specific state for debugging.
     */
    fun dumpSAF(): String {
        return buildString {
            appendLine("MockSAFFileSystemOps state:")
            appendLine("Files:")
            files.keys.sorted().forEach { path ->
                val file = files[path]!!
                val mimeType = getDocumentMimeType(path)
                val hasPermission = !pathsWithoutPermission.contains(path)
                appendLine("  $path -> ${file.type} (${file.size} bytes, mimeType=$mimeType, hasPermission=$hasPermission)")
                if (file.type == FileType.DIRECTORY) {
                    appendLine("    children: ${file.children}")
                }
            }
            if (pathsWithoutPermission.isNotEmpty()) {
                appendLine("Paths without permission: $pathsWithoutPermission")
            }
        }
    }
}

// Note: MockSAFDocFile is not needed - we use mockk to create SAFDocFile mocks in lookup()
// SAFDocFile requires real Android Context/ContentResolver, so we can't create test instances directly
