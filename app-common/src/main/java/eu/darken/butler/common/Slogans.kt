package eu.darken.butler.common

import eu.darken.butler.common.ca.CaString
import eu.darken.butler.common.ca.toCaString
import java.time.LocalDate
import kotlin.random.Random

object Slogans {
    val all = listOf(
        R.string.slogan_message_0,
        R.string.slogan_message_1,
        R.string.slogan_message_2,
        R.string.slogan_message_3,
        R.string.slogan_message_4,
        R.string.slogan_message_5,
        R.string.slogan_message_6,
        R.string.slogan_message_7,
        R.string.slogan_message_8,
        R.string.slogan_message_9,
        R.string.slogan_message_10,
    )

    private val newYearSlogan = R.string.slogan_message_10

    private val isNewYearPeriod: Boolean
        get() {
            val today = LocalDate.now()
            val month = today.monthValue
            val day = today.dayOfMonth
            return (month == 12 && day >= 28) || (month == 1 && day == 1)
        }

    val random: CaString
        get() {
            // 33% chance for "Dinner for One" slogan during New Year period
            if (isNewYearPeriod && Random.nextInt(3) == 0) {
                return newYearSlogan.toCaString()
            }
            return all[Random.nextInt(all.size)].toCaString()
        }
}
