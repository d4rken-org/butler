package eu.darken.butler.common.pkgs

import android.os.Parcelable
import eu.darken.butler.common.ca.CaDrawable
import eu.darken.butler.common.ca.CaString
import kotlinx.parcelize.Parcelize
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

interface Pkg {

    val id: Id

    val packageName: String
        get() = id.name

    val label: CaString?

    val icon: CaDrawable?

    @Parcelize
    @Serializable
    data class Id(
        @SerialName("name") val name: String,
    ) : Parcelable {
        override fun toString(): String = name
    }

}
