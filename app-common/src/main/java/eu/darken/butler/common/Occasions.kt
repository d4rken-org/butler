package eu.darken.butler.common

import java.time.LocalDate

object Occasions {
    enum class Period {
        XMAS,
        NEW_YEAR,
        NONE,
    }

    fun current(today: LocalDate = LocalDate.now()): Period {
        val month = today.monthValue
        val day = today.dayOfMonth
        return when {
            month == 12 && day in 21..27 -> Period.XMAS
            (month == 12 && day >= 28) || (month == 1 && day == 1) -> Period.NEW_YEAR
            else -> Period.NONE
        }
    }

    val isXmas: Boolean get() = current() == Period.XMAS

    val isNewYear: Boolean get() = current() == Period.NEW_YEAR
}
