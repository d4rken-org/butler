package eu.darken.butler.searcher.core

import android.os.Parcelable
import eu.darken.butler.common.files.APath
import kotlinx.parcelize.Parcelize
import kotlinx.serialization.Serializable

@Serializable
@Parcelize
data class SearchPathConfig(
    val path: APath<*>,
    val enabled: Boolean = true,
    val label: String? = null,
) : Parcelable {

    fun displayText(): String = label ?: abbreviate(path)

    companion object {
        fun from(path: APath<*>) = SearchPathConfig(
            path = path,
            enabled = true,
            label = null
        )

        private fun abbreviate(path: APath<*>): String {
            val fullPath = path.path

            val segments = fullPath.split("/")
            val lastSegment = segments.lastOrNull { it.isNotEmpty() }

            return when {
                fullPath.length <= 20 -> fullPath
                lastSegment != null && lastSegment.length > 1 -> lastSegment
                fullPath == "/" || fullPath == "/storage/emulated/0" -> fullPath
                else -> "…${fullPath.takeLast(17)}"
            }
        }
    }
}
