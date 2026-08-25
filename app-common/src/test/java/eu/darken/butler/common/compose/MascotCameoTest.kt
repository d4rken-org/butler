package eu.darken.butler.common.compose

import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Test
import testhelpers.BaseTest

class MascotCameoTest : BaseTest() {

    @AfterEach
    fun tearDown() {
        MascotCameo.claim()
    }

    /**
     * Tests and screenshot renders never call [MascotCameo.roll], so a cycling mascot in them is
     * always a Butler clip and can't flake on the cameo.
     */
    @Test
    fun `nothing arms the cameo on its own`() {
        MascotCameo.claim() shouldBe false
    }

    @Test
    fun `an armed cameo is claimed exactly once`() {
        MascotCameo.arm()

        MascotCameo.claim() shouldBe true
        MascotCameo.claim() shouldBe false
    }

    @Test
    fun `rolling arms the cameo on some runs and not others`() {
        val outcomes = (1..10_000).map {
            MascotCameo.roll()
            MascotCameo.claim()
        }.toSet()

        outcomes shouldBe setOf(true, false)
    }
}
