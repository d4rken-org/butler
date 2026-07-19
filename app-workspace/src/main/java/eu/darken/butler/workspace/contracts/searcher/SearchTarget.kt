package eu.darken.butler.workspace.contracts.searcher

import android.os.Parcelable
import eu.darken.butler.common.ca.CaString
import eu.darken.butler.common.ca.toCaString
import eu.darken.butler.common.files.APath
import kotlinx.parcelize.IgnoredOnParcel
import kotlinx.parcelize.Parcelize
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

@Serializable
@Parcelize
sealed class SearchTarget : Parcelable {
    abstract val enabled: Boolean

    /** Backend-agnostic label for progress rows, chips, and history entries. */
    abstract val displayText: CaString

    @Serializable
    @SerialName("path")
    @Parcelize
    data class Path(
        val path: APath<*>,
        override val enabled: Boolean = true,
        val label: String? = null,
    ) : SearchTarget() {

        @IgnoredOnParcel
        override val displayText: CaString
            get() = label?.toCaString() ?: path.userReadablePath

        companion object {
            fun from(path: APath<*>) = Path(
                path = path,
                enabled = true,
                label = null
            )
        }
    }
}
