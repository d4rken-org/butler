package eu.darken.butler.common

import eu.darken.butler.common.ca.CaString
import eu.darken.butler.common.ca.toCaString

object Slogans {
    private val general = listOf(
        R.string.slogan_general_message_0,
        R.string.slogan_general_message_1,
        R.string.slogan_general_message_2,
        R.string.slogan_general_message_3,
        R.string.slogan_general_message_4,
        R.string.slogan_general_message_5,
        R.string.slogan_general_message_6,
        R.string.slogan_general_message_7,
        R.string.slogan_general_message_8,
        R.string.slogan_general_message_9,
    )

    private val xmas = listOf(
        R.string.slogan_xmas_message_0,
        R.string.slogan_xmas_message_1,
    )

    private val newYear = listOf(
        R.string.slogan_newyear_message_0,
    )

    private val halloween = listOf(
        R.string.slogan_halloween_message_0,
        R.string.slogan_halloween_message_1,
    )

    private val stPatricks = listOf(
        R.string.slogan_stpatricks_message_0,
        R.string.slogan_stpatricks_message_1,
    )

    private val aprilFools = listOf(
        R.string.slogan_aprilfools_message_0,
        R.string.slogan_aprilfools_message_1,
    )

    private val oktoberfest = listOf(
        R.string.slogan_oktoberfest_message_0,
        R.string.slogan_oktoberfest_message_1,
    )

    val random: CaString
        get() = when (Occasions.current()) {
            Occasions.Period.HALLOWEEN -> halloween.random()
            Occasions.Period.ST_PATRICKS -> stPatricks.random()
            Occasions.Period.APRIL_FOOLS -> aprilFools.random()
            Occasions.Period.OKTOBERFEST -> oktoberfest.random()
            Occasions.Period.XMAS -> xmas.random()
            Occasions.Period.NEW_YEAR -> newYear.random()
            Occasions.Period.NONE -> general.random()
        }.toCaString()
}
