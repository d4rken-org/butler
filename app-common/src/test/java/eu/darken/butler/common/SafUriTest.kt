package eu.darken.butler.common

import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test

class SafUriTest {

    @Test
    fun `parse simple content URI`() {
        val uri = SafUri.parse("content://com.android.externalstorage.documents/tree/primary")

        uri.scheme shouldBe "content"
        uri.authority shouldBe "com.android.externalstorage.documents"
        uri.path shouldBe "/tree/primary"
    }

    @Test
    fun `parse SAF tree URI with encoded path`() {
        val uri = SafUri.parse("content://com.android.externalstorage.documents/tree/primary%3Asafstor")

        uri.scheme shouldBe "content"
        uri.authority shouldBe "com.android.externalstorage.documents"
        uri.pathSegments shouldBe listOf("tree", "primary", "safstor")
    }

    @Test
    fun `parse SAF tree URI with complex encoded path`() {
        val uri = SafUri.parse("content://com.android.externalstorage.documents/tree/primary%3Afolder%2Fsubfolder%2Ffile")

        uri.pathSegments shouldBe listOf("tree", "primary", "folder", "subfolder", "file")
    }

    @Test
    fun `parse SD card tree URI`() {
        val uri = SafUri.parse("content://com.android.externalstorage.documents/tree/3135-3132%3Asafstor")

        uri.scheme shouldBe "content"
        uri.authority shouldBe "com.android.externalstorage.documents"
        uri.pathSegments shouldBe listOf("tree", "3135-3132", "safstor")
    }

    @Test
    fun `parse URI with empty path`() {
        val uri = SafUri.parse("content://authority")

        uri.scheme shouldBe "content"
        uri.authority shouldBe "authority"
        uri.path shouldBe null
        uri.pathSegments shouldBe emptyList()
    }

    @Test
    fun `parse URI with root path`() {
        val uri = SafUri.parse("content://authority/")

        uri.scheme shouldBe "content"
        uri.authority shouldBe "authority"
        uri.path shouldBe "/"
        uri.pathSegments shouldBe emptyList()
    }

    @Test
    fun `encode string for URI`() {
        SafUri.encode("hello world") shouldBe "hello%20world"
        SafUri.encode("path/to/file") shouldBe "path%2Fto%2Ffile"
        SafUri.encode("prefix:path") shouldBe "prefix%3Apath"
        SafUri.encode("special!@#") shouldBe "special%21%40%23"
    }

    @Test
    fun `decode string from URI`() {
        SafUri.decode("hello%20world") shouldBe "hello world"
        SafUri.decode("path%2Fto%2Ffile") shouldBe "path/to/file"
        SafUri.decode("prefix%3Apath") shouldBe "prefix:path"
        SafUri.decode("special%21%40%23") shouldBe "special!@#"
    }

    @Test
    fun `encode decode round trip`() {
        val original = "path/to:file with spaces!@#"
        val encoded = SafUri.encode(original)
        val decoded = SafUri.decode(encoded)

        decoded shouldBe original
    }

    @Test
    fun `toString returns raw URI`() {
        val uriString = "content://com.android.externalstorage.documents/tree/primary%3Asafstor"
        val uri = SafUri.parse(uriString)

        uri.toString() shouldBe uriString
    }

    @Test
    fun `pathSegments handles multiple separators`() {
        val uri = SafUri.parse("content://authority/tree/primary%3Afolder1%2Ffolder2")

        uri.pathSegments shouldBe listOf("tree", "primary", "folder1", "folder2")
    }

    @Test
    fun `pathSegments filters empty segments`() {
        val uri = SafUri.parse("content://authority/tree//primary")

        uri.pathSegments shouldBe listOf("tree", "primary")
    }

    @Test
    fun `equality and hashCode work correctly`() {
        val uri1 = SafUri.parse("content://authority/tree/primary")
        val uri2 = SafUri.parse("content://authority/tree/primary")
        val uri3 = SafUri.parse("content://authority/tree/secondary")

        uri1 shouldBe uri2
        (uri1 == uri3) shouldBe false
        uri1.hashCode() shouldBe uri2.hashCode()
    }

    @Test
    fun `handles URI without scheme`() {
        val uri = SafUri.parse("/path/to/file")

        uri.scheme shouldBe null
        uri.authority shouldBe null
        uri.path shouldBe null
    }

    @Test
    fun `real world SAF URI examples`() {
        // Primary storage root
        SafUri.parse("content://com.android.externalstorage.documents/tree/primary%3A")
            .pathSegments shouldBe listOf("tree", "primary")

        // SD card with path
        SafUri.parse("content://com.android.externalstorage.documents/tree/4BBD-D3E7%3AAndroid%2Fdata")
            .pathSegments shouldBe listOf("tree", "4BBD-D3E7", "Android", "data")

        // Deep nested path
        SafUri.parse("content://com.android.externalstorage.documents/tree/primary%3ADownload%2Ftest%2Ffile.txt")
            .pathSegments shouldBe listOf("tree", "primary", "Download", "test", "file.txt")
    }
}
