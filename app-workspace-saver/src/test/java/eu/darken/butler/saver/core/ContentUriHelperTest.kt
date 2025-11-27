package eu.darken.butler.saver.core

import android.content.ContentResolver
import android.content.Context
import android.database.Cursor
import android.net.Uri
import android.provider.OpenableColumns
import io.kotest.matchers.shouldBe
import io.mockk.every
import io.mockk.mockk
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.ByteArrayInputStream

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33])
class ContentUriHelperTest {

    private lateinit var context: Context
    private lateinit var contentResolver: ContentResolver
    private lateinit var helper: ContentUriHelper

    @Before
    fun setup() {
        context = mockk()
        contentResolver = mockk()
        every { context.contentResolver } returns contentResolver
        helper = ContentUriHelper(context)
    }

    @Test
    fun `extractInfo returns all metadata when cursor provides complete data`() {
        val uri = Uri.parse("content://com.example.provider/files/photo.jpg")
        val cursor = mockCursor(displayName = "photo.jpg", size = 1024L)

        every { contentResolver.query(uri, any(), null, null, null) } returns cursor
        every { contentResolver.getType(uri) } returns "image/jpeg"
        every { contentResolver.openInputStream(uri) } returns ByteArrayInputStream(byteArrayOf())

        val result = helper.extractInfo(uri)

        result.uri shouldBe uri
        result.displayName shouldBe "photo.jpg"
        result.size shouldBe 1024L
        result.mimeType shouldBe "image/jpeg"
        result.isAccessible shouldBe true
    }

    @Test
    fun `extractInfo falls back to lastPathSegment when cursor returns null displayName`() {
        val uri = Uri.parse("content://com.example.provider/documents/report.pdf")
        val cursor = mockCursor(displayName = null, size = 2048L)

        every { contentResolver.query(uri, any(), null, null, null) } returns cursor
        every { contentResolver.getType(uri) } returns "application/pdf"
        every { contentResolver.openInputStream(uri) } returns ByteArrayInputStream(byteArrayOf())

        val result = helper.extractInfo(uri)

        result.displayName shouldBe "report.pdf"
        result.size shouldBe 2048L
    }

    @Test
    fun `extractInfo uses shared_file default when no displayName and no lastPathSegment`() {
        val uri = Uri.parse("content://com.example.provider/")
        val cursor = mockCursor(displayName = null, size = null)

        every { contentResolver.query(uri, any(), null, null, null) } returns cursor
        every { contentResolver.getType(uri) } returns null
        every { contentResolver.openInputStream(uri) } returns ByteArrayInputStream(byteArrayOf())

        val result = helper.extractInfo(uri)

        result.displayName shouldBe "shared_file"
    }

    @Test
    fun `extractInfo handles null cursor gracefully`() {
        val uri = Uri.parse("content://com.example.provider/files/data.zip")

        every { contentResolver.query(uri, any(), null, null, null) } returns null
        every { contentResolver.getType(uri) } returns "application/zip"
        every { contentResolver.openInputStream(uri) } returns ByteArrayInputStream(byteArrayOf())

        val result = helper.extractInfo(uri)

        result.displayName shouldBe "data.zip"
        result.size shouldBe null
        result.mimeType shouldBe "application/zip"
        result.isAccessible shouldBe true
    }

    @Test
    fun `extractInfo handles query exception gracefully`() {
        val uri = Uri.parse("content://com.example.provider/files/document.txt")

        every { contentResolver.query(uri, any(), null, null, null) } throws SecurityException("Permission denied")
        every { contentResolver.getType(uri) } returns "text/plain"
        every { contentResolver.openInputStream(uri) } returns ByteArrayInputStream(byteArrayOf())

        val result = helper.extractInfo(uri)

        result.displayName shouldBe "document.txt"
        result.size shouldBe null
    }

    @Test
    fun `checkAccessibility returns true when input stream opens successfully`() {
        val uri = Uri.parse("content://com.example.provider/files/test")

        every { contentResolver.openInputStream(uri) } returns ByteArrayInputStream(byteArrayOf())

        val result = helper.checkAccessibility(uri)

        result shouldBe true
    }

    @Test
    fun `checkAccessibility returns false on SecurityException`() {
        val uri = Uri.parse("content://com.example.provider/files/expired")

        every { contentResolver.openInputStream(uri) } throws SecurityException("Permission revoked")

        val result = helper.checkAccessibility(uri)

        result shouldBe false
    }

    @Test
    fun `checkAccessibility returns false when openInputStream returns null`() {
        val uri = Uri.parse("content://com.example.provider/files/missing")

        every { contentResolver.openInputStream(uri) } returns null

        val result = helper.checkAccessibility(uri)

        result shouldBe false
    }

    @Test
    fun `extractInfo marks source as inaccessible when openInputStream fails`() {
        val uri = Uri.parse("content://com.example.provider/files/expired.doc")
        val cursor = mockCursor(displayName = "expired.doc", size = 500L)

        every { contentResolver.query(uri, any(), null, null, null) } returns cursor
        every { contentResolver.getType(uri) } returns "application/msword"
        every { contentResolver.openInputStream(uri) } throws SecurityException("Permission expired")

        val result = helper.extractInfo(uri)

        result.displayName shouldBe "expired.doc"
        result.size shouldBe 500L
        result.mimeType shouldBe "application/msword"
        result.isAccessible shouldBe false
    }

    private fun mockCursor(displayName: String?, size: Long?): Cursor {
        val cursor = mockk<Cursor>()

        every { cursor.moveToFirst() } returns true
        every { cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME) } returns 0
        every { cursor.getColumnIndex(OpenableColumns.SIZE) } returns 1
        every { cursor.getString(0) } returns displayName
        every { cursor.isNull(1) } returns (size == null)
        every { cursor.getLong(1) } returns (size ?: 0L)
        every { cursor.close() } returns Unit

        return cursor
    }
}
