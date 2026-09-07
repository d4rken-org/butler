package eu.darken.butler.common.files.saf

import android.content.ContentProviderClient
import android.content.ContentResolver
import android.content.Context
import android.database.Cursor
import android.database.MatrixCursor
import android.net.Uri
import android.os.Bundle
import android.os.RemoteException
import android.provider.DocumentsContract
import androidx.test.ext.junit.runners.AndroidJUnit4
import eu.darken.butler.common.SafUri
import eu.darken.butler.common.files.Existence
import eu.darken.butler.common.files.LookupOptions
import eu.darken.butler.common.files.SAFPath
import eu.darken.butler.common.files.errors.ReadException
import eu.darken.butler.common.files.errors.WriteException
import eu.darken.butler.common.files.metadata.FileType
import eu.darken.butler.common.files.permissions.PermissionErrorClassifier
import eu.darken.butler.common.files.saf.location.SAFLocation
import eu.darken.butler.common.files.saf.location.SAFLocationManager
import eu.darken.butler.common.files.saf.location.SAFLocationMatch
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.matchers.shouldBe
import io.kotest.matchers.shouldNotBe
import io.mockk.coEvery
import io.mockk.every
import io.mockk.mockk
import io.mockk.mockkStatic
import io.mockk.unmockkStatic
import io.mockk.verify
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config
import testhelpers.BaseTest
import testhelpers.EmptyApp
import kotlin.time.Instant

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

    @After
    fun teardown() {
        unmockkStatic(DocumentsContract::class)
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

    // ============ FOREIGN PROVIDER RESOLUTION TESTS ============

    /**
     * A document of a provider whose ids are opaque: "42" is not derivable from a display name, so
     * resolution that synthesizes an id from the tree id plus display names never finds it.
     */
    private data class OpaqueDoc(val id: String, val name: String?, val mime: String)

    private val opaqueDocs = mutableMapOf<String, OpaqueDoc>()
    private val opaqueChildren = mutableMapOf<String, MutableList<OpaqueDoc>>()
    private val opaqueListingExtras = mutableMapOf<String, Bundle>()
    private val opaqueContext: Context = mockk(relaxed = true)
    private val queriedProjections = mutableListOf<List<String>>()
    private var onChildrenQuery: (() -> Unit)? = null

    private val opaqueRoot: SAFPath get() = SAFPath.build(OPAQUE_TREE)

    private val opaqueRootDocUri: Uri get() = SAFDocFile.buildTreeUri(Uri.parse(OPAQUE_TREE), emptyList())

    private fun opaqueDocUri(id: String): Uri = DocumentsContract.buildDocumentUriUsingTree(opaqueRootDocUri, id)

    private fun opaqueChildrenUri(id: String): Uri =
        DocumentsContract.buildChildDocumentsUriUsingTree(opaqueRootDocUri, id)

    private fun addOpaqueChild(parentId: String, id: String, name: String?, mime: String = "text/plain") {
        val doc = OpaqueDoc(id = id, name = name, mime = mime)
        opaqueDocs[id] = doc
        opaqueChildren.getOrPut(parentId) { mutableListOf() }.add(doc)
    }

    private fun removeOpaqueDoc(id: String) {
        opaqueDocs.remove(id)
        opaqueChildren.remove(id)
        opaqueChildren.values.forEach { siblings -> siblings.removeAll { it.id == id } }
    }

    private fun rowFor(projection: Array<String>, doc: OpaqueDoc): Array<Any?> = projection.map { column ->
        when (column) {
            DocumentsContract.Document.COLUMN_DOCUMENT_ID -> doc.id
            DocumentsContract.Document.COLUMN_DISPLAY_NAME -> doc.name
            DocumentsContract.Document.COLUMN_MIME_TYPE -> doc.mime
            DocumentsContract.Document.COLUMN_SIZE -> 0L
            DocumentsContract.Document.COLUMN_LAST_MODIFIED -> 0L
            DocumentsContract.Document.COLUMN_FLAGS -> 0
            else -> null
        }
    }.toTypedArray()

    /**
     * Points the resolver and the location manager at an in-memory provider with opaque ids.
     *
     * The tree document id and the root document id agree, as they do on a real provider:
     * `getDocFileFor` builds the root URI through [SAFDocFile.buildTreeUri], which reads the tree
     * document id, and a fixture where the two differ would mock that relationship away.
     */
    private fun useOpaqueProvider() {
        opaqueDocs[OPAQUE_ROOT_ID] = OpaqueDoc(OPAQUE_ROOT_ID, "opaque", DocumentsContract.Document.MIME_TYPE_DIR)

        every { mockContentResolver.query(any(), any(), any(), any(), any()) } answers {
            val uri = firstArg<Uri>()
            val projection = secondArg<Array<String>>()
            queriedProjections.add(projection.toList())

            val cursor: Cursor? = if (uri.lastPathSegment == "children") {
                onChildrenQuery?.invoke()
                val parentId = DocumentsContract.getDocumentId(uri)
                MatrixCursor(projection).apply {
                    opaqueChildren[parentId].orEmpty().forEach { addRow(rowFor(projection, it)) }
                    opaqueListingExtras[parentId]?.let { extras = it }
                }
            } else {
                opaqueDocs[DocumentsContract.getDocumentId(uri)]
                    ?.let { doc -> MatrixCursor(projection).apply { addRow(rowFor(projection, doc)) } }
            }
            cursor
        }
        // fstat() is best-effort for extended lookups; deny it rather than leave it to a relaxed mock
        every { mockContentResolver.openFileDescriptor(any(), any()) } returns null

        val location = SAFLocation(
            id = "opaque",
            treeUri = SafUri.parse(OPAQUE_TREE),
            path = opaqueRoot,
            hasReadPermission = true,
            hasWritePermission = true,
            grantedAt = Instant.fromEpochMilliseconds(0),
        )
        every { mockLocationManager.findPermissionFor(any()) } answers {
            SAFLocationMatch(location = location, missingSegments = firstArg<SAFPath>().segments)
        }
        // Synthesized resolution stays available, so the tests prove it is not what gets used.
        every { mockLocationManager.getDocFileFor(any()) } answers {
            val path = firstArg<SAFPath>()
            SAFDocFile(
                opaqueContext,
                mockContentResolver,
                SAFDocFile.buildTreeUri(Uri.parse(OPAQUE_TREE), path.segments),
            )
        }
    }

    /**
     * Simulates the contract mutators against the opaque fixture, for the tests that create or delete.
     *
     * Only those: while [DocumentsContract] is static-mocked, a call to it nested inside a `verify`
     * block records a matcher instead of returning a URI, which silently truncates expected URIs.
     */
    private fun useContractMutators() {
        mockkStatic(DocumentsContract::class)

        every { DocumentsContract.createDocument(any(), any(), any(), any()) } answers {
            val id = "created${opaqueDocs.size}"
            addOpaqueChild(
                parentId = DocumentsContract.getDocumentId(secondArg<Uri>()),
                id = id,
                name = arg<String>(3),
                mime = thirdArg<String>(),
            )
            opaqueDocUri(id)
        }
        every { DocumentsContract.deleteDocument(any(), any()) } answers {
            val id = DocumentsContract.getDocumentId(secondArg<Uri>())
            (opaqueDocs[id] != null).also { removeOpaqueDoc(id) }
        }
    }

    /**
     * Routes [SAFDocFile.existsStrict] at the same in-memory documents the resolver serves.
     *
     * Without it the relaxed resolver hands back a relaxed [ContentProviderClient] whose cursor
     * reports no row, so every strict existence probe answers "gone".
     */
    private fun useStrictExistence() {
        every { mockContentResolver.acquireUnstableContentProviderClient(any<Uri>()) } answers {
            mockk<ContentProviderClient>(relaxed = true).also { client ->
                every { client.query(any(), any(), any(), any(), any()) } answers {
                    mockContentResolver.query(firstArg(), secondArg(), thirdArg(), arg(3), arg(4))
                }
            }
        }
    }

    private fun registerOpaqueDir() {
        addOpaqueChild(
            parentId = OPAQUE_ROOT_ID,
            id = "42",
            name = "dir",
            mime = DocumentsContract.Document.MIME_TYPE_DIR,
        )
        addOpaqueChild(parentId = "42", id = "43", name = "a.txt")
    }

    @Test
    fun `a listed child resolves through the provider's handle, not a synthesized one`() = runTest {
        useOpaqueProvider()
        addOpaqueChild(parentId = OPAQUE_ROOT_ID, id = "42", name = "a.txt")
        val child = opaqueRoot.child("a.txt")
        val handleUri = opaqueDocUri("42")
        val synthesizedUri = SAFDocFile.buildTreeUri(Uri.parse(OPAQUE_TREE), listOf("a.txt"))
        val rootChildrenUri = opaqueChildrenUri(OPAQUE_ROOT_ID)

        fileSystemOps.listFiles(opaqueRoot) shouldBe listOf(child)
        // Extended lookups bypass the lookup cache, so this really re-resolves the document
        fileSystemOps.lookup(child, LookupOptions(fetchOwnership = true)).fileType shouldBe FileType.FILE

        verify { mockContentResolver.query(handleUri, any(), any(), any(), any()) }
        verify(exactly = 0) { mockContentResolver.query(synthesizedUri, any(), any(), any(), any()) }
        verify(exactly = 0) { mockLocationManager.getDocFileFor(child) }
        verify(exactly = 1) { mockContentResolver.query(rootChildrenUri, any(), any(), any(), any()) }
    }

    @Test
    fun `foreign provider cache miss resolves by listing the parent`() = runTest {
        useOpaqueProvider()
        addOpaqueChild(parentId = OPAQUE_ROOT_ID, id = "42", name = "a.txt")
        val child = opaqueRoot.child("a.txt")
        val rootChildrenUri = opaqueChildrenUri(OPAQUE_ROOT_ID)
        val handleUri = opaqueDocUri("42")

        fileSystemOps.exists(child) shouldBe true

        verify(exactly = 1) { mockContentResolver.query(rootChildrenUri, any(), any(), any(), any()) }
        verify { mockContentResolver.query(handleUri, any(), any(), any(), any()) }
        verify { mockLocationManager.getDocFileFor(opaqueRoot) }
        verify(exactly = 0) { mockLocationManager.getDocFileFor(child) }
    }

    @Test
    fun `foreign provider resolves a new descendant from a cached ancestor`() = runTest {
        useOpaqueProvider()
        registerOpaqueDir()
        val dir = opaqueRoot.child("dir")
        val dirChildrenUri = opaqueChildrenUri("42")
        val rootChildrenUri = opaqueChildrenUri(OPAQUE_ROOT_ID)

        fileSystemOps.exists(dir) shouldBe true
        fileSystemOps.lookup(dir.child("a.txt"), LookupOptions()).fileType shouldBe FileType.FILE

        verify(exactly = 1) { mockContentResolver.query(dirChildrenUri, any(), any(), any(), any()) }
        verify(exactly = 1) { mockContentResolver.query(rootChildrenUri, any(), any(), any(), any()) }
    }

    @Test
    fun `foreign provider reports an unlisted child as absent`() = runTest {
        useOpaqueProvider()
        addOpaqueChild(parentId = OPAQUE_ROOT_ID, id = "42", name = "a.txt")
        val missing = opaqueRoot.child("nope.txt")

        fileSystemOps.exists(missing) shouldBe false
        fileSystemOps.existsStrict(missing) shouldBe Existence.ABSENT
        fileSystemOps.lookup(missing, LookupOptions(fallbackToUnknown = true)).fileType shouldBe FileType.UNKNOWN
        shouldThrow<ReadException> { fileSystemOps.lookup(missing, LookupOptions()) }

        verify(exactly = 0) {
            mockContentResolver.query(
                match<Uri> { it.toString().contains("nope.txt") },
                any(), any(), any(), any(),
            )
        }
    }

    @Test
    fun `duplicate display names are inconclusive, not absent`() = runTest {
        useOpaqueProvider()
        useContractMutators()
        addOpaqueChild(parentId = OPAQUE_ROOT_ID, id = "42", name = "dup.txt")
        addOpaqueChild(parentId = OPAQUE_ROOT_ID, id = "43", name = "dup.txt")
        val dup = opaqueRoot.child("dup.txt")

        shouldThrow<ReadException> { fileSystemOps.exists(dup) }
        shouldThrow<ReadException> { fileSystemOps.lookup(dup, LookupOptions()) }
        fileSystemOps.existsStrict(dup) shouldBe Existence.UNKNOWN
        shouldThrow<WriteException> { fileSystemOps.createFile(dup, createParents = false) }

        verify(exactly = 0) { DocumentsContract.createDocument(any(), any(), any(), any()) }
    }

    @Test
    fun `a duplicate name stays inconclusive on the next access`() = runTest {
        useOpaqueProvider()
        addOpaqueChild(parentId = OPAQUE_ROOT_ID, id = "42", name = "dup.txt")
        addOpaqueChild(parentId = OPAQUE_ROOT_ID, id = "43", name = "dup.txt")
        val dup = opaqueRoot.child("dup.txt")
        val firstCandidateUri = opaqueDocUri("42")
        val secondCandidateUri = opaqueDocUri("43")

        fileSystemOps.listFiles(opaqueRoot) shouldBe listOf(dup, dup)
        shouldThrow<ReadException> { fileSystemOps.exists(dup) }
        shouldThrow<ReadException> { fileSystemOps.exists(dup) }

        // Neither of the two candidates may be picked as "the" document
        verify(exactly = 0) { mockContentResolver.query(firstCandidateUri, any(), any(), any(), any()) }
        verify(exactly = 0) { mockContentResolver.query(secondCandidateUri, any(), any(), any(), any()) }
    }

    private suspend fun assertPartialListingIsInconclusive(extras: Bundle) {
        useOpaqueProvider()
        useContractMutators()
        addOpaqueChild(parentId = OPAQUE_ROOT_ID, id = "42", name = "a.txt")
        opaqueListingExtras[OPAQUE_ROOT_ID] = extras
        val later = opaqueRoot.child("later.txt")

        fileSystemOps.existsStrict(later) shouldBe Existence.UNKNOWN
        shouldThrow<ReadException> { fileSystemOps.exists(later) }
        shouldThrow<WriteException> { fileSystemOps.createFile(later, createParents = false) }

        verify(exactly = 0) { DocumentsContract.createDocument(any(), any(), any(), any()) }
    }

    @Test
    fun `a listing the provider is still loading is inconclusive, not absent`() = runTest {
        assertPartialListingIsInconclusive(Bundle().apply { putBoolean(DocumentsContract.EXTRA_LOADING, true) })
    }

    @Test
    fun `a listing the provider flagged as errored is inconclusive, not absent`() = runTest {
        assertPartialListingIsInconclusive(Bundle().apply { putString(DocumentsContract.EXTRA_ERROR, "boom") })
    }

    @Test
    fun `externalstorage keeps synthesized resolution`() = runTest {
        val child = SAFPath.build(testTreeUri, "a.txt")
        val docUri = SAFDocFile.buildTreeUri(Uri.parse(testTreeUri), listOf("a.txt"))
        every { mockLocationManager.getDocFileFor(child) } returns
            SAFDocFile(mockk(relaxed = true), mockContentResolver, docUri)
        every { mockContentResolver.query(docUri, any(), any(), any(), any()) } answers {
            val projection = secondArg<Array<String>>()
            MatrixCursor(projection).apply { addRow(rowFor(projection, OpaqueDoc("a.txt", "a.txt", "text/plain"))) }
        }

        fileSystemOps.lookup(child, LookupOptions()).fileType shouldBe FileType.FILE

        verify { mockLocationManager.getDocFileFor(child) }
        verify(exactly = 0) { mockLocationManager.findPermissionFor(any()) }
        verify(exactly = 0) {
            mockContentResolver.query(
                match<Uri> { it.lastPathSegment == "children" },
                any(), any(), any(), any(),
            )
        }
    }

    @Test
    fun `listFiles takes display names from the batch listing`() = runTest {
        useOpaqueProvider()
        addOpaqueChild(parentId = OPAQUE_ROOT_ID, id = "42", name = "a.txt")
        addOpaqueChild(parentId = OPAQUE_ROOT_ID, id = "43", name = "b.txt")

        fileSystemOps.listFiles(opaqueRoot) shouldBe
            listOf(opaqueRoot.child("a.txt"), opaqueRoot.child("b.txt"))

        // The per-child display-name getter is the one that queries this projection alone
        queriedProjections.none { it == listOf(DocumentsContract.Document.COLUMN_DISPLAY_NAME) } shouldBe true
    }

    @Test
    fun `a directory larger than the doc-file cache costs one listing, not one per child`() = runTest {
        useOpaqueProvider()
        val names = (0 until 1001).map { "child$it.txt" }
        names.forEachIndexed { index, name -> addOpaqueChild(parentId = OPAQUE_ROOT_ID, id = "d$index", name = name) }
        // Counted at the fixture: matching 1000+ recorded calls with verify exhausts the heap
        var listings = 0
        onChildrenQuery = { listings++ }

        names.forEach { fileSystemOps.exists(opaqueRoot.child(it)) shouldBe true }

        listings shouldBe 1
    }

    @Test
    fun `a changed directory is re-listed`() = runTest {
        useOpaqueProvider()
        useContractMutators()
        addOpaqueChild(parentId = OPAQUE_ROOT_ID, id = "42", name = "a.txt")
        addOpaqueChild(parentId = OPAQUE_ROOT_ID, id = "43", name = "b.txt")
        val rootChildrenUri = opaqueChildrenUri(OPAQUE_ROOT_ID)

        fileSystemOps.exists(opaqueRoot.child("a.txt")) shouldBe true
        fileSystemOps.delete(opaqueRoot.child("a.txt"), recursive = false) shouldBe true
        fileSystemOps.exists(opaqueRoot.child("b.txt")) shouldBe true

        verify(exactly = 2) { mockContentResolver.query(rootChildrenUri, any(), any(), any(), any()) }
    }

    @Test
    fun `deleting a directory drops its stored listing`() = runTest {
        useOpaqueProvider()
        useContractMutators()
        registerOpaqueDir()
        val dir = opaqueRoot.child("dir")
        val dirChildrenUri = opaqueChildrenUri("42")

        fileSystemOps.listFiles(dir) shouldBe listOf(dir.child("a.txt"))
        fileSystemOps.exists(dir.child("a.txt")) shouldBe true
        verify(exactly = 1) { mockContentResolver.query(dirChildrenUri, any(), any(), any(), any()) }

        fileSystemOps.delete(dir, recursive = true) shouldBe true
        // Another app puts the tree back; the stored listing must not be what answers for it
        registerOpaqueDir()
        fileSystemOps.exists(dir.child("a.txt")) shouldBe true

        // listFiles, the recursive delete walk, and the re-resolve after invalidation
        verify(exactly = 3) { mockContentResolver.query(dirChildrenUri, any(), any(), any(), any()) }
    }

    @Test
    fun `a cancelled resolution cancels the caller`() = runTest {
        useOpaqueProvider()
        addOpaqueChild(parentId = OPAQUE_ROOT_ID, id = "42", name = "a.txt")
        val child = opaqueRoot.child("a.txt")

        val readScope = CoroutineScope(Job() + Dispatchers.Unconfined)
        onChildrenQuery = { readScope.cancel() }
        var readable: Boolean? = null
        readScope.launch { readable = fileSystemOps.canRead(child) }.join()
        // canRead swallows Exception; a cancellation must not surface as "not readable"
        readable shouldBe null

        val fileScope = CoroutineScope(Job() + Dispatchers.Unconfined)
        onChildrenQuery = { fileScope.cancel() }
        var failure: Throwable? = null
        fileScope.launch {
            try {
                fileSystemOps.file(child, readWrite = false)
            } catch (e: Throwable) {
                failure = e
            }
        }.join()
        (failure is CancellationException) shouldBe true
    }

    @Test
    fun `createFile on a foreign provider creates under the parent's real handle`() = runTest {
        useOpaqueProvider()
        useContractMutators()
        addOpaqueChild(
            parentId = OPAQUE_ROOT_ID,
            id = "42",
            name = "dir",
            mime = DocumentsContract.Document.MIME_TYPE_DIR,
        )
        val target = opaqueRoot.child("dir").child("new.txt")
        val parentHandleUri = opaqueDocUri("42")

        fileSystemOps.createFile(target, createParents = false)

        verify { DocumentsContract.createDocument(any(), parentHandleUri, any(), "new.txt") }
    }

    @Test
    fun `a failed move leaves no handle behind in the parent's stored listing`() = runTest {
        useOpaqueProvider()
        useStrictExistence()
        mockkStatic(DocumentsContract::class)
        addOpaqueChild(parentId = OPAQUE_ROOT_ID, id = "42", name = "a.txt")
        val source = opaqueRoot.child("a.txt")
        val destination = opaqueRoot.child("b.txt")
        every { DocumentsContract.renameDocument(any(), any(), any()) } throws RuntimeException("provider blew up")

        // Populates the root listing, which is where the source's handle also lives
        fileSystemOps.exists(source) shouldBe true

        val failure = shouldThrow<WriteException> { fileSystemOps.move(source, destination) }
        // Pins where the move died: anything earlier would mean the fixture, not the rename, refused
        failure.cause?.message shouldBe "provider blew up"

        // The failure may have had side effects, so the source must be genuinely uncached now
        var listings = 0
        onChildrenQuery = { listings++ }
        fileSystemOps.exists(source) shouldBe true

        listings shouldBe 1
    }

    @Test
    fun `a name turning ambiguous drops the descendants resolved under it`() = runTest {
        useOpaqueProvider()
        useStrictExistence()
        registerOpaqueDir()
        val dir = opaqueRoot.child("dir")
        val file = dir.child("a.txt")

        fileSystemOps.existsStrict(file) shouldBe Existence.PRESENT

        // A second document of the same name appears; the parent is re-listed
        addOpaqueChild(
            parentId = OPAQUE_ROOT_ID,
            id = "44",
            name = "dir",
            mime = DocumentsContract.Document.MIME_TYPE_DIR,
        )
        fileSystemOps.listFiles(opaqueRoot) shouldBe listOf(dir, dir)

        fileSystemOps.existsStrict(dir) shouldBe Existence.UNKNOWN
        // The descendant was resolved through the now-ambiguous 'dir'; its handle cannot stand
        fileSystemOps.existsStrict(file) shouldBe Existence.UNKNOWN
    }

    @Test
    fun `a row without a display name cannot prove another name absent`() = runTest {
        useOpaqueProvider()
        addOpaqueChild(parentId = OPAQUE_ROOT_ID, id = "42", name = "a.txt")
        // The provider left COLUMN_DISPLAY_NAME empty, so this document's name is unknown to us
        addOpaqueChild(parentId = OPAQUE_ROOT_ID, id = "43", name = null)
        val unindexable = opaqueRoot.child("secret.txt")

        fileSystemOps.existsStrict(unindexable) shouldBe Existence.UNKNOWN
    }

    companion object {
        private val PROBE_URI: Uri = Uri.parse("content://com.android.externalstorage.documents/document/probe")
        private val PROBE_PROJECTION = arrayOf(DocumentsContract.Document.COLUMN_DOCUMENT_ID)

        private const val OPAQUE_TREE = "content://com.example.opaque/tree/7"
        private const val OPAQUE_ROOT_ID = "7"
    }
}
