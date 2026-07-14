package eu.darken.butler.common.files

import io.kotest.assertions.throwables.shouldThrow
import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test
import testhelpers.BaseTest

class ArchivePathTest : BaseTest() {

    private val container = LocalPath.build("/storage/emulated/0/archive.zip")

    @Test
    fun `root path has no segments`() {
        val root = ArchivePath.root(container)
        root.segments shouldBe emptyList()
        root.parent shouldBe null
        root.name shouldBe "archive.zip"
        root.path shouldBe "/storage/emulated/0/archive.zip!"
    }

    @Test
    fun `child appends segments`() {
        val child = ArchivePath.root(container).child("dir", "file.txt")
        child.segments shouldBe listOf("dir", "file.txt")
        child.name shouldBe "file.txt"
        child.path shouldBe "/storage/emulated/0/archive.zip!/dir/file.txt"
    }

    @Test
    fun `parent walks up one segment`() {
        val child = ArchivePath(container, listOf("a", "b", "c"))
        child.parent shouldBe ArchivePath(container, listOf("a", "b"))
        child.parent?.parent shouldBe ArchivePath(container, listOf("a"))
    }

    @Test
    fun `hostile segments are rejected at construction`() {
        shouldThrow<IllegalArgumentException> { ArchivePath(container, listOf("..")) }
        shouldThrow<IllegalArgumentException> { ArchivePath(container, listOf("a", "")) }
        shouldThrow<IllegalArgumentException> { ArchivePath(container, listOf("a/b")) }
    }

    @Test
    fun `equality is container-scoped`() {
        val other = LocalPath.build("/storage/emulated/0/other.zip")
        ArchivePath(container, listOf("a")) shouldBe ArchivePath(container, listOf("a"))
        (ArchivePath(container, listOf("a")) == ArchivePath(other, listOf("a"))) shouldBe false
    }
}
