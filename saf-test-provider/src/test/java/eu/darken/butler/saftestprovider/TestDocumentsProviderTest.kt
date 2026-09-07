package eu.darken.butler.saftestprovider

import android.Manifest
import android.content.pm.ProviderInfo
import android.database.ContentObserver
import android.net.Uri
import android.os.Bundle
import android.os.ParcelFileDescriptor
import android.provider.DocumentsContract
import android.provider.DocumentsContract.Document
import android.provider.DocumentsContract.Root
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import org.robolectric.shadows.ShadowBinder
import java.io.FileNotFoundException

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28])
class TestDocumentsProviderTest {
    private lateinit var provider: TestDocumentsProvider

    @Before
    fun setup() {
        provider = newProvider()
        control("reset")
    }

    @Test
    fun `root advertises ancestry and cold subtree grants reach deep descendants`() {
        provider.queryRoots(null).use { cursor ->
            assertTrue(cursor.moveToFirst())
            val flags = cursor.getInt(cursor.getColumnIndexOrThrow(Root.COLUMN_FLAGS))
            assertTrue(flags and Root.FLAG_SUPPORTS_IS_CHILD != 0)
            assertTrue(flags and Root.FLAG_SUPPORTS_CREATE != 0)
            assertFalse(cursor.moveToNext())
        }
        assertTrue(provider.isChildDocument("82", "83"))
        assertTrue(provider.isChildDocument("82", "84"))
        assertTrue(provider.isChildDocument("82", "85"))
        assertTrue(provider.isChildDocument("1", "85"))
        assertFalse(provider.isChildDocument("82", "82"))
        assertFalse(provider.isChildDocument("82", "81"))
        assertFalse(provider.isChildDocument("82", "42"))
        assertFalse(provider.isChildDocument("82", "999"))
        assertFalse(provider.isChildDocument("999", "85"))
        assertFalse(provider.isChildDocument("85", "82"))
        assertEquals(0L, stats().getLong("queryChildDocuments"))
        val uri = DocumentsContract.buildDocumentUriUsingTree(
            DocumentsContract.buildTreeDocumentUri(TestDocumentsProvider.AUTHORITY, "82"), "85",
        )
        provider.query(uri, null, Bundle.EMPTY, null).use { cursor ->
            assertNotNull(cursor)
            assertTrue(cursor!!.moveToFirst())
            assertEquals("file.txt", cursor.getString(cursor.getColumnIndexOrThrow(Document.COLUMN_DISPLAY_NAME)))
        }
    }

    @Test
    fun `malformed scenarios are below valid folders and use opaque distinct IDs`() {
        assertTrue(children("1").all { it.second != null })
        val duplicates = children("20").filter { it.second == "dup.txt" }
        assertEquals(listOf("44", "45"), duplicates.map { it.first })
        assertNotEquals(read("44"), read("45"))
        assertEquals("other.txt", children("20").last().second)
        assertEquals(listOf(null, "visible.txt"), children("30").map { it.second })
        assertTrue(children("70").isEmpty())
        listOf("61", "62").forEachIndexed { count, id ->
            provider.queryChildDocuments(id, null, sortOrder = null).use {
                assertEquals(count, it.count)
                assertNotNull(it.extras.getString(DocumentsContract.EXTRA_ERROR))
            }
        }
        provider.queryDocument("42", arrayOf(Document.COLUMN_DISPLAY_NAME, "unknown", Document.COLUMN_DOCUMENT_ID)).use {
            assertTrue(it.moveToFirst())
            assertEquals("hello.txt", it.getString(0))
            assertTrue(it.isNull(1))
            assertEquals("42", it.getString(2))
        }
    }

    @Test
    fun `loading notifies the canonical URI only after full state is visible`() {
        val uri = DocumentsContract.buildChildDocumentsUri(TestDocumentsProvider.AUTHORITY, "50")
        val observations = mutableListOf<Pair<Int, Boolean>>()
        val resolver = RuntimeEnvironment.getApplication().contentResolver
        val observer = object : ContentObserver(null) {
            override fun onChange(selfChange: Boolean, changedUri: Uri?) {
                assertEquals(uri, changedUri)
                provider.queryChildDocuments("50", null, sortOrder = null).use {
                    observations.add(it.count to it.extras.getBoolean(DocumentsContract.EXTRA_LOADING))
                }
            }
        }
        provider.queryChildDocuments("50", null, sortOrder = null).use {
            assertEquals(uri, it.notificationUri)
            assertEquals(1, it.count)
            assertTrue(it.extras.getBoolean(DocumentsContract.EXTRA_LOADING))
        }
        resolver.registerContentObserver(uri, false, observer)
        try {
            repeat(2) { provider.queryChildDocuments("50", null, sortOrder = null).close() }
            assertTrue(observations.isEmpty())
            control("completeLoading", "loading")
            assertEquals(listOf(2 to false), observations)
            control("completeLoading", "loading")
            assertEquals(1, observations.size)
            control("setLoading", "loading")
            control("setLoading", "loading")
            assertEquals(listOf(2 to false, 1 to true), observations)
        } finally {
            resolver.unregisterContentObserver(observer)
        }
        listOf("1", "10", "20", "30", "61", "62", "70", "82", "90").forEach { id ->
            provider.queryChildDocuments(id, null, sortOrder = null).use {
                assertEquals(DocumentsContract.buildChildDocumentsUri(TestDocumentsProvider.AUTHORITY, id), it.notificationUri)
            }
        }
    }

    @Test
    fun `error controls and duplicate directory injection are idempotent`() {
        control("clearError", "errored/partial")
        control("clearError", "errored/partial")
        provider.queryChildDocuments("62", null, sortOrder = null).use {
            assertFalse(it.extras.containsKey(DocumentsContract.EXTRA_ERROR))
            assertEquals(1, it.count)
        }
        repeat(2) {
            control("setError", "empty", Bundle().apply { putString("message", "Injected failure") })
        }
        provider.queryChildDocuments("70", null, sortOrder = null).use {
            assertEquals("Injected failure", it.extras.getString(DocumentsContract.EXTRA_ERROR))
            assertEquals(0, it.count)
        }
        assertTrue(provider.isChildDocument("82", "85"))
        provider.queryDocument("85", null).close()
        repeat(2) {
            control("makeDuplicate", "deep/a/b", Bundle().apply { putString("name", "c") })
        }
        val duplicates = children("82")
        assertEquals(listOf("c", "c"), duplicates.map { it.second })
        assertEquals(2, duplicates.map { it.first }.distinct().size)
        assertTrue(provider.isChildDocument("82", "85"))
        assertFalse(provider.isChildDocument(duplicates.last().first, "85"))
    }

    @Test
    fun `journal counts attempted methods and supports negative mutation assertions and paging`() {
        val initial = stats()
        provider.queryDocument("42", null).close()
        provider.queryChildDocuments("50", null, sortOrder = null).close()
        assertThrows(FileNotFoundException::class.java) { provider.openDocument("999", "r", null) }
        val afterReads = stats()
        assertEquals(initial.getString("session"), afterReads.getString("session"))
        assertEquals(0L, afterReads.getLong("createDocument"))
        assertEquals(0L, afterReads.getLong("deleteDocument"))
        assertEquals(0L, afterReads.getLong("renameDocument"))
        assertEquals(1L, afterReads.getLong("openDocument"))
        assertEquals(3L, afterReads.getLong("totalEntries"))
        val entries = afterReads.getString("journal")!!.lines().map { it.split('\t') }
        assertEquals(listOf("queryDocument", "queryChildDocuments", "openDocument"), entries.map { it[1] })
        assertEquals(listOf("42", "50", "999"), entries.map { it[2] })
        assertEquals(listOf("1", "2", "3"), entries.map { it[0] })
        assertTrue(entries.zipWithNext().all { (a, b) -> a[3].toLong() <= b[3].toLong() })
        val page = control("stats", extras = Bundle().apply {
            putLong("after", 1)
            putInt("limit", 1)
        })
        assertTrue(page.getBoolean("hasMore"))
        assertEquals(2L, page.getLong("nextAfter"))
        assertEquals("queryChildDocuments", page.getString("journal")!!.split('\t')[1])
        control("reset")
        val reset = stats()
        assertNotEquals(initial.getString("session"), reset.getString("session"))
        assertEquals(0L, reset.getLong("totalEntries"))
        assertEquals("", reset.getString("journal"))
        assertEquals(0L, reset.getLong("openDocument"))
    }

    @Test
    fun `writes read back and renames change IDs while preserving descendant ancestry`() {
        val folder = provider.createDocument("90", Document.MIME_TYPE_DIR, "new-folder")
        val file = provider.createDocument(folder, "text/plain", "written.txt")
        write(file, "Written through a file descriptor")
        assertEquals("Written through a file descriptor", read(file))
        val renamedFile = provider.renameDocument(file, "renamed.txt")
        assertNotEquals(file, renamedFile)
        assertEquals("Written through a file descriptor", read(renamedFile))
        assertThrows(FileNotFoundException::class.java) { provider.queryDocument(file, null) }
        val renamedFolder = provider.renameDocument(folder, "renamed-folder")
        assertTrue(provider.isChildDocument(renamedFolder, renamedFile))
        assertTrue(provider.isChildDocument("90", renamedFile))
        assertFalse(provider.isChildDocument(folder, renamedFile))
        provider.deleteDocument(renamedFolder)
        assertThrows(FileNotFoundException::class.java) { provider.openDocument(renamedFile, "r", null) }
        val stats = stats()
        assertEquals(2L, stats.getLong("createDocument"))
        assertEquals(2L, stats.getLong("renameDocument"))
        assertEquals(1L, stats.getLong("deleteDocument"))
    }

    @Test
    fun `restart reproduces seed IDs and retains seeded file bytes while reset restores exact contents`() {
        val original = read("42")
        write("42", "Persisted bytes")
        val oldSession = stats().getString("session")
        provider = newProvider()
        assertEquals("Persisted bytes", read("42"))
        assertEquals(listOf("42", "43"), children("10").map { it.first })
        assertNotEquals(oldSession, stats().getString("session"))
        provider.createDocument("70", "text/plain", "temporary.txt")
        control("completeLoading", "loading")
        control("reset")
        assertEquals(original, read("42"))
        assertTrue(children("70").isEmpty())
        provider.queryChildDocuments("50", null, sortOrder = null).use {
            assertTrue(it.extras.getBoolean(DocumentsContract.EXTRA_LOADING))
        }
    }

    @Test
    fun `custom controls allow shell and reject another application while platform dispatch survives`() {
        ShadowBinder.setCallingUid(2000)
        assertNotNull(control("stats"))
        ShadowBinder.setCallingUid(12345)
        try {
            assertThrows(SecurityException::class.java) { control("reset") }
            assertNull(provider.call("unknown", null, null))
        } finally {
            ShadowBinder.reset()
        }
        val args = Bundle().apply {
            putParcelable("uri", DocumentsContract.buildDocumentUri(TestDocumentsProvider.AUTHORITY, "82"))
            putParcelable("android.content.extra.TARGET_URI", DocumentsContract.buildDocumentUri(TestDocumentsProvider.AUTHORITY, "85"))
        }
        assertTrue(provider.call("android:isChildDocument", null, args)!!.getBoolean("result"))
    }

    private fun newProvider() = TestDocumentsProvider().apply {
        attachInfo(RuntimeEnvironment.getApplication(), ProviderInfo().apply {
            authority = TestDocumentsProvider.AUTHORITY
            exported = true
            grantUriPermissions = true
            readPermission = Manifest.permission.MANAGE_DOCUMENTS
            writePermission = Manifest.permission.MANAGE_DOCUMENTS
        })
    }

    private fun control(method: String, folder: String? = null, extras: Bundle? = null): Bundle =
        requireNotNull(provider.call(method, folder, extras))

    private fun stats() = control("stats")

    private fun children(id: String): List<Pair<String, String?>> =
        provider.queryChildDocuments(id, null, sortOrder = null).use { cursor ->
            buildList {
                while (cursor.moveToNext()) {
                    add(cursor.getString(cursor.getColumnIndexOrThrow(Document.COLUMN_DOCUMENT_ID)) to
                        cursor.getString(cursor.getColumnIndexOrThrow(Document.COLUMN_DISPLAY_NAME)))
                }
            }
        }

    private fun read(id: String): String =
        ParcelFileDescriptor.AutoCloseInputStream(provider.openDocument(id, "r", null)).bufferedReader().use { it.readText() }

    private fun write(id: String, contents: String) {
        ParcelFileDescriptor.AutoCloseOutputStream(provider.openDocument(id, "wt", null)).use {
            it.write(contents.toByteArray())
        }
    }
}
