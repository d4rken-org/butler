package eu.darken.butler.common.files.saf

import android.content.ContentResolver
import android.database.MatrixCursor
import android.net.Uri
import android.provider.DocumentsContract
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import eu.darken.butler.common.files.MoveOutcome
import eu.darken.butler.common.files.SAFPath
import eu.darken.butler.common.files.errors.ReadException
import eu.darken.butler.common.files.errors.WriteException
import eu.darken.butler.common.files.saf.location.SAFLocationManager
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.matchers.nulls.shouldNotBeNull
import io.kotest.matchers.shouldBe
import io.kotest.matchers.types.shouldBeInstanceOf
import io.mockk.coEvery
import io.mockk.every
import io.mockk.mockk
import io.mockk.mockkStatic
import io.mockk.unmockkStatic
import io.mockk.verify
import java.io.ByteArrayOutputStream
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config
import testhelpers.BaseTest
import testhelpers.EmptyApp

/**
 * Write/move correctness tests against the real [SAFFileSystemOps].
 *
 * The in-memory mocks are deliberately more capable than real SAF (create-on-write, rename-aware
 * move), which is how the original bugs escaped testing. These tests drive the real implementation
 * with a simulated provider: document state lives in [docs] (uri -> name/mime), reads go through a
 * mocked [ContentResolver.query], and contract calls (create/rename/move/delete) are intercepted
 * via mockkStatic on [DocumentsContract].
 */
@RunWith(AndroidJUnit4::class)
@Config(sdk = [29], application = EmptyApp::class)
class SAFFileSystemOpsWriteTest : BaseTest() {

    private data class Doc(val name: String, val mime: String)

    private lateinit var resolver: ContentResolver
    private lateinit var locationManager: SAFLocationManager
    private lateinit var ops: SAFFileSystemOps

    private val treeUri = "content://com.android.externalstorage.documents/tree/primary%3A"
    private val docs = mutableMapOf<String, Doc>()
    private val poisonedUris = mutableSetOf<String>()

    private val dirMime = DocumentsContract.Document.MIME_TYPE_DIR

    private fun predictedUri(path: SAFPath): Uri =
        SAFDocFile.fromTreeUri(
            ApplicationProvider.getApplicationContext(),
            resolver,
            SAFDocFile.buildTreeUri(Uri.parse(treeUri), path.segments),
        ).uri

    private fun registerDoc(path: SAFPath, mime: String = "application/octet-stream") {
        docs[predictedUri(path).toString()] = Doc(name = path.segments.lastOrNull() ?: "primary:", mime = mime)
    }

    @Before
    fun setup() {
        docs.clear()
        poisonedUris.clear()
        resolver = mockk(relaxed = true)
        locationManager = mockk()

        every { resolver.query(any(), any(), any(), any(), any()) } answers {
            val uri = firstArg<Uri>()
            val projection = secondArg<Array<String>>()
            if (uri.toString() in poisonedUris) throw UnsupportedOperationException("poisoned: $uri")
            val fillRow: MatrixCursor.(name: String, mime: String, docUri: Uri) -> Unit = { name, mime, docUri ->
                addRow(
                    projection.map<String, Any?> { column ->
                        when (column) {
                            DocumentsContract.Document.COLUMN_DOCUMENT_ID ->
                                DocumentsContract.getDocumentId(docUri)
                            DocumentsContract.Document.COLUMN_DISPLAY_NAME -> name
                            DocumentsContract.Document.COLUMN_MIME_TYPE -> mime
                            DocumentsContract.Document.COLUMN_SIZE -> 0L
                            DocumentsContract.Document.COLUMN_LAST_MODIFIED -> 0L
                            DocumentsContract.Document.COLUMN_FLAGS -> 0
                            else -> null
                        }
                    }.toTypedArray()
                )
            }
            MatrixCursor(projection).apply {
                if (uri.toString().endsWith("/children")) {
                    // Child-documents listing (findFile filters by name client-side)
                    val parentUri = uri.toString().removeSuffix("/children")
                    val prefix = "$parentUri%2F"
                    docs.filterKeys { it.startsWith(prefix) && !it.removePrefix(prefix).contains("%2F") }
                        .forEach { (childUri, doc) -> fillRow(doc.name, doc.mime, Uri.parse(childUri)) }
                } else {
                    docs[uri.toString()]?.let { doc -> fillRow(doc.name, doc.mime, uri) }
                }
            }
        }
        every { resolver.openOutputStream(any(), any()) } answers {
            if (docs.containsKey(firstArg<Uri>().toString())) ByteArrayOutputStream() else null
        }

        coEvery { locationManager.getDocFileFor(any()) } answers {
            val path = firstArg<SAFPath>()
            SAFDocFile.fromTreeUri(
                ApplicationProvider.getApplicationContext(),
                resolver,
                SAFDocFile.buildTreeUri(Uri.parse(treeUri), path.segments),
            )
        }

        mockkStatic(DocumentsContract::class)
        // Contract mutators are simulated against [docs]; static URI helpers keep real behavior.
        // Robolectric has no documents provider installed, so the real isDocumentUri() rejects
        // every URI, which would collapse all document URIs to the tree root. Answer structurally.
        every { DocumentsContract.isDocumentUri(any(), any()) } answers {
            val uri = secondArg<Uri>()
            uri.pathSegments.size >= 4 && uri.pathSegments[0] == "tree" && uri.pathSegments[2] == "document"
        }
        every { DocumentsContract.createDocument(any(), any(), any(), any()) } answers {
            val parentUri = secondArg<Uri>()
            val mime = thirdArg<String>()
            val name = arg<String>(3)
            val newUri = Uri.parse("$parentUri%2F${Uri.encode(name)}")
            docs[newUri.toString()] = Doc(name = name, mime = mime)
            newUri
        }
        every { DocumentsContract.renameDocument(any(), any(), any()) } answers {
            val uri = secondArg<Uri>()
            val newName = thirdArg<String>()
            val doc = docs.remove(uri.toString()) ?: return@answers null
            val newUri = Uri.parse(
                uri.toString().substringBeforeLast("%2F") + "%2F" + Uri.encode(newName),
            )
            docs[newUri.toString()] = doc.copy(name = newName)
            newUri
        }
        every { DocumentsContract.moveDocument(any(), any(), any(), any()) } answers {
            val sourceUri = secondArg<Uri>()
            val destParentUri = arg<Uri>(3)
            val doc = docs.remove(sourceUri.toString()) ?: return@answers null
            val newUri = Uri.parse("$destParentUri%2F${Uri.encode(doc.name)}")
            docs[newUri.toString()] = doc
            newUri
        }
        every { DocumentsContract.deleteDocument(any(), any()) } answers {
            docs.remove(secondArg<Uri>().toString()) != null
        }

        ops = SAFFileSystemOps(
            contentResolver = resolver,
            locationManager = locationManager,
        )

        // Tree root always exists as a directory
        registerDoc(SAFPath.build(treeUri), mime = dirMime)
    }

    @After
    fun teardown() {
        unmockkStatic(DocumentsContract::class)
    }

    private fun path(vararg segments: String) = SAFPath.build(treeUri, *segments)

    // ============ openOutputStream ============

    @Test
    fun `openOutputStream creates a missing file before opening`() = runTest {
        val target = path("new.txt")

        val stream = ops.openOutputStream(target, append = false)

        stream.shouldNotBeNull()
        verify(exactly = 1) { DocumentsContract.createDocument(any(), any(), any(), "new.txt") }
        docs.values.any { it.name == "new.txt" } shouldBe true
    }

    @Test
    fun `openOutputStream in append mode also creates a missing file`() = runTest {
        val target = path("append.txt")

        ops.openOutputStream(target, append = true).shouldNotBeNull()

        verify(exactly = 1) { DocumentsContract.createDocument(any(), any(), any(), "append.txt") }
        verify { resolver.openOutputStream(any(), "wa") }
    }

    @Test
    fun `openOutputStream does not create when the file exists`() = runTest {
        val target = path("existing.txt")
        registerDoc(target)

        ops.openOutputStream(target, append = false).shouldNotBeNull()

        verify(exactly = 0) { DocumentsContract.createDocument(any(), any(), any(), any()) }
    }

    @Test
    fun `openOutputStream treats a failing existence query as an error, not as absence`() = runTest {
        val target = path("flaky.txt")
        every { resolver.query(any(), any(), null, null, null) } throws SecurityException("provider unhappy")

        shouldThrow<WriteException> {
            ops.openOutputStream(target, append = false)
        }

        verify(exactly = 0) { DocumentsContract.createDocument(any(), any(), any(), any()) }
    }

    @Test
    fun `subsequent operations use the provider-returned uri, not the predicted one`() = runTest {
        val target = path("opaque.txt")
        // Simulate an opaque-ID provider: the created document's URI differs from the predicted one
        every { DocumentsContract.createDocument(any(), any(), any(), any()) } answers {
            val name = arg<String>(3)
            val newUri = Uri.parse("$treeUri/document/opaque-id-42")
            docs[newUri.toString()] = Doc(name = name, mime = "application/octet-stream")
            newUri
        }

        ops.openOutputStream(target, append = false).shouldNotBeNull()

        // exists() must resolve through the cached returned URI, not re-predict and miss
        ops.exists(target) shouldBe true
    }

    // ============ move ============

    @Test
    fun `same-parent move renames and never calls moveDocument`() = runTest {
        val source = path("dir", "a.txt")
        registerDoc(SAFPath.build(treeUri, "dir"), mime = dirMime)
        registerDoc(source)

        val outcome = ops.move(source, path("dir", "b.txt"))

        outcome shouldBe MoveOutcome.Moved
        verify(exactly = 1) { DocumentsContract.renameDocument(any(), any(), "b.txt") }
        verify(exactly = 0) { DocumentsContract.moveDocument(any(), any(), any(), any()) }
        docs.values.any { it.name == "b.txt" } shouldBe true
        docs.values.any { it.name == "a.txt" } shouldBe false
    }

    @Test
    fun `cross-parent same-name move uses moveDocument and never renames`() = runTest {
        val source = path("dirA", "a.txt")
        registerDoc(SAFPath.build(treeUri, "dirA"), mime = dirMime)
        registerDoc(SAFPath.build(treeUri, "dirB"), mime = dirMime)
        registerDoc(source)

        val outcome = ops.move(source, path("dirB", "a.txt"))

        outcome shouldBe MoveOutcome.Moved
        verify(exactly = 1) { DocumentsContract.moveDocument(any(), any(), any(), any()) }
        verify(exactly = 0) { DocumentsContract.renameDocument(any(), any(), any()) }
    }

    @Test
    fun `cross-parent rename is refused before any contract call`() = runTest {
        val source = path("dirA", "a.txt")
        registerDoc(SAFPath.build(treeUri, "dirA"), mime = dirMime)
        registerDoc(SAFPath.build(treeUri, "dirB"), mime = dirMime)
        registerDoc(source)

        val outcome = ops.move(source, path("dirB", "b.txt"))

        outcome.shouldBeInstanceOf<MoveOutcome.NotSupported>()
        verify(exactly = 0) { DocumentsContract.moveDocument(any(), any(), any(), any()) }
        verify(exactly = 0) { DocumentsContract.renameDocument(any(), any(), any()) }
        docs.values.any { it.name == "a.txt" } shouldBe true
    }

    @Test
    fun `move into own subtree is refused before any contract call`() = runTest {
        val source = path("dir")
        registerDoc(source, mime = dirMime)

        val outcome = ops.move(source, path("dir", "dir"))

        outcome.shouldBeInstanceOf<MoveOutcome.NotSupported>()
        verify(exactly = 0) { DocumentsContract.moveDocument(any(), any(), any(), any()) }
        verify(exactly = 0) { DocumentsContract.renameDocument(any(), any(), any()) }
    }

    @Test
    fun `move onto an existing destination is refused before any contract call`() = runTest {
        val source = path("a.txt")
        val destination = path("b.txt")
        registerDoc(source)
        registerDoc(destination)

        val outcome = ops.move(source, destination)

        outcome.shouldBeInstanceOf<MoveOutcome.NotSupported>()
        verify(exactly = 0) { DocumentsContract.renameDocument(any(), any(), any()) }
        docs.values.count { it.name == "a.txt" } shouldBe 1
    }

    @Test
    fun `moving a nonexistent path onto itself is an error, not a successful no-op`() = runTest {
        val ghost = path("ghost.txt")

        val thrown = shouldThrow<WriteException> {
            ops.move(ghost, ghost)
        }
        thrown.cause.shouldBeInstanceOf<ReadException>()
    }

    @Test
    fun `post-mutation UnsupportedOperationException is a loud failure, not NotSupported`() = runTest {
        val source = path("a.txt")
        val destination = path("b.txt")
        registerDoc(source)
        // The rename mutates normally, but every query on the moved document afterwards blows up
        // with UnsupportedOperationException — that must not read as "provably nothing mutated".
        every { DocumentsContract.renameDocument(any(), any(), any()) } answers {
            val uri = secondArg<Uri>()
            val doc = docs.remove(uri.toString()) ?: return@answers null
            val newUri = Uri.parse(
                uri.toString().substringBeforeLast("%2F") + "%2F" + Uri.encode(thirdArg<String>()),
            )
            docs[newUri.toString()] = doc.copy(name = thirdArg<String>())
            poisonedUris += newUri.toString()
            newUri
        }

        shouldThrow<WriteException> {
            ops.move(source, destination)
        }
    }

    @Test
    fun `clean UnsupportedOperationException from the contract call is NotSupported`() = runTest {
        val source = path("a.txt")
        registerDoc(source)
        every { DocumentsContract.renameDocument(any(), any(), any()) } throws
            UnsupportedOperationException("not implemented")

        val outcome = ops.move(source, path("b.txt"))

        outcome.shouldBeInstanceOf<MoveOutcome.NotSupported>()
        docs.values.any { it.name == "a.txt" } shouldBe true
    }

    @Test
    fun `UnsupportedOperationException after a mutation is a loud failure, not NotSupported`() = runTest {
        val source = path("a.txt")
        registerDoc(source)
        // Pathological provider: renames, then throws — state verification must catch this
        every { DocumentsContract.renameDocument(any(), any(), any()) } answers {
            val uri = secondArg<Uri>()
            val doc = docs.remove(uri.toString())!!
            val newUri = Uri.parse(
                uri.toString().substringBeforeLast("%2F") + "%2F" + Uri.encode(thirdArg<String>()),
            )
            docs[newUri.toString()] = doc.copy(name = thirdArg<String>())
            throw UnsupportedOperationException("mutated anyway")
        }

        shouldThrow<WriteException> {
            ops.move(source, path("b.txt"))
        }
    }

    @Test
    fun `null rename result with intact source is NotSupported`() = runTest {
        val source = path("a.txt")
        registerDoc(source)
        every { DocumentsContract.renameDocument(any(), any(), any()) } returns null

        val outcome = ops.move(source, path("b.txt"))

        outcome.shouldBeInstanceOf<MoveOutcome.NotSupported>()
        docs.values.any { it.name == "a.txt" } shouldBe true
    }

    @Test
    fun `null rename result with vanished source is a loud failure`() = runTest {
        val source = path("a.txt")
        registerDoc(source)
        // Provider "fails" the rename but the document is gone afterwards - ambiguous state
        every { DocumentsContract.renameDocument(any(), any(), any()) } answers {
            docs.remove(secondArg<Uri>().toString())
            null
        }

        shouldThrow<WriteException> {
            ops.move(source, path("b.txt"))
        }
    }

    @Test
    fun `move landing under a munged name is corrected by a rename`() = runTest {
        val source = path("a.txt")
        registerDoc(source)
        // First rename attempt lands under a suffixed name, corrective rename then works normally
        var firstRename = true
        val realRename = { uri: Uri, name: String ->
            val doc = docs.remove(uri.toString())
            if (doc == null) {
                null
            } else {
                val newUri = Uri.parse(uri.toString().substringBeforeLast("%2F") + "%2F" + Uri.encode(name))
                docs[newUri.toString()] = doc.copy(name = name)
                newUri
            }
        }
        every { DocumentsContract.renameDocument(any(), any(), any()) } answers {
            val requested = thirdArg<String>()
            val effective = if (firstRename) {
                firstRename = false
                "$requested (1)"
            } else {
                requested
            }
            realRename(secondArg(), effective)
        }

        val outcome = ops.move(source, path("b.txt"))

        outcome shouldBe MoveOutcome.Moved
        docs.values.any { it.name == "b.txt" } shouldBe true
        docs.values.any { it.name == "b.txt (1)" } shouldBe false
    }

    // ============ createFile name munging ============

    @Test
    fun `provider-munged create name is corrected by a rename`() = runTest {
        val target = path("wanted.txt")
        every { DocumentsContract.createDocument(any(), any(), any(), any()) } answers {
            val name = arg<String>(3)
            val munged = "$name (1)"
            val newUri = Uri.parse("${secondArg<Uri>()}%2F${Uri.encode(munged)}")
            docs[newUri.toString()] = Doc(name = munged, mime = thirdArg())
            newUri
        }

        ops.createFile(target, createParents = false)

        verify(exactly = 1) { DocumentsContract.renameDocument(any(), any(), "wanted.txt") }
        docs.values.any { it.name == "wanted.txt" } shouldBe true
    }

    @Test
    fun `uncorrectable munged create is cleaned up and fails loudly`() = runTest {
        val target = path("wanted.txt")
        every { DocumentsContract.createDocument(any(), any(), any(), any()) } answers {
            val munged = "${arg<String>(3)} (1)"
            val newUri = Uri.parse("${secondArg<Uri>()}%2F${Uri.encode(munged)}")
            docs[newUri.toString()] = Doc(name = munged, mime = thirdArg())
            newUri
        }
        every { DocumentsContract.renameDocument(any(), any(), any()) } returns null

        shouldThrow<WriteException> {
            ops.createFile(target, createParents = false)
        }

        // The unexpected document was cleaned up rather than orphaned
        docs.values.any { it.name == "wanted.txt (1)" } shouldBe false
    }
}
