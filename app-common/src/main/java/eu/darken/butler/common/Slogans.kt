package eu.darken.butler.common

import eu.darken.butler.common.ca.CaString
import eu.darken.butler.common.ca.toCaString
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
    )

    val random: CaString
        get() = all[Random.nextInt(all.size)].toCaString()
}
