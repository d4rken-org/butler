package eu.darken.butler.provider.documents.core

import eu.darken.butler.common.files.LocalPath
import eu.darken.butler.common.files.SAFPath
import eu.darken.butler.common.serialization.SerializationIOModule
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.matchers.shouldBe
import io.kotest.matchers.shouldNotBe
import io.kotest.matchers.string.shouldContain
import io.kotest.matchers.string.shouldNotContain
import io.kotest.matchers.string.shouldStartWith
import io.kotest.matchers.types.shouldBeInstanceOf
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Nested
import org.junit.jupiter.api.Test
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.ValueSource
import testhelpers.BaseTest
import java.util.Base64

/**
 * Test-Driven Development for DocumentIdCodec
 *
 * This test suite is written FIRST before implementation (RED phase).
 * Document ID stability is a critical correctness requirement - bugs here break client apps.
 *
 * Coverage goal: >95%
 */
@DisplayName("DocumentIdCodec")
class DocumentIdCodecTest : BaseTest() {

    // Use real SerializationIOModule for testing (same as production)
    private val codec = DocumentIdCodec(SerializationIOModule().json())

    @Nested
    @DisplayName("Encoding")
    inner class Encoding {

        @Test
        fun `encode LocalPath with simple absolute path`() {
            val path = LocalPath.build("/storage/emulated/0/Download/file.pdf")
            val encoded = codec.encode(path)

            encoded shouldStartWith "local|"
            encoded shouldNotContain "/" // Base64 shouldn't contain slashes
        }

        @Test
        fun `encode path with special characters`() {
            val path = LocalPath.build("/storage/test file (1) [copy].txt")
            val encoded = codec.encode(path)

            encoded shouldNotBe null
            encoded.split("|").size shouldBe 2
        }

        @Test
        fun `encode path with Unicode characters`() {
            val path = LocalPath.build("/storage/emulated/0/文件/ファイル.txt")
            val encoded = codec.encode(path)

            encoded shouldNotBe null
            encoded shouldContain "|"
        }

        @Test
        fun `encode very long path - no length limit`() {
            val longPath = "/storage/emulated/0/" + "a".repeat(500) + "/file.pdf"
            val path = LocalPath.build(longPath)
            val encoded = codec.encode(path)

            encoded shouldNotBe null
        }

        @Test
        fun `encode path with pipe characters in filename`() {
            val path = LocalPath.build("/storage/file|with|pipes.txt")
            val encoded = codec.encode(path)

            // Base64 part should not expose pipe characters
            val parts = encoded.split("|")
            parts.size shouldBe 2
        }

        @Test
        fun `encode root path`() {
            val path = LocalPath.build("/")
            val encoded = codec.encode(path)

            encoded shouldNotBe null
            encoded shouldStartWith "local|"
        }
    }

    @Nested
    @DisplayName("Decoding")
    inner class Decoding {

        @Test
        fun `decode valid document ID returns correct path`() {
            val documentId = "local|L3N0b3JhZ2UvZW11bGF0ZWQvMC9maWxlLnBkZg"
            val path = codec.decode(documentId)

            path shouldBe LocalPath.build("/storage/emulated/0/file.pdf")
        }

        @Test
        fun `decode throws on malformed document ID - missing parts`() {
            shouldThrow<IllegalArgumentException> {
                codec.decode("local") // Missing encoded path
            }
        }

        @Test
        fun `decode throws on malformed document ID - too many parts`() {
            shouldThrow<IllegalArgumentException> {
                codec.decode("local|base64|extra")
            }
        }

        @Test
        fun `decode throws on invalid base64`() {
            shouldThrow<IllegalArgumentException> {
                codec.decode("local|NOT_VALID_BASE64!!!")
            }
        }

        @Test
        fun `decode throws on unknown path type`() {
            shouldThrow<IllegalArgumentException> {
                codec.decode("unknown_type|L3N0b3JhZ2U")
            }
        }

        @Test
        fun `decode empty document ID throws`() {
            shouldThrow<IllegalArgumentException> {
                codec.decode("")
            }
        }

        @Test
        fun `decode document ID with only separator throws`() {
            shouldThrow<IllegalArgumentException> {
                codec.decode("|")
            }
        }
    }

    @Nested
    @DisplayName("Round Trip")
    inner class RoundTrip {

        @ParameterizedTest
        @ValueSource(
            strings = [
                "/storage/emulated/0/Download/file.pdf",
                "/storage/test file (1).txt",
                "/storage/emulated/0/文件.txt",
                "/storage/emulated/0/a/b/c/d/e/f/g/deep.txt",
                "/storage/My Documents/Report [Final] (2).docx",
                "/storage/file with\ttab.txt",
                "/storage/file with\nnewline.txt"
            ]
        )
        fun `encode and decode round trip preserves path`(pathString: String) {
            val original = LocalPath.build(pathString)
            val encoded = codec.encode(original)
            val decoded = codec.decode(encoded)

            decoded shouldBe original
        }

        @Test
        fun `round trip with root path`() {
            val path = LocalPath.build("/")
            val encoded = codec.encode(path)
            val decoded = codec.decode(encoded)

            decoded shouldBe path
        }

        @Test
        fun `round trip with different storage locations`() {
            val paths = listOf(
                LocalPath.build("/storage/emulated/0/file.txt"),  // Internal storage
                LocalPath.build("/storage/1234-5678/file.txt"),   // SD card
                LocalPath.build("/system/build.prop"),            // System path
                LocalPath.build("/")                               // Root
            )

            paths.forEach { original ->
                val encoded = codec.encode(original)
                val decoded = codec.decode(encoded)
                decoded shouldBe original
            }
        }
    }

    @Nested
    @DisplayName("Stability")
    inner class Stability {

        @Test
        fun `same input produces same output - stability guarantee`() {
            val path = LocalPath.build("/storage/emulated/0/file.pdf")

            val encoded1 = codec.encode(path)
            val encoded2 = codec.encode(path)
            val encoded3 = codec.encode(path)

            encoded1 shouldBe encoded2
            encoded2 shouldBe encoded3
        }

        @Test
        fun `document ID format matches specification`() {
            val path = LocalPath.build("/storage/emulated/0/file.pdf")
            val encoded = codec.encode(path)

            val parts = encoded.split("|")
            parts.size shouldBe 2
            parts[0] shouldBe "local"
            parts[1].isNotEmpty() shouldBe true
        }

        @Test
        fun `document ID does not contain path separators in base64`() {
            val path = LocalPath.build("/storage/emulated/0/file.pdf")
            val encoded = codec.encode(path)

            val parts = encoded.split("|")
            val base64Part = parts[1]

            // URL-safe Base64 should not contain / or +
            base64Part shouldNotContain "/"
            base64Part shouldNotContain "+"
        }
    }

    @Nested
    @DisplayName("Edge Cases")
    inner class EdgeCases {

        @Test
        fun `encode path with consecutive slashes - normalized by LocalPath`() {
            // LocalPath may normalize - test the codec handles whatever LocalPath returns
            val path = LocalPath.build("/storage//emulated///0/file.pdf")
            val encoded = codec.encode(path)
            val decoded = codec.decode(encoded)

            decoded shouldBe path
        }

        @Test
        fun `encode path with trailing slash`() {
            val path1 = LocalPath.build("/storage/emulated/0/folder")
            val path2 = LocalPath.build("/storage/emulated/0/folder/")

            // Document behavior: LocalPath normalization
            val encoded1 = codec.encode(path1)
            val encoded2 = codec.encode(path2)

            encoded1 shouldNotBe null
            encoded2 shouldNotBe null
        }

        @Test
        fun `encode path with only root`() {
            val path = LocalPath.build("/")
            val encoded = codec.encode(path)

            encoded shouldNotBe null
            encoded shouldStartWith "local|"
        }
    }

    @Nested
    @DisplayName("SAFPath Encoding")
    inner class SAFPathEncoding {

        @Test
        fun `encode and decode SAFPath round trip`() {
            val original = SAFPath.build(
                "content://com.android.externalstorage.documents/tree/primary%3Afolder",
                "subfolder",
                "file.txt"
            )
            val encoded = codec.encode(original)
            val decoded = codec.decode(encoded)

            decoded shouldBe original
            decoded.shouldBeInstanceOf<SAFPath>()
            // Data class equality checks treeRoot and segments automatically
            decoded.segments shouldBe original.segments
        }

        @Test
        fun `encode SAFPath with empty segments`() {
            val safPath = SAFPath.build("content://com.android.externalstorage.documents/tree/primary%3Afolder")
            val encoded = codec.encode(safPath)
            val decoded = codec.decode(encoded)

            decoded shouldBe safPath
            (decoded as SAFPath).segments.isEmpty() shouldBe true
        }

        @Test
        fun `encode SAFPath with special characters in segments`() {
            val safPath = SAFPath.build(
                "content://com.android.externalstorage.documents/tree/primary%3Afolder",
                "folder with spaces",
                "file (1).txt",
                "文件.pdf"
            )
            val encoded = codec.encode(safPath)
            val decoded = codec.decode(encoded)

            decoded shouldBe safPath
        }

        @Test
        fun `encode SAFPath with deep nesting`() {
            val safPath = SAFPath.build(
                "content://com.android.externalstorage.documents/tree/primary%3Afolder",
                "a", "b", "c", "d", "e", "f", "g", "file.txt"
            )
            val encoded = codec.encode(safPath)
            val decoded = codec.decode(encoded)

            decoded shouldBe safPath
        }

        @Test
        fun `SAFPath document ID starts with saf pathType`() {
            val safPath = SAFPath.build(
                "content://com.android.externalstorage.documents/tree/primary%3Afolder",
                "file.txt"
            )
            val encoded = codec.encode(safPath)

            encoded shouldStartWith "saf|"
        }

        @Test
        fun `SAFPath document ID is JSON-based`() {
            val safPath = SAFPath.build(
                "content://com.android.externalstorage.documents/tree/primary%3Afolder",
                "file.txt"
            )
            val encoded = codec.encode(safPath)
            val parts = encoded.split("|")

            parts.size shouldBe 2
            parts[0] shouldBe "saf"

            // Decode base64 and verify it's JSON
            val jsonBytes = Base64.getUrlDecoder().decode(parts[1])
            val jsonString = String(jsonBytes)
            jsonString shouldContain "treeRoot"
            jsonString shouldContain "segments"
        }
    }

    @Nested
    @DisplayName("Virtual Document Detection")
    inner class VirtualDocumentDetection {

        @Test
        fun `root document ID is virtual`() {
            codec.isVirtualDocument(DocumentIdCodec.ROOT_DOCUMENT_ID) shouldBe true
        }

        @Test
        fun `butler is virtual document`() {
            codec.isVirtualDocument("butler") shouldBe true
        }

        @Test
        fun `device self is virtual document`() {
            codec.isVirtualDocument(DocumentIdCodec.DEVICE_DOCUMENT_ID) shouldBe true
        }

        @Test
        fun `device pipe self is virtual document`() {
            codec.isVirtualDocument("device|self") shouldBe true
        }

        @Test
        fun `ssh connection is virtual document`() {
            codec.isVirtualDocument("ssh|server1") shouldBe true
        }

        @Test
        fun `ftp connection is virtual document`() {
            codec.isVirtualDocument("ftp|server1") shouldBe true
        }

        @Test
        fun `local path document ID is not virtual`() {
            val path = LocalPath.build("/storage/emulated/0/file.txt")
            val encoded = codec.encode(path)

            codec.isVirtualDocument(encoded) shouldBe false
        }

        @Test
        fun `saf path document ID is not virtual`() {
            val safPath = SAFPath.build(
                "content://com.android.externalstorage.documents/tree/primary%3Afolder",
                "file.txt"
            )
            val encoded = codec.encode(safPath)

            codec.isVirtualDocument(encoded) shouldBe false
        }

        @Test
        fun `document ID starting with local is not virtual`() {
            codec.isVirtualDocument("local|L3N0b3JhZ2U") shouldBe false
        }

        @Test
        fun `document ID starting with saf is not virtual`() {
            codec.isVirtualDocument("saf|eyJ0cmVlUm9vdCI") shouldBe false
        }

        @Test
        fun `random document ID is not virtual`() {
            codec.isVirtualDocument("random|something") shouldBe false
        }

        @Test
        fun `empty string is not virtual`() {
            codec.isVirtualDocument("") shouldBe false
        }

        @Test
        fun `device without suffix is still virtual`() {
            codec.isVirtualDocument("device|anything") shouldBe true
        }

        @Test
        fun `ssh with different server IDs are all virtual`() {
            codec.isVirtualDocument("ssh|server1") shouldBe true
            codec.isVirtualDocument("ssh|server2") shouldBe true
            codec.isVirtualDocument("ssh|prod-server-01") shouldBe true
        }

        @Test
        fun `ftp with different server IDs are all virtual`() {
            codec.isVirtualDocument("ftp|server1") shouldBe true
            codec.isVirtualDocument("ftp|server2") shouldBe true
            codec.isVirtualDocument("ftp|backup-ftp") shouldBe true
        }
    }
}
