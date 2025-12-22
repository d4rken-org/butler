package eu.darken.butler.common

import eu.darken.butler.common.Occasions.Period
import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test
import testhelpers.BaseTest
import java.time.LocalDate

class OccasionsTest : BaseTest() {

    @Test
    fun `XMAS period starts on Dec 21`() {
        Occasions.current(LocalDate.of(2024, 12, 20)) shouldBe Period.NONE
        Occasions.current(LocalDate.of(2024, 12, 21)) shouldBe Period.XMAS
    }

    @Test
    fun `XMAS period ends on Dec 27`() {
        Occasions.current(LocalDate.of(2024, 12, 27)) shouldBe Period.XMAS
        Occasions.current(LocalDate.of(2024, 12, 28)) shouldBe Period.NEW_YEAR
    }

    @Test
    fun `NEW_YEAR period is Dec 28 to Jan 1`() {
        Occasions.current(LocalDate.of(2024, 12, 28)) shouldBe Period.NEW_YEAR
        Occasions.current(LocalDate.of(2024, 12, 29)) shouldBe Period.NEW_YEAR
        Occasions.current(LocalDate.of(2024, 12, 30)) shouldBe Period.NEW_YEAR
        Occasions.current(LocalDate.of(2024, 12, 31)) shouldBe Period.NEW_YEAR
        Occasions.current(LocalDate.of(2025, 1, 1)) shouldBe Period.NEW_YEAR
    }

    @Test
    fun `NEW_YEAR period ends after Jan 1`() {
        Occasions.current(LocalDate.of(2025, 1, 1)) shouldBe Period.NEW_YEAR
        Occasions.current(LocalDate.of(2025, 1, 2)) shouldBe Period.NONE
    }

    @Test
    fun `regular dates return NONE`() {
        Occasions.current(LocalDate.of(2024, 6, 15)) shouldBe Period.NONE
        Occasions.current(LocalDate.of(2024, 1, 15)) shouldBe Period.NONE
        Occasions.current(LocalDate.of(2024, 11, 30)) shouldBe Period.NONE
        Occasions.current(LocalDate.of(2024, 12, 1)) shouldBe Period.NONE
        Occasions.current(LocalDate.of(2024, 12, 20)) shouldBe Period.NONE
    }

    @Test
    fun `isXmas returns true only during XMAS period`() {
        // Can't easily test isXmas property without mocking, but we can test current()
        Occasions.current(LocalDate.of(2024, 12, 25)) shouldBe Period.XMAS
    }

    @Test
    fun `isNewYear returns true only during NEW_YEAR period`() {
        Occasions.current(LocalDate.of(2024, 12, 31)) shouldBe Period.NEW_YEAR
    }
}
