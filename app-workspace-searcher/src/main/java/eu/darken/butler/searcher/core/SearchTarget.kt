package eu.darken.butler.searcher.core

import android.os.Parcelable
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

        fun displayText(): String = label ?: abbreviate(path)

        companion object {
            fun from(path: APath<*>) = Path(
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
}
