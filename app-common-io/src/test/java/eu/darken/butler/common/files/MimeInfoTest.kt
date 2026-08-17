package eu.darken.butler.common.files

import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test
import testhelpers.BaseTest

class MimeInfoTest : BaseTest() {

    private val expectedTable = mapOf(
        "jpg" to "image/jpeg",
        "jpeg" to "image/jpeg",
        "png" to "image/png",
        "gif" to "image/gif",
        "webp" to "image/webp",
        "bmp" to "image/bmp",
        "heic" to "image/heic",
        "heif" to "image/heif",
        "avif" to "image/avif",
        "svg" to "image/svg+xml",
        "mp4" to "video/mp4",
        "mkv" to "video/x-matroska",
        "avi" to "video/x-msvideo",
        "mov" to "video/quicktime",
        "webm" to "video/webm",
        "3gp" to "video/3gpp",
        "m4v" to "video/x-m4v",
        "mp3" to "audio/mpeg",
        "wav" to "audio/wav",
        "flac" to "audio/flac",
        "ogg" to "audio/ogg",
        "m4a" to "audio/mp4",
        "zip" to "application/zip",
        "tar" to "application/x-tar",
        "gz" to "application/gzip",
        "7z" to "application/x-7z-compressed",
        "rar" to "application/vnd.rar",
        "pdf" to "application/pdf",
        "doc" to "application/msword",
        "docx" to "application/vnd.openxmlformats-officedocument.wordprocessingml.document",
        "txt" to "text/plain",
        "md" to "text/markdown",
        "apk" to "application/vnd.android.package-archive",
    )

    @Test
    fun `full extension table maps as expected`() {
        expectedTable.forEach { (extension, mime) ->
            MimeInfo.fromFileName("file.$extension").rawType shouldBe mime
        }
    }

    @Test
    fun `extensions are case-insensitive`() {
        expectedTable.forEach { (extension, mime) ->
            MimeInfo.fromFileName("FILE.${extension.uppercase()}").rawType shouldBe mime
        }
    }

    @Test
    fun `unknown and missing extensions fall back to octet-stream`() {
        MimeInfo.fromFileName("file.xyz").rawType shouldBe "application/octet-stream"
        MimeInfo.fromFileName("noextension").rawType shouldBe "application/octet-stream"
        MimeInfo.fromFileName("trailingdot.").rawType shouldBe "application/octet-stream"
        MimeInfo.fromFileName(".hidden").rawType shouldBe "application/octet-stream"
    }

    @Test
    fun `media classification helpers match the table`() {
        MimeInfo.fromFileName("a.heic").isImage shouldBe true
        MimeInfo.fromFileName("a.svg").isImage shouldBe true
        MimeInfo.fromFileName("a.3gp").isVideo shouldBe true
        MimeInfo.fromFileName("a.mp3").isImage shouldBe false
        MimeInfo.fromFileName("a.mp3").isVideo shouldBe false
    }

    @Test
    fun `apk files are recognized as package archives`() {
        MimeInfo.fromFileName("foo.apk").isApk shouldBe true
        MimeInfo.fromFileName("FOO.APK").isApk shouldBe true
        MimeInfo.fromFileName("foo.zip").isApk shouldBe false
        MimeInfo.fromFileName("foo.jpg").isApk shouldBe false
    }

    @Test
    fun `only a pdf counts as pdf`() {
        MimeInfo.fromFileName("a.pdf").isPdf shouldBe true
        MimeInfo.fromFileName("A.PDF").isPdf shouldBe true
        MimeInfo.fromFileName("a.jpg").isPdf shouldBe false
        MimeInfo.fromFileName("a.doc").isPdf shouldBe false
        MimeInfo.fromFileName("a.xyz").isPdf shouldBe false
        MimeInfo.fromFileName("a.pdf").isImage shouldBe false
    }
}
