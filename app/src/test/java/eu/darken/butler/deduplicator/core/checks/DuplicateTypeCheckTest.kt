package eu.darken.butler.deduplicator.core.checks

import eu.darken.butler.deduplicator.core.Duplicate
import eu.darken.butler.deduplicator.core.arbiter.ArbiterCriterium
import eu.darken.butler.deduplicator.core.arbiter.checks.DuplicateTypeCheck
import io.kotest.matchers.shouldBe
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Test
import testhelpers.BaseTest

class DuplicateTypeCheckTest : BaseTest() {

    private val dupeChecksum = mockk<Duplicate>().apply {
        every { type } returns Duplicate.Type.CHECKSUM
    }

    private val dupePhash = mockk<Duplicate>().apply {
        every { type } returns Duplicate.Type.PHASH
    }

    private fun create() = DuplicateTypeCheck()

    @Test
    fun `check mode - prefer checksum`() = runTest {
        create().favorite(
            listOf(dupeChecksum, dupePhash),
            ArbiterCriterium.DuplicateType(ArbiterCriterium.DuplicateType.Mode.PREFER_CHECKSUM),
        ) shouldBe listOf(dupeChecksum, dupePhash)

        create().favorite(
            listOf(dupePhash, dupeChecksum),
            ArbiterCriterium.DuplicateType(ArbiterCriterium.DuplicateType.Mode.PREFER_CHECKSUM),
        ) shouldBe listOf(dupeChecksum, dupePhash)
    }

    @Test
    fun `check mode - prefer phash`() = runTest {
        create().favorite(
            listOf(dupeChecksum, dupePhash),
            ArbiterCriterium.DuplicateType(ArbiterCriterium.DuplicateType.Mode.PREFER_PHASH),
        ) shouldBe listOf(dupePhash, dupeChecksum)

        create().favorite(
            listOf(dupePhash, dupeChecksum),
            ArbiterCriterium.DuplicateType(ArbiterCriterium.DuplicateType.Mode.PREFER_PHASH),
        ) shouldBe listOf(dupePhash, dupeChecksum)
    }
}