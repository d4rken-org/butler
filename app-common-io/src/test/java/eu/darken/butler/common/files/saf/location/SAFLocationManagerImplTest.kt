package eu.darken.butler.common.files.saf.location

import android.content.ContentResolver
import android.content.Context
import android.content.UriPermission
import android.net.Uri
import eu.darken.butler.common.coroutine.DispatcherProvider
import eu.darken.butler.common.files.SAFPath
import io.kotest.matchers.shouldBe
import io.kotest.matchers.shouldNotBe
import io.mockk.coEvery
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import testhelpers.BaseTest

/**
 * Tests for SAFLocationManagerImpl permission matching logic.
 *
 * These tests verify that the manager correctly finds permissions
 * for SAF paths, handling various scenarios including:
 * - Exact root matches (empty segments)
 * - Direct and nested child paths
 * - Permissions with nested URIs
 * - No-match scenarios
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [29])
class SAFLocationManagerImplTest : BaseTest() {

    private val testDispatcher = StandardTestDispatcher()
    private val testScope = TestScope(testDispatcher)
    private lateinit var context: Context
    private lateinit var contentResolver: ContentResolver
    private lateinit var preferences: SAFLocationPreferences
    private lateinit var dispatcherProvider: DispatcherProvider
    private lateinit var manager: SAFLocationManagerImpl

    private val baseAuthority = "com.android.externalstorage.documents"

    @Before
    fun setup() {
        context = mockk(relaxed = true)
        contentResolver = mockk(relaxed = true)
        preferences = mockk(relaxed = true) {
            // Emit an empty map - no custom preferences
            every { locations } returns flowOf(emptyMap())
            // Mock cleanup method to do nothing in tests
            coEvery { cleanup(any()) } returns Unit
        }
        dispatcherProvider = mockk {
            every { IO } returns testDispatcher
        }

        manager = SAFLocationManagerImpl(
            context = context,
            contentResolver = contentResolver,
            preferences = preferences,
            dispatcherProvider = dispatcherProvider,
            appScope = testScope,
        )

        // Advance dispatcher to allow StateFlow cache to initialize
        testDispatcher.scheduler.runCurrent()
    }

    /**
     * Test Case 1: Exact root match with empty segments.
     *
     * This was the bug: When querying the root of a permission (segments=[]),
     * the manager failed to match because it was comparing extracted URI segments
     * instead of comparing URIs directly.
     */
    @Test
    fun `exact root match - empty segments`() {
        // Setup: Permission for tree/primary:FolderA
        val permissionUri = Uri.parse("content://$baseAuthority/tree/primary%3AFolderA")
        val permission = mockUriPermission(permissionUri, read = true, write = true)

        every { contentResolver.persistedUriPermissions } returns listOf(permission)
        refreshCache() // Reload cache with new mocked permissions

        // Query: Path with same treeRoot and empty segments
        val queryPath = SAFPath.build(permissionUri)

        // Act
        val match = manager.findPermissionFor(queryPath)

        // Assert
        match shouldNotBe null
        match!!.location.treeUri.toString() shouldBe permissionUri.toString()
        match.missingSegments shouldBe emptyList()
    }

    /**
     * Test Case 2: Direct child match.
     *
     * Permission for folder, query for immediate child.
     */
    @Test
    fun `direct child match`() {
        // Setup: Permission for tree/primary:FolderA
        val permissionUri = Uri.parse("content://$baseAuthority/tree/primary%3AFolderA")
        val permission = mockUriPermission(permissionUri, read = true, write = true)

        every { contentResolver.persistedUriPermissions } returns listOf(permission)
        refreshCache() // Reload cache with new mocked permissions

        // Query: Path for FolderA/FolderB
        val queryPath = SAFPath.build(permissionUri, "FolderB")

        // Act
        val match = manager.findPermissionFor(queryPath)

        // Assert
        match shouldNotBe null
        match!!.location.treeUri.toString() shouldBe permissionUri.toString()
        match.missingSegments shouldBe listOf("FolderB")
    }

    /**
     * Test Case 3: Nested descendant match.
     *
     * Permission for folder, query for deeply nested child.
     */
    @Test
    fun `nested descendant match`() {
        // Setup: Permission for tree/primary:FolderA
        val permissionUri = Uri.parse("content://$baseAuthority/tree/primary%3AFolderA")
        val permission = mockUriPermission(permissionUri, read = true, write = true)

        every { contentResolver.persistedUriPermissions } returns listOf(permission)
        refreshCache() // Reload cache with new mocked permissions

        // Query: Path for FolderA/FolderB/FolderC
        val queryPath = SAFPath.build(permissionUri, "FolderB", "FolderC")

        // Act
        val match = manager.findPermissionFor(queryPath)

        // Assert
        match shouldNotBe null
        match!!.location.treeUri.toString() shouldBe permissionUri.toString()
        match.missingSegments shouldBe listOf("FolderB", "FolderC")
    }

    /**
     * Test Case 4: Permission with nested path, query for root.
     *
     * The permission URI itself contains path segments.
     * Query for the root of that permission.
     */
    @Test
    fun `permission with nested path - query root`() {
        // Setup: Permission for tree/primary:FolderA/FolderB (URI contains segments)
        val permissionUri = Uri.parse("content://$baseAuthority/tree/primary%3AFolderA%2FFolderB")
        val permission = mockUriPermission(permissionUri, read = true, write = true)

        every { contentResolver.persistedUriPermissions } returns listOf(permission)
        refreshCache() // Reload cache with new mocked permissions

        // Query: Path with same treeRoot and empty segments (the root of this permission)
        val queryPath = SAFPath.build(permissionUri)

        // Act
        val match = manager.findPermissionFor(queryPath)

        // Assert
        match shouldNotBe null
        match!!.location.treeUri.toString() shouldBe permissionUri.toString()
        match.missingSegments shouldBe emptyList()
    }

    /**
     * Test Case 5: Permission with nested path, query for child.
     *
     * The permission URI contains path segments, and we query for a child.
     */
    @Test
    fun `permission with nested path - query child`() {
        // Setup: Permission for tree/primary:FolderA/FolderB
        val permissionUri = Uri.parse("content://$baseAuthority/tree/primary%3AFolderA%2FFolderB")
        val permission = mockUriPermission(permissionUri, read = true, write = true)

        every { contentResolver.persistedUriPermissions } returns listOf(permission)
        refreshCache() // Reload cache with new mocked permissions

        // Query: Path for FolderA/FolderB/FolderC
        val queryPath = SAFPath.build(permissionUri, "FolderC")

        // Act
        val match = manager.findPermissionFor(queryPath)

        // Assert
        match shouldNotBe null
        match!!.location.treeUri.toString() shouldBe permissionUri.toString()
        match.missingSegments shouldBe listOf("FolderC")
    }

    /**
     * Test Case 6: No match - different tree root.
     *
     * Permission for one folder, query for a completely different folder.
     */
    @Test
    fun `no match - different tree root`() {
        // Setup: Permission for tree/primary:FolderA
        val permissionUri = Uri.parse("content://$baseAuthority/tree/primary%3AFolderA")
        val permission = mockUriPermission(permissionUri, read = true, write = true)

        every { contentResolver.persistedUriPermissions } returns listOf(permission)
        refreshCache() // Reload cache with new mocked permissions

        // Query: Path for tree/primary:FolderB (different folder)
        val queryUri = Uri.parse("content://$baseAuthority/tree/primary%3AFolderB")
        val queryPath = SAFPath.build(queryUri)

        // Act
        val match = manager.findPermissionFor(queryPath)

        // Assert
        match shouldBe null
    }

    /**
     * Test Case 7: No match - different storage prefix.
     *
     * Permission for primary storage, query for sdcard storage.
     */
    @Test
    fun `no match - different storage prefix`() {
        // Setup: Permission for tree/primary:FolderA
        val permissionUri = Uri.parse("content://$baseAuthority/tree/primary%3AFolderA")
        val permission = mockUriPermission(permissionUri, read = true, write = true)

        every { contentResolver.persistedUriPermissions } returns listOf(permission)
        refreshCache() // Reload cache with new mocked permissions

        // Query: Path for tree/sdcard:FolderA (different storage)
        val queryUri = Uri.parse("content://$baseAuthority/tree/sdcard%3AFolderA")
        val queryPath = SAFPath.build(queryUri)

        // Act
        val match = manager.findPermissionFor(queryPath)

        // Assert
        match shouldBe null
    }

    /**
     * Test Case 8: Multiple permissions - choose best match.
     *
     * When multiple permissions could match, the manager should choose
     * the most specific one (longest matching path).
     */
    @Test
    fun `multiple permissions - choose best match`() {
        // Setup: Two permissions
        // Permission 1: tree/primary:FolderA
        val permission1Uri = Uri.parse("content://$baseAuthority/tree/primary%3AFolderA")
        val permission1 = mockUriPermission(permission1Uri, read = true, write = true)

        // Permission 2: tree/primary:FolderA/FolderB (more specific)
        val permission2Uri = Uri.parse("content://$baseAuthority/tree/primary%3AFolderA%2FFolderB")
        val permission2 = mockUriPermission(permission2Uri, read = true, write = true)

        every { contentResolver.persistedUriPermissions } returns listOf(permission1, permission2)
        refreshCache() // Reload cache with new mocked permissions

        // Query: Path for FolderA/FolderB/FolderC
        // Should match permission2 (more specific)
        val queryPath = SAFPath.build(permission2Uri, "FolderC")

        // Act
        val match = manager.findPermissionFor(queryPath)

        // Assert
        match shouldNotBe null
        // Should match the more specific permission
        match!!.location.treeUri.toString() shouldBe permission2Uri.toString()
        match.missingSegments shouldBe listOf("FolderC")
    }

    // --- Helper Methods ---

    /**
     * Create a mock UriPermission with the specified properties.
     */
    private fun mockUriPermission(
        uri: Uri,
        read: Boolean = true,
        write: Boolean = true,
        persistedTime: Long = 1000L
    ): UriPermission {
        return mockk {
            every { this@mockk.uri } returns uri
            every { isReadPermission } returns read
            every { isWritePermission } returns write
            every { this@mockk.persistedTime } returns persistedTime
        }
    }

    /**
     * Refresh the manager's cache after mocking permissions.
     * This is needed because the cache is initialized during construction,
     * so tests need to refresh after setting up their mocked permissions.
     */
    private fun refreshCache() = runBlocking {
        manager.refresh()
        testDispatcher.scheduler.runCurrent()
    }
}
