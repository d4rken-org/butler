package eu.darken.butler.common.files.archive

import io.kotest.matchers.collections.shouldContainExactlyInAnyOrder
import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test
import testhelpers.BaseTest

class ArchiveIndexTest : BaseTest() {

    private fun fileEntry(vararg segments: String, size: Long = 1L) = ArchiveEntryMeta(
        segments = segments.toList(),
        rawName = segments.joinToString("/"),
        isDirectory = false,
        size = size,
        modifiedAt = null,
    )

    @Test
    fun `implied directories are synthesized`() {
        val (bySegments, children) = buildIndexMaps(listOf(fileEntry("a", "b", "c.txt")))

        bySegments.keys shouldContainExactlyInAnyOrder listOf(
            listOf("a"),
            listOf("a", "b"),
            listOf("a", "b", "c.txt"),
        )
        bySegments[listOf("a")]!!.isDirectory shouldBe true
        bySegments[listOf("a")]!!.synthesized shouldBe true
        children[emptyList()]!!.map { it.segments } shouldBe listOf(listOf("a"))
        children[listOf("a", "b")]!!.map { it.segments } shouldBe listOf(listOf("a", "b", "c.txt"))
    }

    @Test
    fun `duplicate file entries keep the last occurrence`() {
        val first = fileEntry("dup.txt", size = 10)
        val second = fileEntry("dup.txt", size = 20)
        val (bySegments, _) = buildIndexMaps(listOf(first, second))

        bySegments[listOf("dup.txt")]!!.size shouldBe 20
    }

    @Test
    fun `explicit directory entry is preserved over synthesized one`() {
        val explicitDir = ArchiveEntryMeta(
            segments = listOf("a"),
            rawName = "a/",
            isDirectory = true,
            size = null,
            modifiedAt = null,
            synthesized = false,
        )
        val (bySegments, _) = buildIndexMaps(listOf(fileEntry("a", "b.txt"), explicitDir))

        bySegments[listOf("a")]!!.isDirectory shouldBe true
        bySegments[listOf("a")]!!.synthesized shouldBe false
    }

    @Test
    fun `a file shadowed by a child path becomes a directory`() {
        // "a" as a file, plus "a/b" as a file -> "a" must resolve to a directory so browsing works.
        val (bySegments, _) = buildIndexMaps(listOf(fileEntry("a"), fileEntry("a", "b")))

        bySegments[listOf("a")]!!.isDirectory shouldBe true
    }
}
