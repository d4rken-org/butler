package eu.darken.butler.common

import java.time.DayOfWeek
import java.time.LocalDate
import java.time.temporal.TemporalAdjusters

object Occasions {
    enum class Period {
        HALLOWEEN,
        ST_PATRICKS,
        APRIL_FOOLS,
        OKTOBERFEST,
        XMAS,
        NEW_YEAR,
        NONE,
    }

    private fun getOktoberfestStart(year: Int): LocalDate {
        val septemberFirst = LocalDate.of(year, 9, 1)
        val firstSaturday = septemberFirst.with(TemporalAdjusters.nextOrSame(DayOfWeek.SATURDAY))
        return firstSaturday.plusWeeks(2)
    }

    private fun isInOktoberfestRange(today: LocalDate): Boolean {
        val start = getOktoberfestStart(today.year)
        val end = start.plusDays(2)
        return today in start..end
    }

    fun current(today: LocalDate = LocalDate.now()): Period {
        val month = today.monthValue
        val day = today.dayOfMonth
        return when {
            month == 10 && day in 28..31 -> Period.HALLOWEEN
            month == 3 && day == 17 -> Period.ST_PATRICKS
            month == 4 && day == 1 -> Period.APRIL_FOOLS
            isInOktoberfestRange(today) -> Period.OKTOBERFEST
            month == 12 && day in 21..27 -> Period.XMAS
            (month == 12 && day >= 28) || (month == 1 && day == 1) -> Period.NEW_YEAR
            else -> Period.NONE
        }
    }

    val isHalloween: Boolean get() = current() == Period.HALLOWEEN
    val isStPatricks: Boolean get() = current() == Period.ST_PATRICKS
    val isAprilFools: Boolean get() = current() == Period.APRIL_FOOLS
    val isOktoberfest: Boolean get() = current() == Period.OKTOBERFEST
    val isXmas: Boolean get() = current() == Period.XMAS
    val isNewYear: Boolean get() = current() == Period.NEW_YEAR
}
