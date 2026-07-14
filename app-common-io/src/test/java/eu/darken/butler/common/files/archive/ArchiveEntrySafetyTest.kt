package eu.darken.butler.common.files.archive

import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test
import testhelpers.BaseTest

/**
 * The entry-name policy is the primary zip-slip defense: hostile names never enter the index,
 * so no downstream path construction can escape the destination.
 */
class ArchiveEntrySafetyTest : BaseTest() {

    @Test
    fun `plain nested paths parse to segments`() {
        ArchiveEntrySafety.parseEntryName("a/b/c.txt") shouldBe listOf("a", "b", "c.txt")
        ArchiveEntrySafety.parseEntryName("readme.md") shouldBe listOf("readme.md")
    }

    @Test
    fun `directory entries drop trailing separator`() {
        ArchiveEntrySafety.parseEntryName("dir/") shouldBe listOf("dir")
    }

    @Test
    fun `traversal segments are rejected`() {
        ArchiveEntrySafety.parseEntryName("../evil.txt") shouldBe null
        ArchiveEntrySafety.parseEntryName("a/../../evil.txt") shouldBe null
        ArchiveEntrySafety.parseEntryName("..") shouldBe null
    }

    @Test
    fun `absolute paths collapse to their relative remainder`() {
        // Leading separator yields empty first segment, which is dropped -> stays inside destination.
        ArchiveEntrySafety.parseEntryName("/etc/passwd") shouldBe listOf("etc", "passwd")
    }

    @Test
    fun `redundant separators and dot segments are dropped`() {
        ArchiveEntrySafety.parseEntryName("a//b/./c") shouldBe listOf("a", "b", "c")
        ArchiveEntrySafety.parseEntryName("./x") shouldBe listOf("x")
    }

    @Test
    fun `nul bytes are rejected`() {
        ArchiveEntrySafety.parseEntryName("a\u0000b") shouldBe null
    }

    @Test
    fun `windows separators handled when no forward slashes present`() {
        ArchiveEntrySafety.parseEntryName("a\\b\\c.txt") shouldBe listOf("a", "b", "c.txt")
        ArchiveEntrySafety.parseEntryName("..\\evil") shouldBe null
    }

    @Test
    fun `empty and separator-only names are rejected`() {
        ArchiveEntrySafety.parseEntryName("") shouldBe null
        ArchiveEntrySafety.parseEntryName("/") shouldBe null
    }
}
