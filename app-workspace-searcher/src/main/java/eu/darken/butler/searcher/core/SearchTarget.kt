package eu.darken.butler.searcher.core

import android.os.Parcelable
import eu.darken.butler.common.ca.CaString
import eu.darken.butler.common.ca.toCaString
import eu.darken.butler.common.files.APath
import kotlinx.parcelize.Parcelize
import kotlinx.serialization.Serializable

@Serializable
@Parcelize
sealed class SearchTarget : Parcelable {
    abstract val enabled: Boolean

    @Serializable
    @Parcelize
    data class Path(
        val path: APath<*>,
        override val enabled: Boolean = true,
        val label: String? = null,
    ) : SearchTarget() {

        val displayText: CaString = label?.toCaString() ?: path.userReadablePath

        companion object {
            fun from(path: APath<*>) = Path(
                path = path,
                enabled = true,
                label = null
            )
        }
    }
}
