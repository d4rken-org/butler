package eu.darken.butler.common.files.saf

import android.content.ContentProviderClient
import android.content.ContentResolver
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.database.Cursor
import android.database.MatrixCursor
import android.net.Uri
import android.os.RemoteException
import android.provider.DocumentsContract
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.matchers.shouldBe
import io.mockk.every
import io.mockk.mockk
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import testhelpers.BaseTest
import java.io.IOException

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [29])
class SAFDocFileTest : BaseTest() {
    private val context: Context = mockk(relaxed = true)
    private val contentResolver: ContentResolver = mockk(relaxed = true)

    @Test
    fun `test tree uri no segments`() {
        SAFDocFile.buildTreeUri(
            Uri.parse("content://auth.ority/tree/primary%3A"),
            emptyList()
        ) shouldBe Uri.parse("content://auth.ority/tree/primary%3A/document/primary%3A")

        SAFDocFile.buildTreeUri(
            Uri.parse("content://auth.ority/tree/primary%3A"),
            emptyList()
        ).toString() shouldBe "content://auth.ority/tree/primary%3A/document/primary%3A"
    }

    @Test
    fun `test tree uri 1 segment`() {
        SAFDocFile.buildTreeUri(
            Uri.parse("content://auth.ority/tree/primary%3A"),
            listOf("segment1")
        ).toString() shouldBe "content://auth.ority/tree/primary%3A/document/primary%3A%2Fsegment1"
    }

    @Test
    fun `test tree uri 2 segments`() {
        SAFDocFile.buildTreeUri(
            Uri.parse("content://auth.ority/tree/primary%3A"),
            listOf("segment1", "segment2")
        ).toString() shouldBe "content://auth.ority/tree/primary%3A/document/primary%3A%2Fsegment1%2Fsegment2"
    }

    @Test
    fun `test tree uri 2 empty segment`() {
        SAFDocFile.buildTreeUri(
            Uri.parse("content://auth.ority/tree/primary%3A"),
            listOf("")
        ).toString() shouldBe "content://auth.ority/tree/primary%3A/document/primary%3A%2F"
    }

    @Test
    fun `tree uri separates repeated segments by position, not by value`() {
        // The third crumb equals the first, so a by-value "am I the first crumb?" check skips its
        // separator and glues the path together. Any real path can hit this: Download/foo/Download.
        SAFDocFile.buildTreeUri(
            Uri.parse("content://auth.ority/tree/primary%3A"),
            listOf("Download", "foo", "Download"),
        ).toString() shouldBe "content://auth.ority/tree/primary%3A/document/primary%3A%2FDownload%2Ffoo%2FDownload"

        SAFDocFile.buildTreeUri(
            Uri.parse("content://auth.ority/tree/primary%3A"),
            listOf("files", "cache", "files"),
        ).toString() shouldBe "content://auth.ority/tree/primary%3A/document/primary%3A%2Ffiles%2Fcache%2Ffiles"
    }

    @Test
    fun `test tree seperator addition`() {
        SAFDocFile.buildTreeUri(
            Uri.parse("content://com.android.externalstorage.documents/tree/primary%3AAndroid%2Fdata"),
            listOf("com.samsung.android.smartmirroring")
        )
            .toString() shouldBe "content://com.android.externalstorage.documents/tree/primary%3AAndroid%2Fdata/document/primary%3AAndroid%2Fdata%2Fcom.samsung.android.smartmirroring"
    }

//    @Test
//    fun `docfile instantiation`() {
//        val fileUri = Uri.parse("content://auth.ority/tree/primary%3A/document/primary%3Asegment1")
//        SAFDocFile.fromTreeUri(
//            context,
//            contentResolver,
//            fileUri
//        ).toString() shouldBe "SAFDocFile(uri=$fileUri)"
//    }

    // Helper to create cursor with MIME type
    private fun createMimeCursor(mimeType: String?): Cursor {
        val cursor = MatrixCursor(arrayOf(DocumentsContract.Document.COLUMN_MIME_TYPE))
        cursor.addRow(arrayOf(mimeType))
        return cursor
    }

    // Helper to create cursor with flags
    private fun createFlagsCursor(flags: Int): Cursor {
        val cursor = MatrixCursor(arrayOf(DocumentsContract.Document.COLUMN_FLAGS))
        cursor.addRow(arrayOf(flags))
        return cursor
    }

    private fun createDocIdCursor(docId: String?): Cursor {
        val cursor = MatrixCursor(arrayOf(DocumentsContract.Document.COLUMN_DOCUMENT_ID))
        cursor.addRow(arrayOf(docId))
        return cursor
    }

    private val strictUri: Uri get() = Uri.parse("content://authority/document/strict")

    private fun clientAnswering(cursor: Cursor?): ContentProviderClient = mockk(relaxed = true) {
        every { query(strictUri, arrayOf(DocumentsContract.Document.COLUMN_DOCUMENT_ID), null, null, null) } returns
            cursor
    }

    @Test
    fun `existsStrict is true when the provider reports the document`() {
        every { contentResolver.acquireUnstableContentProviderClient(strictUri) } returns
            clientAnswering(createDocIdCursor("doc:strict"))

        SAFDocFile(context, contentResolver, strictUri).existsStrict() shouldBe true
    }

    @Test
    fun `existsStrict is false when a live provider reports no document`() {
        // DocumentsProvider.query returns null after catching the FileNotFoundException from
        // queryDocument, so a null cursor from a provider that DID answer means "gone".
        every { contentResolver.acquireUnstableContentProviderClient(strictUri) } returns clientAnswering(null)

        SAFDocFile(context, contentResolver, strictUri).existsStrict() shouldBe false
    }

    @Test
    fun `existsStrict raises instead of reporting absence when no provider answers`() {
        // ContentResolver.query hands back a null cursor for this case too, which is why the check
        // goes through a client: a delete that returned false must not be reported as a success just
        // because the query meant to prove it never reached anyone.
        every { contentResolver.acquireUnstableContentProviderClient(strictUri) } returns null

        shouldThrow<IOException> { SAFDocFile(context, contentResolver, strictUri).existsStrict() }
    }

    @Test
    fun `existsStrict raises when the live provider dies mid-query`() {
        val client = mockk<ContentProviderClient>(relaxed = true)
        every {
            client.query(strictUri, arrayOf(DocumentsContract.Document.COLUMN_DOCUMENT_ID), null, null, null)
        } throws RemoteException("provider died")
        every { contentResolver.acquireUnstableContentProviderClient(strictUri) } returns client

        shouldThrow<IOException> { SAFDocFile(context, contentResolver, strictUri).existsStrict() }
    }

    @Test
    fun `readable returns true for direct URI with read permission and MIME`() {
        val uri = Uri.parse("content://authority/document/123")

        // Mock permission check - direct URI has permission
        every {
            context.checkCallingOrSelfUriPermission(
                uri,
                Intent.FLAG_GRANT_READ_URI_PERMISSION
            )
        } returns PackageManager.PERMISSION_GRANTED

        // Mock MIME type query
        every {
            contentResolver.query(
                uri,
                arrayOf(DocumentsContract.Document.COLUMN_MIME_TYPE),
                null,
                null,
                null
            )
        } returns createMimeCursor("text/plain")

        val docFile = SAFDocFile(context, contentResolver, uri)

        docFile.readable shouldBe true
    }

    @Test
    fun `readable returns false for document without MIME type`() {
        val uri = Uri.parse("content://authority/document/123")

        // Mock permission check
        every {
            context.checkCallingOrSelfUriPermission(
                uri,
                Intent.FLAG_GRANT_READ_URI_PERMISSION
            )
        } returns PackageManager.PERMISSION_GRANTED

        // Mock MIME type query - returns null
        every {
            contentResolver.query(
                uri,
                arrayOf(DocumentsContract.Document.COLUMN_MIME_TYPE),
                null,
                null,
                null
            )
        } returns createMimeCursor(null)

        val docFile = SAFDocFile(context, contentResolver, uri)

        docFile.readable shouldBe false
    }

    @Test
    fun `readable returns false for direct URI without permission`() {
        val uri = Uri.parse("content://authority/document/123")

        // Mock permission check - no permission
        every {
            context.checkCallingOrSelfUriPermission(
                uri,
                Intent.FLAG_GRANT_READ_URI_PERMISSION
            )
        } returns PackageManager.PERMISSION_DENIED

        // Mock MIME type query
        every {
            contentResolver.query(
                uri,
                arrayOf(DocumentsContract.Document.COLUMN_MIME_TYPE),
                null,
                null,
                null
            )
        } returns createMimeCursor("text/plain")

        val docFile = SAFDocFile(context, contentResolver, uri)

        docFile.readable shouldBe false
    }

    @Test
    fun `readable returns true for tree child with tree root permission`() {
        val childUri = Uri.parse("content://authority/tree/primary%3AFolder/document/primary%3AFolder%2Fchild.txt")
        val treeRootUri = Uri.parse("content://authority/tree/primary%3AFolder")

        // Mock permission check - child has no direct permission, but tree root does
        every {
            context.checkCallingOrSelfUriPermission(
                childUri,
                Intent.FLAG_GRANT_READ_URI_PERMISSION
            )
        } returns PackageManager.PERMISSION_DENIED
        every {
            context.checkCallingOrSelfUriPermission(
                treeRootUri,
                Intent.FLAG_GRANT_READ_URI_PERMISSION
            )
        } returns PackageManager.PERMISSION_GRANTED

        // Mock MIME type query
        every {
            contentResolver.query(
                childUri,
                arrayOf(DocumentsContract.Document.COLUMN_MIME_TYPE),
                null,
                null,
                null
            )
        } returns createMimeCursor("text/plain")

        val docFile = SAFDocFile(context, contentResolver, childUri)

        docFile.readable shouldBe true
    }

    @Test
    fun `readable returns false for tree child without tree root permission`() {
        val childUri = Uri.parse("content://authority/tree/primary%3AFolder/document/primary%3AFolder%2Fchild.txt")
        val treeRootUri = Uri.parse("content://authority/tree/primary%3AFolder")

        // Mock permission check - neither child nor tree root have permission
        every {
            context.checkCallingOrSelfUriPermission(
                childUri,
                Intent.FLAG_GRANT_READ_URI_PERMISSION
            )
        } returns PackageManager.PERMISSION_DENIED
        every {
            context.checkCallingOrSelfUriPermission(
                treeRootUri,
                Intent.FLAG_GRANT_READ_URI_PERMISSION
            )
        } returns PackageManager.PERMISSION_DENIED

        // Mock MIME type query
        every {
            contentResolver.query(
                childUri,
                arrayOf(DocumentsContract.Document.COLUMN_MIME_TYPE),
                null,
                null,
                null
            )
        } returns createMimeCursor("text/plain")

        val docFile = SAFDocFile(context, contentResolver, childUri)

        docFile.readable shouldBe false
    }

    @Test
    fun `readable returns true for nested tree child with tree root permission`() {
        val childUri =
            Uri.parse("content://authority/tree/primary%3AFolder/document/primary%3AFolder%2Fsubfolder%2Ffile.txt")
        val treeRootUri = Uri.parse("content://authority/tree/primary%3AFolder")

        // Mock permission check - tree root has permission
        every {
            context.checkCallingOrSelfUriPermission(
                childUri,
                Intent.FLAG_GRANT_READ_URI_PERMISSION
            )
        } returns PackageManager.PERMISSION_DENIED
        every {
            context.checkCallingOrSelfUriPermission(
                treeRootUri,
                Intent.FLAG_GRANT_READ_URI_PERMISSION
            )
        } returns PackageManager.PERMISSION_GRANTED

        // Mock MIME type query
        every {
            contentResolver.query(
                childUri,
                arrayOf(DocumentsContract.Document.COLUMN_MIME_TYPE),
                null,
                null,
                null
            )
        } returns createMimeCursor("text/plain")

        val docFile = SAFDocFile(context, contentResolver, childUri)

        docFile.readable shouldBe true
    }

    @Test
    fun `writable returns true for tree child with tree root write permission and write flags`() {
        val childUri = Uri.parse("content://authority/tree/primary%3AFolder/document/primary%3AFolder%2Ffile.txt")
        val treeRootUri = Uri.parse("content://authority/tree/primary%3AFolder")

        // Mock permission check - tree root has write permission
        every {
            context.checkCallingOrSelfUriPermission(
                childUri,
                Intent.FLAG_GRANT_WRITE_URI_PERMISSION
            )
        } returns PackageManager.PERMISSION_DENIED
        every {
            context.checkCallingOrSelfUriPermission(
                treeRootUri,
                Intent.FLAG_GRANT_WRITE_URI_PERMISSION
            )
        } returns PackageManager.PERMISSION_GRANTED

        // Mock MIME type query
        every {
            contentResolver.query(
                childUri,
                arrayOf(DocumentsContract.Document.COLUMN_MIME_TYPE),
                null,
                null,
                null
            )
        } returns createMimeCursor("text/plain")

        // Mock flags query - supports write
        every {
            contentResolver.query(
                childUri,
                arrayOf(DocumentsContract.Document.COLUMN_FLAGS),
                null,
                null,
                null
            )
        } returns createFlagsCursor(DocumentsContract.Document.FLAG_SUPPORTS_WRITE)

        val docFile = SAFDocFile(context, contentResolver, childUri)

        docFile.writable shouldBe true
    }

    @Test
    fun `writable returns false for tree child without tree root write permission`() {
        val childUri = Uri.parse("content://authority/tree/primary%3AFolder/document/primary%3AFolder%2Ffile.txt")
        val treeRootUri = Uri.parse("content://authority/tree/primary%3AFolder")

        // Mock permission check - no write permission
        every {
            context.checkCallingOrSelfUriPermission(
                childUri,
                Intent.FLAG_GRANT_WRITE_URI_PERMISSION
            )
        } returns PackageManager.PERMISSION_DENIED
        every {
            context.checkCallingOrSelfUriPermission(
                treeRootUri,
                Intent.FLAG_GRANT_WRITE_URI_PERMISSION
            )
        } returns PackageManager.PERMISSION_DENIED

        // Mock MIME type query
        every {
            contentResolver.query(
                childUri,
                arrayOf(DocumentsContract.Document.COLUMN_MIME_TYPE),
                null,
                null,
                null
            )
        } returns createMimeCursor("text/plain")

        val docFile = SAFDocFile(context, contentResolver, childUri)

        docFile.writable shouldBe false
    }

    @Test
    fun `writable returns true for directory with create flag and tree permission`() {
        val childUri = Uri.parse("content://authority/tree/primary%3AFolder/document/primary%3AFolder%2Fsubdir")
        val treeRootUri = Uri.parse("content://authority/tree/primary%3AFolder")

        // Mock permission check - tree root has write permission
        every {
            context.checkCallingOrSelfUriPermission(
                childUri,
                Intent.FLAG_GRANT_WRITE_URI_PERMISSION
            )
        } returns PackageManager.PERMISSION_DENIED
        every {
            context.checkCallingOrSelfUriPermission(
                treeRootUri,
                Intent.FLAG_GRANT_WRITE_URI_PERMISSION
            )
        } returns PackageManager.PERMISSION_GRANTED

        // Mock MIME type query - directory
        every {
            contentResolver.query(
                childUri,
                arrayOf(DocumentsContract.Document.COLUMN_MIME_TYPE),
                null,
                null,
                null
            )
        } returns createMimeCursor(DocumentsContract.Document.MIME_TYPE_DIR)

        // Mock flags query - supports create
        every {
            contentResolver.query(
                childUri,
                arrayOf(DocumentsContract.Document.COLUMN_FLAGS),
                null,
                null,
                null
            )
        } returns createFlagsCursor(DocumentsContract.Document.FLAG_DIR_SUPPORTS_CREATE)

        val docFile = SAFDocFile(context, contentResolver, childUri)

        docFile.writable shouldBe true
    }
}