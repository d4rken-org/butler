package eu.darken.butler.common.files.saf.location

import android.content.ContentResolver
import android.content.Context
import android.content.UriPermission
import android.net.Uri
import eu.darken.butler.common.coroutine.DispatcherProvider
import eu.darken.butler.common.files.LocalPath
import eu.darken.butler.common.files.SAFPath
import eu.darken.butler.common.files.saf.location.db.SAFLocationDatabase
import eu.darken.butler.common.files.saf.location.db.SAFLocationEntity
import eu.darken.butler.common.files.saf.location.db.SAFLocationsDao
import eu.darken.butler.common.storage.StorageManager2
import eu.darken.butler.common.storage.StorageVolumeX
import io.kotest.matchers.shouldBe
import io.kotest.matchers.shouldNotBe
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch
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
    private lateinit var database: SAFLocationDatabase
    private lateinit var dao: SAFLocationsDao
    private lateinit var preferences: MutableStateFlow<List<SAFLocationEntity>>
    private lateinit var dispatcherProvider: DispatcherProvider
    private lateinit var storageManager2: StorageManager2
    private lateinit var manager: SAFLocationManagerImpl

    private val baseAuthority = "com.android.externalstorage.documents"

    @Before
    fun setup() {
        context = mockk(relaxed = true)
        contentResolver = mockk(relaxed = true)

        // Mock DAO backed by an in-memory table, upserts land in the preference flow like Room's would
        preferences = MutableStateFlow(emptyList())
        dao = mockk(relaxed = true) {
            every { getAllPreferences() } returns preferences
            // Mock cleanup method to do nothing in tests
            coEvery { cleanup(any()) } returns Unit
            coEvery { getPreference(any()) } answers { preferences.value.find { it.locationId == firstArg() } }
            coEvery { upsert(any()) } answers {
                val entity = firstArg<SAFLocationEntity>()
                preferences.value = preferences.value.filterNot { it.locationId == entity.locationId } + entity
            }
        }

        // Mock database to return the DAO
        database = mockk(relaxed = true) {
            every { safLocations() } returns dao
        }

        dispatcherProvider = mockk {
            every { IO } returns testDispatcher
        }

        // Mock StorageManager2 with default empty volumes
        storageManager2 = mockk {
            every { storageVolumes } returns emptyList()
        }

        manager = SAFLocationManagerImpl(
            context = context,
            appScope = testScope,
            contentResolver = contentResolver,
            dispatcherProvider = dispatcherProvider,
            database = database,
            storageManager2 = storageManager2,
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

    // --- Path Mapping Tests ---

    /**
     * Test toSAFPath() with subdirectory permission.
     * This is the main fix: SAFPath should use permission root, not volume root.
     */
    @Test
    fun `toSAFPath with subdirectory permission - uses permission root`() {
        // Setup: Volume for /storage/emulated/0
        val volumeDir = mockk<java.io.File> {
            every { path } returns "/storage/emulated/0"
        }
        val volume = mockk<StorageVolumeX> {
            every { directory } returns volumeDir
            every { treeUri } returns Uri.parse("content://$baseAuthority/tree/primary")
        }
        every { storageManager2.storageVolumes } returns listOf(volume)

        // Setup: Permission for Android/data subdirectory
        val permissionUri = Uri.parse("content://$baseAuthority/tree/primary%3AAndroid%2Fdata")
        val permission = mockUriPermission(permissionUri, read = true, write = true)
        every { contentResolver.persistedUriPermissions } returns listOf(permission)
        refreshCache()

        // Act: Convert LocalPath in Android/data
        val localPath = LocalPath.build("/storage/emulated/0/Android/data/com.app/file.txt")
        val safPath = manager.toSAFPath(localPath)

        // Assert: Should use permission root, not volume root
        safPath shouldNotBe null
        safPath!!.treeRoot shouldBe "content://$baseAuthority/tree/primary%3AAndroid%2Fdata"
        safPath.segments shouldBe listOf("com.app", "file.txt")
    }

    /**
     * Test toSAFPath() with root directory permission.
     */
    @Test
    fun `toSAFPath with root directory - uses permission root`() {
        // Setup: Volume for /storage/emulated/0
        val volumeDir = mockk<java.io.File> {
            every { path } returns "/storage/emulated/0"
        }
        val volume = mockk<StorageVolumeX> {
            every { directory } returns volumeDir
            every { treeUri } returns Uri.parse("content://$baseAuthority/tree/primary")
        }
        every { storageManager2.storageVolumes } returns listOf(volume)

        // Setup: Permission for root directory
        val permissionUri = Uri.parse("content://$baseAuthority/tree/primary")
        val permission = mockUriPermission(permissionUri, read = true, write = true)
        every { contentResolver.persistedUriPermissions } returns listOf(permission)
        refreshCache()

        // Act: Convert LocalPath at root
        val localPath = LocalPath.build("/storage/emulated/0/Pictures/photo.jpg")
        val safPath = manager.toSAFPath(localPath)

        // Assert: Should use permission root (which is volume root in this case)
        safPath shouldNotBe null
        safPath!!.treeRoot shouldBe "content://$baseAuthority/tree/primary"
        safPath.segments shouldBe listOf("Pictures", "photo.jpg")
    }

    /**
     * Test toSAFPath() returns null for unknown volume.
     */
    @Test
    fun `toSAFPath for unknown volume - returns null`() {
        // Setup: No volumes
        every { storageManager2.storageVolumes } returns emptyList()

        // Act
        val localPath = LocalPath.build("/unknown/path")
        val safPath = manager.toSAFPath(localPath)

        // Assert
        safPath shouldBe null
    }

    /**
     * Test toSAFPath() returns null when no permission exists.
     * This verifies the fix: toSAFPath() should ONLY return when permission exists.
     */
    @Test
    fun `toSAFPath without permission - returns null`() {
        // Setup: Volume available but no permission granted
        val volumeDir = mockk<java.io.File> {
            every { path } returns "/storage/emulated/0"
        }
        val volume = mockk<StorageVolumeX> {
            every { directory } returns volumeDir
            every { treeUri } returns Uri.parse("content://$baseAuthority/tree/primary")
        }
        every { storageManager2.storageVolumes } returns listOf(volume)

        // No permissions granted
        every { contentResolver.persistedUriPermissions } returns emptyList()
        refreshCache()

        // Act
        val localPath = LocalPath.build("/storage/emulated/0/Android/data")
        val safPath = manager.toSAFPath(localPath)

        // Assert: Should return null because no permission exists
        safPath shouldBe null
    }

    // --- toLocalPath() Tests ---

    /**
     * Test toLocalPath() with SD card - should match correct volume.
     * This test WILL FAIL with current "volumes[0]" implementation.
     */
    @Test
    fun `toLocalPath with SD card - matches correct volume`() {
        // Setup: Multiple volumes (primary + SD card)
        val primaryDir = mockk<java.io.File> {
            every { path } returns "/storage/emulated/0"
            every { isAbsolute } returns true
        }
        val primaryVolume = mockk<StorageVolumeX> {
            every { directory } returns primaryDir
            every { treeUri } returns Uri.parse("content://$baseAuthority/tree/primary")
        }

        val sdcardDir = mockk<java.io.File> {
            every { path } returns "/storage/sdcard"
            every { isAbsolute } returns true
        }
        val sdcardVolume = mockk<StorageVolumeX> {
            every { directory } returns sdcardDir
            every { treeUri } returns Uri.parse("content://$baseAuthority/tree/sdcard")
        }

        every { storageManager2.storageVolumes } returns listOf(primaryVolume, sdcardVolume)

        // Create SAFPath for SD card with subdirectory
        val sdcardUri = Uri.parse("content://$baseAuthority/tree/sdcard%3Afolder")
        val safPath = SAFPath.build(sdcardUri, "file.txt")

        // Act
        val localPath = manager.toLocalPath(safPath)

        // Assert: Should map to SD card, NOT primary storage
        localPath shouldNotBe null
        localPath!!.path shouldBe "/storage/sdcard/folder/file.txt"
    }

    /**
     * Test toLocalPath() with mismatched volume - should return null.
     * This test WILL FAIL with current "volumes[0]" implementation.
     */
    @Test
    fun `toLocalPath with mismatched volume - returns null`() {
        // Setup: Only primary volume available
        val primaryDir = mockk<java.io.File> {
            every { path } returns "/storage/emulated/0"
            every { isAbsolute } returns true
        }
        val primaryVolume = mockk<StorageVolumeX> {
            every { directory } returns primaryDir
            every { treeUri } returns Uri.parse("content://$baseAuthority/tree/primary")
        }

        every { storageManager2.storageVolumes } returns listOf(primaryVolume)

        // Create SAFPath for SD card (which doesn't exist)
        val sdcardUri = Uri.parse("content://$baseAuthority/tree/sdcard%3Afolder")
        val safPath = SAFPath.build(sdcardUri)

        // Act
        val localPath = manager.toLocalPath(safPath)

        // Assert: Should return null because no matching volume
        localPath shouldBe null
    }

    /**
     * Test toLocalPath() with multiple volumes - should select correct one.
     * This test WILL FAIL with current "volumes[0]" implementation.
     */
    @Test
    fun `toLocalPath with multiple volumes - selects correct one`() {
        // Setup: Three volumes (primary, sdcard, USB)
        val primaryDir = mockk<java.io.File> {
            every { path } returns "/storage/emulated/0"
            every { isAbsolute } returns true
        }
        val primaryVolume = mockk<StorageVolumeX> {
            every { directory } returns primaryDir
            every { treeUri } returns Uri.parse("content://$baseAuthority/tree/primary")
        }

        val sdcardDir = mockk<java.io.File> {
            every { path } returns "/storage/sdcard"
            every { isAbsolute } returns true
        }
        val sdcardVolume = mockk<StorageVolumeX> {
            every { directory } returns sdcardDir
            every { treeUri } returns Uri.parse("content://$baseAuthority/tree/sdcard")
        }

        val usbDir = mockk<java.io.File> {
            every { path } returns "/storage/usb"
            every { isAbsolute } returns true
        }
        val usbVolume = mockk<StorageVolumeX> {
            every { directory } returns usbDir
            every { treeUri } returns Uri.parse("content://$baseAuthority/tree/1234-5678")
        }

        every { storageManager2.storageVolumes } returns listOf(primaryVolume, sdcardVolume, usbVolume)

        // Create SAFPath for USB storage
        val usbUri = Uri.parse("content://$baseAuthority/tree/1234-5678%3Adata")
        val safPath = SAFPath.build(usbUri, "file.bin")

        // Act
        val localPath = manager.toLocalPath(safPath)

        // Assert: Should map to USB storage, NOT primary (volumes[0])
        localPath shouldNotBe null
        localPath!!.path shouldBe "/storage/usb/data/file.bin"
    }

    /**
     * Test toLocalPath() with single primary volume - regression check.
     * This test SHOULD PASS even with current implementation.
     */
    @Test
    fun `toLocalPath with single primary volume - still works`() {
        // Setup: Only primary volume
        val primaryDir = mockk<java.io.File> {
            every { path } returns "/storage/emulated/0"
            every { isAbsolute } returns true
        }
        val primaryVolume = mockk<StorageVolumeX> {
            every { directory } returns primaryDir
            every { treeUri } returns Uri.parse("content://$baseAuthority/tree/primary")
        }

        every { storageManager2.storageVolumes } returns listOf(primaryVolume)

        // Create SAFPath for primary storage
        val primaryUri = Uri.parse("content://$baseAuthority/tree/primary%3APictures")
        val safPath = SAFPath.build(primaryUri, "photo.jpg")

        // Act
        val localPath = manager.toLocalPath(safPath)

        // Assert: Should work correctly (regression check)
        localPath shouldNotBe null
        localPath!!.path shouldBe "/storage/emulated/0/Pictures/photo.jpg"
    }

    // --- Preference Update Tests ---

    /**
     * Seeding a label on a location that has none writes it through.
     */
    @Test
    fun `seedLocationLabel writes when no label is stored`() {
        val locationId = grantedLocationId()

        var seeded: String? = null
        val seed = testScope.launch { seeded = manager.seedLocationLabel(locationId, "Termux") }
        testDispatcher.scheduler.runCurrent()

        seed.isCompleted shouldBe true
        seeded shouldBe "Termux"
        preferences.value.single().userLabel shouldBe "Termux"
    }

    /**
     * A re-grant must not clobber a name the user set, not even on a hidden location
     * (those are absent from the public `locations` flow).
     */
    @Test
    fun `seedLocationLabel keeps an existing label`() {
        val locationId = grantedLocationId()
        preferences.value = listOf(SAFLocationEntity(locationId, userLabel = "My Termux", isHidden = true))

        var seeded: String? = null
        val seed = testScope.launch { seeded = manager.seedLocationLabel(locationId, "Termux") }
        testDispatcher.scheduler.runCurrent()

        seed.isCompleted shouldBe true
        seeded shouldBe "My Termux"
        coVerify(exactly = 0) { dao.upsert(any()) }
        preferences.value.single().userLabel shouldBe "My Termux"
    }

    /**
     * Callers refresh right after a label change, so the write must not return before
     * the location cache carries it.
     */
    @Test
    fun `label update waits for the cache to catch up`() {
        val locationId = grantedLocationId()

        var written: SAFLocationEntity? = null
        coEvery { dao.upsert(any()) } answers { written = firstArg() }

        val update = testScope.launch { manager.setLocationLabel(locationId, "Termux") }
        testDispatcher.scheduler.runCurrent()

        written!!.userLabel shouldBe "Termux"
        update.isCompleted shouldBe false

        preferences.value = listOf(written!!)
        testDispatcher.scheduler.runCurrent()

        update.isCompleted shouldBe true
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
     * Register a single granted permission and return its location ID (an MD5 of the tree URI).
     */
    private fun grantedLocationId(): String {
        val permissionUri = Uri.parse("content://$baseAuthority/tree/primary%3AFolderA")
        every { contentResolver.persistedUriPermissions } returns listOf(mockUriPermission(permissionUri))
        refreshCache()
        return manager.findPermissionFor(SAFPath.build(permissionUri))!!.location.id
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
