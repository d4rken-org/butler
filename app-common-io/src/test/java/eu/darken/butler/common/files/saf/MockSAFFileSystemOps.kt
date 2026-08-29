package eu.darken.butler.common.files.saf

import eu.darken.butler.common.files.Existence
import eu.darken.butler.common.files.LookupOptions
import eu.darken.butler.common.files.MoveOutcome
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
class MockSAFFileSystemOps : MockFileSystemOps<SAFPath, SAFPathLookup>(
    lookupFactory = { _, _, _, _, _, _, _ ->
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

    override suspend fun lookup(path: SAFPath, options: LookupOptions): SAFPathLookup {
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
            every { length } returns (mockFile.size ?: 0L)
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
            ownership = if (options.fetchOwnership) mockFile.ownership else null,
            permissions = if (options.fetchPermissions) mockFile.permissions else null,
            createdAt = if (options.fetchCreatedAt) null else null,
        )
    }

    override suspend fun lookupFiles(path: SAFPath, options: LookupOptions): List<SAFPathLookup> {
        // Check permission first
        if (pathsWithoutPermission.contains(path.path)) {
            throw MissingUriPermissionException(path = path)
        }

        return listFiles(path).map { lookup(it, options) }
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

    /** A missing grant means nobody was asked, which is not an absence - as in SAFFileSystemOps. */
    override suspend fun existsStrict(path: SAFPath): Existence = when {
        pathsWithoutPermission.contains(path.path) -> Existence.UNKNOWN
        else -> super.existsStrict(path)
    }

    override suspend fun createSymlink(linkPath: SAFPath, targetPath: SAFPath): Boolean {
        throw UnsupportedOperationException("SAF (Storage Access Framework) does not support symlinks")
    }

    override suspend fun readSymbolicLink(linkPath: SAFPath): SAFPath {
        throw UnsupportedOperationException("SAF (Storage Access Framework) does not support symlinks")
    }

    override suspend fun move(source: SAFPath, destination: SAFPath): MoveOutcome {
        // Check permissions for both source and destination
        if (pathsWithoutPermission.contains(source.path)) {
            throw MissingUriPermissionException(path = source)
        }
        if (pathsWithoutPermission.contains(destination.path)) {
            throw MissingUriPermissionException(path = destination)
        }

        // Model the real SAFFileSystemOps contract: structural refusals are NotSupported
        // (provably nothing mutated) so callers exercise their copy+delete fallback.
        if (source == destination) return MoveOutcome.Moved
        if (source.treeRootUri != destination.treeRootUri) {
            return MoveOutcome.NotSupported("Cross-tree SAF moves are not atomic")
        }
        val sameParent = source.segments.dropLast(1) == destination.segments.dropLast(1)
        val sameName = source.segments.last() == destination.segments.last()
        if (!sameParent && !sameName) {
            return MoveOutcome.NotSupported("SAF cannot atomically reparent and rename")
        }
        if (files.containsKey(destination.path)) {
            return MoveOutcome.NotSupported("Destination already exists: $destination")
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
