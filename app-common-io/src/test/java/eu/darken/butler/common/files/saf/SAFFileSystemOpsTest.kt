package eu.darken.butler.common.files.saf

import android.content.ContentProviderClient
import android.content.ContentResolver
import android.database.Cursor
import android.database.MatrixCursor
import android.net.Uri
import android.os.RemoteException
import android.provider.DocumentsContract
import androidx.test.ext.junit.runners.AndroidJUnit4
import eu.darken.butler.common.files.Existence
import eu.darken.butler.common.files.LookupOptions
import eu.darken.butler.common.files.SAFPath
import eu.darken.butler.common.files.errors.ReadException
import eu.darken.butler.common.files.metadata.FileType
import eu.darken.butler.common.files.permissions.PermissionErrorClassifier
import eu.darken.butler.common.files.saf.location.SAFLocationManager
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.matchers.shouldBe
import io.kotest.matchers.shouldNotBe
import io.mockk.coEvery
import io.mockk.every
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

    // ============ STRICT EXISTENCE TESTS ============

    /**
     * Points [path] at a document whose provider is reached through [client]; a null client is a
     * provider that could not be reached at all.
     */
    private fun probeVia(path: SAFPath, client: ContentProviderClient?) {
        val resolver = mockk<ContentResolver>(relaxed = true)
        every { resolver.acquireUnstableContentProviderClient(PROBE_URI) } returns client
        coEvery { mockLocationManager.getDocFileFor(path) } returns
            SAFDocFile(mockk(relaxed = true), resolver, PROBE_URI)
    }

    private fun providerClient(stub: (ContentProviderClient) -> Unit): ContentProviderClient =
        mockk<ContentProviderClient>(relaxed = true).also(stub)

    private fun docIdCursor(): Cursor = MatrixCursor(arrayOf(DocumentsContract.Document.COLUMN_DOCUMENT_ID))
        .apply { addRow(arrayOf("doc:probe")) }

    @Test
    fun `existsStrict reports a document the provider knows as present`() = runTest {
        val path = SAFPath.build(testTreeUri, "there.txt")
        probeVia(path, providerClient { every { it.query(PROBE_URI, PROBE_PROJECTION, null, null, null) } returns docIdCursor() })

        fileSystemOps.existsStrict(path) shouldBe Existence.PRESENT
    }

    /** A live provider answering with no document is the one definitive absence SAF offers. */
    @Test
    fun `existsStrict reports a document a live provider denies as absent`() = runTest {
        val path = SAFPath.build(testTreeUri, "gone.txt")
        probeVia(path, providerClient { every { it.query(PROBE_URI, PROBE_PROJECTION, null, null, null) } returns null })

        fileSystemOps.existsStrict(path) shouldBe Existence.ABSENT
    }

    @Test
    fun `existsStrict cannot tell without a grant`() = runTest {
        val path = SAFPath.build(testTreeUri, "restricted.txt")
        coEvery { mockLocationManager.getDocFileFor(path) } throws MissingUriPermissionException(path = path)

        fileSystemOps.existsStrict(path) shouldBe Existence.UNKNOWN
    }

    @Test
    fun `existsStrict cannot tell when no provider answers`() = runTest {
        val path = SAFPath.build(testTreeUri, "unreachable.txt")
        probeVia(path, client = null)

        fileSystemOps.existsStrict(path) shouldBe Existence.UNKNOWN
    }

    @Test
    fun `existsStrict cannot tell when the provider dies mid-query`() = runTest {
        val path = SAFPath.build(testTreeUri, "dying.txt")
        probeVia(path, providerClient {
            every { it.query(PROBE_URI, PROBE_PROJECTION, null, null, null) } throws RemoteException("died")
        })

        fileSystemOps.existsStrict(path) shouldBe Existence.UNKNOWN
    }

    @Test
    fun `existsStrict cannot tell when the provider refuses access`() = runTest {
        val path = SAFPath.build(testTreeUri, "refused.txt")
        probeVia(path, providerClient {
            every { it.query(PROBE_URI, PROBE_PROJECTION, null, null, null) } throws SecurityException("nope")
        })

        fileSystemOps.existsStrict(path) shouldBe Existence.UNKNOWN
    }

    /** Only the provider quirks that mean "removed" read as absent, other argument errors do not. */
    @Test
    fun `existsStrict cannot tell on an unrelated argument error`() = runTest {
        val path = SAFPath.build(testTreeUri, "odd.txt")
        probeVia(path, providerClient {
            every {
                it.query(PROBE_URI, PROBE_PROJECTION, null, null, null)
            } throws IllegalArgumentException("Unknown URI")
        })

        fileSystemOps.existsStrict(path) shouldBe Existence.UNKNOWN
    }

    @Test
    fun `a document the provider marks read-only is a permission failure`() = runTest {
        val path = SAFPath.build(testTreeUri, "readonly.txt")
        val docFile = mockk<SAFDocFile> {
            every { writable } returns false
            every { readable } returns true
        }
        coEvery { mockLocationManager.getDocFileFor(path) } returns docFile

        val error = shouldThrow<ReadException> { fileSystemOps.file(path, readWrite = true) }

        PermissionErrorClassifier.isPermissionError(error) shouldBe true
    }

    companion object {
        private val PROBE_URI: Uri = Uri.parse("content://com.android.externalstorage.documents/document/probe")
        private val PROBE_PROJECTION = arrayOf(DocumentsContract.Document.COLUMN_DOCUMENT_ID)
    }
}
