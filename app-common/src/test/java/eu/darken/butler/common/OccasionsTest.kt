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
    fun `HALLOWEEN period is Oct 28-31`() {
        Occasions.current(LocalDate.of(2024, 10, 27)) shouldBe Period.NONE
        Occasions.current(LocalDate.of(2024, 10, 28)) shouldBe Period.HALLOWEEN
        Occasions.current(LocalDate.of(2024, 10, 29)) shouldBe Period.HALLOWEEN
        Occasions.current(LocalDate.of(2024, 10, 30)) shouldBe Period.HALLOWEEN
        Occasions.current(LocalDate.of(2024, 10, 31)) shouldBe Period.HALLOWEEN
    }

    @Test
    fun `ST_PATRICKS is only Mar 17`() {
        Occasions.current(LocalDate.of(2024, 3, 16)) shouldBe Period.NONE
        Occasions.current(LocalDate.of(2024, 3, 17)) shouldBe Period.ST_PATRICKS
        Occasions.current(LocalDate.of(2024, 3, 18)) shouldBe Period.NONE
    }

    @Test
    fun `APRIL_FOOLS is only Apr 1`() {
        Occasions.current(LocalDate.of(2024, 3, 31)) shouldBe Period.NONE
        Occasions.current(LocalDate.of(2024, 4, 1)) shouldBe Period.APRIL_FOOLS
        Occasions.current(LocalDate.of(2024, 4, 2)) shouldBe Period.NONE
    }

    @Test
    fun `OKTOBERFEST starts on third Saturday in September for 3 days - 2024`() {
        // 2024: Third Saturday in September = Sep 21
        Occasions.current(LocalDate.of(2024, 9, 20)) shouldBe Period.NONE
        Occasions.current(LocalDate.of(2024, 9, 21)) shouldBe Period.OKTOBERFEST // Saturday
        Occasions.current(LocalDate.of(2024, 9, 22)) shouldBe Period.OKTOBERFEST // Sunday
        Occasions.current(LocalDate.of(2024, 9, 23)) shouldBe Period.OKTOBERFEST // Monday
        Occasions.current(LocalDate.of(2024, 9, 24)) shouldBe Period.NONE
    }

    @Test
    fun `OKTOBERFEST starts on third Saturday in September for 3 days - 2025`() {
        // 2025: Third Saturday in September = Sep 20
        Occasions.current(LocalDate.of(2025, 9, 19)) shouldBe Period.NONE
        Occasions.current(LocalDate.of(2025, 9, 20)) shouldBe Period.OKTOBERFEST // Saturday
        Occasions.current(LocalDate.of(2025, 9, 21)) shouldBe Period.OKTOBERFEST // Sunday
        Occasions.current(LocalDate.of(2025, 9, 22)) shouldBe Period.OKTOBERFEST // Monday
        Occasions.current(LocalDate.of(2025, 9, 23)) shouldBe Period.NONE
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
