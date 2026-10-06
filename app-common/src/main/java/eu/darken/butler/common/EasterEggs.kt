package eu.darken.butler.common

import androidx.annotation.StringRes
import kotlin.random.Random

@get:StringRes
val easterEggProgressMsg: Int
    get() = easterEggProgressMsg(Occasions.current(), Random)

/** During an occasion, half the draws come from its own lines. */
@StringRes
internal fun easterEggProgressMsg(occasion: Occasions.Period, random: Random): Int {
    val seasonal = seasonalEggs(occasion)
    if (seasonal.isNotEmpty() && random.nextBoolean()) return seasonal.random(random)
    return generalEggs.random(random)
}

private val generalEggs = listOf(
    R.string.general_progress_loading_egg_0,
    R.string.general_progress_loading_egg_1,
    R.string.general_progress_loading_egg_2,
    R.string.general_progress_loading_egg_3,
    R.string.general_progress_loading_egg_4,
    R.string.general_progress_loading_egg_5,
    R.string.general_progress_loading_egg_6,
    R.string.general_progress_loading_egg_7,
    R.string.general_progress_loading_egg_8,
    R.string.general_progress_loading_egg_9,
    R.string.general_progress_loading_egg_10,
    R.string.general_progress_loading_egg_11,
    R.string.general_progress_loading_egg_12,
    R.string.general_progress_loading_egg_13,
    R.string.general_progress_loading_egg_14,
)

internal fun seasonalEggs(occasion: Occasions.Period): List<Int> = when (occasion) {
    Occasions.Period.HALLOWEEN -> listOf(
        R.string.general_progress_loading_egg_halloween_0,
        R.string.general_progress_loading_egg_halloween_1,
        R.string.general_progress_loading_egg_halloween_2,
        R.string.general_progress_loading_egg_halloween_3,
    )

    Occasions.Period.ST_PATRICKS -> listOf(
        R.string.general_progress_loading_egg_stpatricks_0,
        R.string.general_progress_loading_egg_stpatricks_1,
        R.string.general_progress_loading_egg_stpatricks_2,
    )

    Occasions.Period.APRIL_FOOLS -> listOf(
        R.string.general_progress_loading_egg_aprilfools_0,
        R.string.general_progress_loading_egg_aprilfools_1,
    )

    Occasions.Period.OKTOBERFEST -> listOf(
        R.string.general_progress_loading_egg_oktoberfest_0,
    )

    Occasions.Period.XMAS -> listOf(
        R.string.general_progress_loading_egg_xmas_0,
        R.string.general_progress_loading_egg_xmas_1,
        R.string.general_progress_loading_egg_xmas_2,
        R.string.general_progress_loading_egg_xmas_3,
    )

    Occasions.Period.NEW_YEAR -> listOf(
        R.string.general_progress_loading_egg_newyear_0,
        R.string.general_progress_loading_egg_newyear_1,
        R.string.general_progress_loading_egg_newyear_2,
        R.string.general_progress_loading_egg_newyear_3,
    )

    Occasions.Period.NONE -> emptyList()
}
