package eu.darken.butler.common.files.saf

import android.content.ContentResolver
import androidx.test.ext.junit.runners.AndroidJUnit4
import eu.darken.butler.common.files.LookupOptions
import eu.darken.butler.common.files.SAFPath
import eu.darken.butler.common.files.errors.ReadException
import eu.darken.butler.common.files.metadata.FileType
import eu.darken.butler.common.files.saf.location.SAFLocationManager
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.matchers.shouldBe
import io.kotest.matchers.shouldNotBe
import io.mockk.coEvery
import io.mockk.mockk
import kotlinx.coroutines.test.runTest
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config
import testhelpers.BaseTest
import testhelpers.EmptyApp

/**
 * Tests for SAFFileSystemOps using Robolectric.
 *
 * Tests the actual SAFFileSystemOps implementation (not MockSAFFileSystemOps)
 * to ensure it correctly handles:
 * - fallbackToUnknown option in lookup()
 * - Permission errors
 * - Non-existent paths
 *
 * This prevents regressions like the fallbackToUnknown bug that was introduced
 * during refactoring and wasn't caught because only mocks were tested.
 */
@RunWith(AndroidJUnit4::class)
@Config(sdk = [29], application = EmptyApp::class)
class SAFFileSystemOpsTest : BaseTest() {

    private lateinit var mockContentResolver: ContentResolver
    private lateinit var mockLocationManager: SAFLocationManager
    private lateinit var fileSystemOps: SAFFileSystemOps

    // Valid SAF tree URI for testing
    private val testTreeUri = "content://com.android.externalstorage.documents/tree/primary%3A"

    @Before
    fun setup() {
        mockContentResolver = mockk(relaxed = true)
        mockLocationManager = mockk()

        fileSystemOps = SAFFileSystemOps(
            contentResolver = mockContentResolver,
            locationManager = mockLocationManager
        )
    }

    // ============ FALLBACKTOUNKNOWN BEHAVIOR TESTS ============

    @Test
    fun `lookup with fallbackToUnknown=true returns UNKNOWN for non-existent path`() = runTest {
        // Given - path doesn't exist (permission exists but path doesn't)
        val path = SAFPath.build(testTreeUri, "nonexistent.txt")
        coEvery { mockLocationManager.getDocFileFor(path) } returns null

        // When
        val lookup = fileSystemOps.lookup(path, LookupOptions(fallbackToUnknown = true))

        // Then - should return UNKNOWN instead of throwing
        lookup.lookedUp shouldBe path
        lookup.fileType shouldBe FileType.UNKNOWN
        lookup.size shouldBe null
        lookup.modifiedAt shouldBe null
        lookup.error shouldNotBe null // Should capture the underlying exception
    }

    @Test
    fun `lookup with fallbackToUnknown=false throws ReadException for non-existent path`() = runTest {
        // Given - path doesn't exist
        val path = SAFPath.build(testTreeUri, "nonexistent.txt")
        coEvery { mockLocationManager.getDocFileFor(path) } returns null

        // When/Then - should throw ReadException
        shouldThrow<ReadException> {
            fileSystemOps.lookup(path, LookupOptions(fallbackToUnknown = false))
        }
    }

    @Test
    fun `lookup with fallbackToUnknown=true returns UNKNOWN when permission missing`() = runTest {
        // Given - no permission for path (common scenario)
        val path = SAFPath.build(testTreeUri, "restricted.txt")
        coEvery { mockLocationManager.getDocFileFor(path) } throws
            MissingUriPermissionException(path = path)

        // When
        val lookup = fileSystemOps.lookup(path, LookupOptions(fallbackToUnknown = true))

        // Then - should return UNKNOWN instead of throwing
        lookup.lookedUp shouldBe path
        lookup.fileType shouldBe FileType.UNKNOWN
        lookup.size shouldBe null
        lookup.modifiedAt shouldBe null
        lookup.error shouldNotBe null
    }

    @Test
    fun `lookup with fallbackToUnknown=false throws when permission missing`() = runTest {
        // Given - no permission for path
        val path = SAFPath.build(testTreeUri, "restricted.txt")
        coEvery { mockLocationManager.getDocFileFor(path) } throws
            MissingUriPermissionException(path = path)

        // When/Then - should throw ReadException
        shouldThrow<ReadException> {
            fileSystemOps.lookup(path, LookupOptions(fallbackToUnknown = false))
        }
    }

    @Test
    fun `lookup default options (fallbackToUnknown=false) throws for non-existent`() = runTest {
        // Given - path doesn't exist
        val path = SAFPath.build(testTreeUri, "default.txt")
        coEvery { mockLocationManager.getDocFileFor(path) } returns null

        // When/Then - LookupOptions() defaults to fallbackToUnknown=false
        shouldThrow<ReadException> {
            fileSystemOps.lookup(path, LookupOptions())
        }
    }
}
