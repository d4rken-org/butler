package eu.darken.butler.common.progress

import android.text.format.Formatter
import eu.darken.butler.common.ca.CaDrawable
import eu.darken.butler.common.ca.CaString
import eu.darken.butler.common.ca.caString
import eu.darken.butler.common.ca.toCaString
import eu.darken.butler.common.easterEggProgressMsg
import kotlinx.coroutines.flow.Flow
import kotlin.math.ceil

interface Progress {

    data class Data(
        val icon: CaDrawable? = null,
        val primary: CaString = eu.darken.butler.common.R.string.general_progress_loading.toCaString(),
        val secondary: CaString = easterEggProgressMsg.toCaString(),
        val count: Count = Count.Indeterminate(),
        val extra: Any? = null
    )

    interface Host {
        val progress: Flow<Data?>
    }

    interface Client {
        fun updateProgress(update: (Data?) -> Data?)
    }

    sealed interface Count {
        val current: Long
        val max: Long
        val displayValue: CaString
        val percentage: Float
            get() = if (max > 0) current / max.toFloat() else 0f

        data class Percent(override val current: Long, override val max: Long) : Count {

            constructor(current: Int, max: Int) : this(current.toLong(), max.toLong())
            constructor(max: Int) : this(0, max)
            constructor(max: Long) : this(0, max)

            override val displayValue: CaString = caString {
                when {
                    current == 0L && max == 0L -> "NaN"
                    current == 0L -> "0%"
                    else -> "${ceil(((current.toDouble() / max.toDouble()) * 100)).toInt()}%"
                }
            }

            fun increment(value: Int = 1): Percent {
                return Percent(current + value, max)
            }
        }

        class Counter(override val current: Long, override val max: Long) : Count {

            constructor(current: Int, max: Int) : this(current.toLong(), max.toLong())
            constructor(max: Int) : this(0, max)
            constructor(max: Long) : this(0, max)

            override val displayValue: CaString = caString { "$current/$max" }

            fun increment(value: Int = 1) = Counter(current + value, max)
        }

        data class Size(override val current: Long, override val max: Long) : Count {
            override val displayValue: CaString = caString {
                val curSize = Formatter.formatShortFileSize(it, current)
                val maxSize = Formatter.formatShortFileSize(it, max)
                "$curSize/$maxSize"
            }
        }

        data class Indeterminate(override val current: Long = 0, override val max: Long = 0) : Count {
            override val displayValue: CaString = CaString.EMPTY
        }

        data class None(override val current: Long = -1, override val max: Long = -1) : Count {
            override val displayValue: CaString = CaString.EMPTY
        }
    }
}