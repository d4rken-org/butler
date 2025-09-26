package eu.darken.butler.common.files

import android.os.Parcelable
import androidx.annotation.Keep
import eu.darken.butler.common.ca.CaString
import eu.darken.butler.common.ca.toCaString
import eu.darken.butler.common.files.extensions.Segments
import kotlinx.serialization.Serializable

@Keep
@Serializable
sealed interface APath : Parcelable {
    val path: String
    val name: String
    val parent: APath?

    val userReadablePath: CaString
        get() = path.toCaString()
    val userReadableName: CaString
        get() = name.toCaString()

    val segments: Segments

    fun child(vararg segments: String): APath
}