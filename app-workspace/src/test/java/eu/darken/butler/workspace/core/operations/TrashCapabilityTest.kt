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

    private fun skips(
        targets: Set<APath<*>> = setOf(localA, localB),
        permanentRequested: Boolean = false,
        trashEnabled: Boolean = true,
        confirmTrashMove: Boolean = false,
    ) = skipsDeleteConfirmation(
        targets = targets,
        permanentRequested = permanentRequested,
        trashEnabled = trashEnabled,
        confirmTrashMove = confirmTrashMove,
    )

    @Test
    fun `a trash move of local items skips the confirmation when it is turned off`() {
        skips() shouldBe true
    }

    @Test
    fun `the confirmation setting on keeps the dialog`() {
        skips(confirmTrashMove = true) shouldBe false
    }

    @Test
    fun `a disabled trash keeps the dialog`() {
        skips(trashEnabled = false) shouldBe false
    }

    @Test
    fun `a permanent delete keeps the dialog`() {
        skips(permanentRequested = true) shouldBe false
    }

    @Test
    fun `a mixed local and saf selection keeps the dialog`() {
        skips(targets = setOf(localA, safA)) shouldBe false
    }

    @Test
    fun `a saf only selection keeps the dialog`() {
        skips(targets = setOf(safA, safB)) shouldBe false
    }

    @Test
    fun `an empty selection keeps the dialog`() {
        skips(targets = emptySet()) shouldBe false
    }
}
