package eu.darken.butler.saftestprovider

import android.Manifest
import android.net.Uri
import android.os.Binder
import android.os.Bundle
import android.os.Process
import android.provider.DocumentsContract
import android.provider.DocumentsContract.Document
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import org.robolectric.shadows.ShadowBinder

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28])
class TestControlsProviderTest {
    private lateinit var documents: TestDocumentsProvider
    private lateinit var controls: TestControlsProvider
    private val uri = Uri.parse("content://${TestControlsProvider.AUTHORITY}")

    @Before
    fun setup() {
        documents = Robolectric.buildContentProvider(TestDocumentsProvider::class.java).create().get()
        controls = Robolectric.buildContentProvider(TestControlsProvider::class.java).create().get()
        control("reset")
    }

    @After
    fun teardown() {
        ShadowBinder.reset()
    }

    @Test
    fun `manifest exports controls without a permission and keeps documents protected in the same process`() {
        val pm = RuntimeEnvironment.getApplication().packageManager
        val controlInfo = requireNotNull(pm.resolveContentProvider(TestControlsProvider.AUTHORITY, 0))
        val documentInfo = requireNotNull(pm.resolveContentProvider(TestDocumentsProvider.AUTHORITY, 0))
        assertTrue(controlInfo.exported)
        assertNull(controlInfo.readPermission)
        assertNull(controlInfo.writePermission)
        assertFalse(controlInfo.grantUriPermissions)
        assertEquals(documentInfo.processName, controlInfo.processName)
        assertEquals(Manifest.permission.MANAGE_DOCUMENTS, documentInfo.readPermission)
        assertEquals(Manifest.permission.MANAGE_DOCUMENTS, documentInfo.writePermission)
        assertTrue(documentInfo.grantUriPermissions)
    }

    @Test
    fun `shell and self reach the existing documents instance and reset its state`() {
        val session = control("stats").getString("session")
        documents.createDocument("70", "text/plain", "temporary.txt")
        listOf(2000, Process.myUid()).forEach { uid ->
            ShadowBinder.setCallingUid(uid)
            val stats = control("stats")
            assertEquals(session, stats.getString("session"))
            assertEquals(1L, stats.getLong("createDocument"))
            assertEquals(1L, stats.getBundle("counters")!!.getLong("createDocument"))
            assertEquals(uid, Binder.getCallingUid())
        }
        ShadowBinder.setCallingUid(2000)
        val reset = control("reset")
        assertNotEquals(session, reset.getString("session"))
        assertEquals(reset.getString("session"), documents.call("stats", null, null)!!.getString("session"))
        assertEquals(0L, control("stats").getLong("totalEntries"))
        documents.queryChildDocuments("70", null, sortOrder = null).use { assertEquals(0, it.count) }
        assertEquals(2000, Binder.getCallingUid())
    }

    @Test
    fun `shell forwards every folder control with arguments and extras`() {
        ShadowBinder.setCallingUid(2000)
        assertEquals("50", control("completeLoading", "loading").getString("documentId"))
        documents.queryChildDocuments("50", null, sortOrder = null).use {
            assertEquals(2, it.count)
            assertFalse(it.extras.getBoolean(DocumentsContract.EXTRA_LOADING))
        }
        control("setLoading", extras = Bundle().apply { putString("folder", "loading") })
        documents.queryChildDocuments("50", null, sortOrder = null).use {
            assertEquals(1, it.count)
            assertTrue(it.extras.getBoolean(DocumentsContract.EXTRA_LOADING))
        }
        control("setError", "empty", Bundle().apply { putString("message", "Injected failure") })
        documents.queryChildDocuments("70", null, sortOrder = null).use {
            assertEquals("Injected failure", it.extras.getString(DocumentsContract.EXTRA_ERROR))
        }
        control("clearError", "empty")
        documents.queryChildDocuments("70", null, sortOrder = null).use {
            assertFalse(it.extras.containsKey(DocumentsContract.EXTRA_ERROR))
        }
        repeat(2) {
            control("makeDuplicate", "deep/a/b", Bundle().apply { putString("name", "c") })
        }
        documents.queryChildDocuments("82", null, sortOrder = null).use {
            assertEquals(2, it.count)
            while (it.moveToNext()) {
                assertEquals("c", it.getString(it.getColumnIndexOrThrow(Document.COLUMN_DISPLAY_NAME)))
            }
        }
    }

    @Test
    fun `stats preserves journal payload and typed pagination extras`() {
        documents.queryDocument("42", null).close()
        documents.queryDocument("43", null).close()
        documents.queryDocument("85", null).close()
        ShadowBinder.setCallingUid(2000)
        val page = control("stats", extras = Bundle().apply {
            putLong("after", 1L)
            putInt("limit", 1)
        })
        assertEquals(3L, page.getLong("queryDocument"))
        assertEquals(3L, page.getLong("totalEntries"))
        assertEquals(2L, page.getLong("nextAfter"))
        assertTrue(page.getBoolean("hasMore"))
        val fields = page.getString("journal")!!.split('\t')
        assertEquals(listOf("2", "queryDocument", "43"), fields.take(3))
        assertEquals(5, fields.size)
        assertEquals(3L, control("stats").getLong("totalEntries"))
    }

    @Test
    fun `foreign app root and system UIDs cannot read stats or mutate the fixture`() {
        val session = control("stats").getString("session")
        listOf(12345, 0, 1000).forEach { uid ->
            ShadowBinder.setCallingUid(uid)
            listOf("stats", "reset", "unknown", "android:isChildDocument").forEach { method ->
                assertThrows(SecurityException::class.java) { control(method) }
                assertEquals(uid, Binder.getCallingUid())
            }
        }
        ShadowBinder.reset()
        assertEquals(session, control("stats").getString("session"))
        assertEquals(0L, control("stats").getLong("totalEntries"))
    }

    @Test
    fun `invalid controls propagate failures without changing caller identity or journal`() {
        ShadowBinder.setCallingUid(2000)
        listOf("unknown", "android:deleteDocument").forEach { method ->
            assertThrows(IllegalArgumentException::class.java) { control(method) }
        }
        assertThrows(IllegalArgumentException::class.java) { control("setError", "empty") }
        assertThrows(IllegalStateException::class.java) { control("setLoading", "missing") }
        assertThrows(IllegalArgumentException::class.java) {
            control("stats", extras = Bundle().apply { putLong("after", 1L) })
        }
        assertEquals(2000, Binder.getCallingUid())
        assertEquals(0L, control("stats").getLong("totalEntries"))
    }

    @Test
    fun `control authority exposes no CRUD operations`() {
        assertThrows(UnsupportedOperationException::class.java) { controls.query(uri, null, null, null, null) }
        assertThrows(UnsupportedOperationException::class.java) { controls.getType(uri) }
        assertThrows(UnsupportedOperationException::class.java) { controls.insert(uri, null) }
        assertThrows(UnsupportedOperationException::class.java) { controls.delete(uri, null, null) }
        assertThrows(UnsupportedOperationException::class.java) { controls.update(uri, null, null, null) }
    }

    private fun control(method: String, arg: String? = null, extras: Bundle? = null): Bundle =
        requireNotNull(RuntimeEnvironment.getApplication().contentResolver.call(uri, method, arg, extras))
}
