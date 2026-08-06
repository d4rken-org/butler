package eu.darken.butler.workspace.core.operations

import eu.darken.butler.common.files.APath
import eu.darken.butler.common.files.LocalPath
import eu.darken.butler.common.files.SAFPath
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test
import testhelpers.BaseTest

class TrashCapabilityTest : BaseTest() {

    private val localA = LocalPath.build("/storage/emulated/0/a.txt")
    private val localB = LocalPath.build("/storage/emulated/0/b.txt")
    private val safA = SAFPath.build(
        "content://com.android.externalstorage.documents/tree/primary%3ADownload",
        "a.txt",
    )
    private val safB = SAFPath.build(
        "content://com.android.externalstorage.documents/tree/primary%3ADownload",
        "b.txt",
    )

    @Test
    fun `local only selection is fully trashable`() {
        val partition = partitionByTrashSupport(setOf(localA, localB))
        partition.trashable shouldBe setOf(localA, localB)
        partition.untrashable.shouldBeEmpty()
    }

    @Test
    fun `saf only selection is fully untrashable`() {
        val partition = partitionByTrashSupport(setOf(safA, safB))
        partition.trashable.shouldBeEmpty()
        partition.untrashable shouldBe setOf(safA, safB)
    }

    @Test
    fun `mixed selection is split by path type`() {
        val partition = partitionByTrashSupport(setOf(localA, safA))
        partition.trashable shouldBe setOf(localA)
        partition.untrashable shouldBe setOf(safA)
    }

    @Test
    fun `empty selection partitions into two empty sets`() {
        val partition = partitionByTrashSupport(emptySet<APath<*>>())
        partition.trashable.shouldBeEmpty()
        partition.untrashable.shouldBeEmpty()
    }
}
