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
        "markdown" to "text/markdown",
        "apk" to "application/vnd.android.package-archive",
        "apks" to "application/zip",
        "xapk" to "application/zip",
        "apkm" to "application/zip",
        "json" to "application/json",
        "xml" to "application/xml",
        "html" to "text/html",
        "htm" to "text/html",
        "css" to "text/css",
        "csv" to "text/csv",
        "js" to "text/javascript",
        "mjs" to "text/javascript",
        "cjs" to "text/javascript",
        "yml" to "text/plain",
        "yaml" to "text/plain",
        "toml" to "text/plain",
        "ini" to "text/plain",
        "cfg" to "text/plain",
        "conf" to "text/plain",
        "config" to "text/plain",
        "properties" to "text/plain",
        "env" to "text/plain",
        "log" to "text/plain",
        "rst" to "text/plain",
        "adoc" to "text/plain",
        "tex" to "text/plain",
        "tsv" to "text/plain",
        "jsx" to "text/plain",
        "ts" to "text/plain",
        "tsx" to "text/plain",
        "scss" to "text/plain",
        "sass" to "text/plain",
        "less" to "text/plain",
        "kt" to "text/plain",
        "kts" to "text/plain",
        "java" to "text/plain",
        "py" to "text/plain",
        "c" to "text/plain",
        "cpp" to "text/plain",
        "cc" to "text/plain",
        "cxx" to "text/plain",
        "h" to "text/plain",
        "hpp" to "text/plain",
        "cs" to "text/plain",
        "php" to "text/plain",
        "rb" to "text/plain",
        "go" to "text/plain",
        "rs" to "text/plain",
        "swift" to "text/plain",
        "m" to "text/plain",
        "mm" to "text/plain",
        "sql" to "text/plain",
        "sh" to "text/plain",
        "bash" to "text/plain",
        "zsh" to "text/plain",
        "fish" to "text/plain",
        "ksh" to "text/plain",
        "bat" to "text/plain",
        "cmd" to "text/plain",
        "ps1" to "text/plain",
        "gradle" to "text/plain",
        "cmake" to "text/plain",
        "make" to "text/plain",
        "mk" to "text/plain",
        "lua" to "text/plain",
        "dart" to "text/plain",
        "pro" to "text/plain",
        "gitignore" to "text/plain",
        "gitattributes" to "text/plain",
        "editorconfig" to "text/plain",
        "bashrc" to "text/plain",
        "zshrc" to "text/plain",
        "profile" to "text/plain",
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
        // Bundles are zips, so they must not answer the single-APK question.
        MimeInfo.fromFileName("foo.apks").isApk shouldBe false
        MimeInfo.fromFileName("foo.xapk").isApk shouldBe false
        MimeInfo.fromFileName("foo.apkm").isApk shouldBe false
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

    @Test
    fun `text files are recognized by their extension`() {
        MimeInfo.fromFileName("x.yaml").isText shouldBe true
        MimeInfo.fromFileName("x.kt").isText shouldBe true
        MimeInfo.fromFileName("x.sh").isText shouldBe true
        MimeInfo.fromFileName("x.log").isText shouldBe true
        MimeInfo.fromFileName("x.lua").isText shouldBe true
        MimeInfo.fromFileName(".gitignore").isText shouldBe true

        MimeInfo.fromFileName("x.png").isText shouldBe false
        MimeInfo.fromFileName("x.zip").isText shouldBe false
        MimeInfo.fromFileName("x.apk").isText shouldBe false
        MimeInfo.fromFileName("x.pdf").isText shouldBe false
        MimeInfo.fromFileName("x.mp3").isText shouldBe false
        MimeInfo.fromFileName("x.xyz").isText shouldBe false
    }

    @Test
    fun `ts is read as TypeScript source, not as an MPEG transport stream`() {
        MimeInfo.fromFileName("app.ts").rawType shouldBe "text/plain"
        MimeInfo.fromFileName("app.ts").isVideo shouldBe false
    }

    @Test
    fun `extensionless text files are recognized by their name`() {
        listOf("Makefile", "Dockerfile", "LICENSE", "README", "CHANGELOG").forEach { name ->
            listOf(name.lowercase(), name.uppercase(), name).forEach { variant ->
                MimeInfo.fromFileName(variant).rawType shouldBe "text/plain"
            }
        }
    }

    @Test
    fun `a known text name with an extension takes the extension path`() {
        MimeInfo.fromFileName("README.backup").rawType shouldBe "application/octet-stream"
        MimeInfo.fromFileName("Dockerfile.bak").rawType shouldBe "application/octet-stream"
    }

    @Test
    fun `svg stays a vector image so the viewer keeps rendering it`() {
        MimeInfo.fromFileName("a.svg").rawType shouldBe "image/svg+xml"
        MimeInfo.fromFileName("a.svg").isImage shouldBe true
        MimeInfo.fromFileName("a.svg").isText shouldBe false
    }

    @Test
    fun `externally declared text types count as text`() {
        listOf(
            "application/yaml",
            "application/x-yaml",
            "application/toml",
            "application/sql",
            "application/x-httpd-php",
            "application/x-perl",
            "application/x-ruby",
        ).forEach { declared ->
            MimeInfo(declared).isText shouldBe true
        }

        MimeInfo("application/zip").isText shouldBe false
        MimeInfo("application/octet-stream").isText shouldBe false
    }
}
