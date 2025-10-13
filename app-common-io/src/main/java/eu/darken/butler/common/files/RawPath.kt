package eu.darken.butler.common.files

import androidx.annotation.Keep
import kotlinx.parcelize.Parcelize
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import java.io.File

@Parcelize
@Keep
@Serializable
@SerialName("RAW")
data class RawPath(
    override val path: String
) : APath<RawPath> {

    override val name: String
        get() = path.substringAfterLast(File.separatorChar)

    override val segments: List<String>
        get() = LocalPath.build(path).segments

    override fun child(vararg segments: String): RawPath {
        throw NotImplementedError()
    }

    override val parent: RawPath
        get() = throw NotImplementedError()

    companion object {
        fun build(base: File, vararg crumbs: String): RawPath = build(base.path, *crumbs)

        fun build(vararg crumbs: String): RawPath {
            var compacter = File(crumbs[0])
            for (i in 1 until crumbs.size) {
                compacter = File(compacter, crumbs[i])
            }
            return RawPath(compacter.path)
        }
    }
}