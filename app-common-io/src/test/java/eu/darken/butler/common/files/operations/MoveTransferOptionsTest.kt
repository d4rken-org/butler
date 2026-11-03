package eu.darken.butler.common.files.operations

import eu.darken.butler.common.files.actions.MoveAction
import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test
import testhelpers.BaseTest

class MoveTransferOptionsTest : BaseTest() {

    private val options = MoveAction.Options(
        preserveAttributes = false,
        overwrite = true,
        attemptAtomicMove = true,
        exactTarget = true,
    )

    @Test
    fun `defaults carry every move option over`() {
        options.toTransferOptions() shouldBe TransferStrategy.Options(
            preserveAttributes = false,
            followSymlinks = false,
            overwrite = true,
            attemptAtomicMove = true,
            exactTarget = true,
        )
    }

    @Test
    fun `exactTarget stays off by default`() {
        MoveAction.Options().toTransferOptions().exactTarget shouldBe false
    }

    @Test
    fun `an overwrite override keeps exactTarget`() {
        options.toTransferOptions(overwrite = false) shouldBe TransferStrategy.Options(
            preserveAttributes = false,
            followSymlinks = false,
            overwrite = false,
            attemptAtomicMove = true,
            exactTarget = true,
        )
    }

    @Test
    fun `an atomic move override keeps exactTarget`() {
        options.toTransferOptions(attemptAtomicMove = false) shouldBe TransferStrategy.Options(
            preserveAttributes = false,
            followSymlinks = false,
            overwrite = true,
            attemptAtomicMove = false,
            exactTarget = true,
        )
    }
}
