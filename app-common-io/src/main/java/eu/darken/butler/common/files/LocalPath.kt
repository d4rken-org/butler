package eu.darken.butler.common.files

import androidx.annotation.Keep
import eu.darken.butler.common.files.extensions.Segments
import eu.darken.butler.common.serialization.FileParcelizer
import eu.darken.butler.common.serialization.FileSerializer
import kotlinx.parcelize.IgnoredOnParcel
import kotlinx.parcelize.Parcelize
import kotlinx.parcelize.TypeParceler
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.Transient
import java.io.File

/**
 * Represents a path on the local file system.
 *
 * **IMPORTANT: LocalPath only accepts ABSOLUTE paths.**
 *
 * This requirement ensures:
 * - Unambiguous file operations (especially with root/ADB access)
 * - Consistency with Android file system APIs
 * - Safe serialization/deserialization (no context-dependent path resolution)
 *
 * @throws IllegalArgumentException if the provided File is not absolute
 *
 * Example usage:
 * ```
 * LocalPath.build("/storage/emulated/0/file.txt")  // ✓ Valid
 * LocalPath.build("relative/path")                  // ✗ Throws exception
 * ```
 */
@Keep
@Serializable
@SerialName("LOCAL")
@Parcelize
@TypeParceler<File, FileParcelizer>()
data class LocalPath(
    val file: @Serializable(with = FileSerializer::class) File
) : APath<LocalPath> {

    init {
        require(file.isAbsolute) { "LocalPath must be ABSOLUTE: $file" }
    }

    @IgnoredOnParcel
    override val path: String
        get() = file.path

    @IgnoredOnParcel
    override val name: String
        get() = file.name

    @IgnoredOnParcel
    @Transient
    private var segmentsCache: Segments? = null

    @IgnoredOnParcel
    override val segments: Segments
        get() = segmentsCache ?: run {
            when (path) {
                File.separator -> listOf("")
                else -> path.split(File.separatorChar)
            }.also { segmentsCache = it }
        }

    override fun child(vararg segments: String): LocalPath = build(this.file, *segments)

    override fun toString(): String = "LocalPath($path)"

    override fun describeContents(): Int = 0

    override val parent: LocalPath?
        get() {
            val raw = segments.dropLast(1)
            return if (raw.isEmpty()) null else build(*raw.toTypedArray())
        }

    companion object {
        fun build(base: File, vararg crumbs: String): LocalPath {
            var result = base
            for (crumb in crumbs) {
                if (crumb.isNotEmpty()) {
                    result = File(result, crumb)
                }
            }
            return build(result)
        }

        fun build(vararg crumbs: String): LocalPath {
            var compacter = File(
                when {
                    crumbs.isEmpty() -> File.separator
                    crumbs[0].startsWith(File.separatorChar) -> crumbs[0]
                    else -> File.separator + crumbs[0]
                }
            )

            for (i in 1 until crumbs.size) {
                compacter = File(compacter, crumbs[i])
            }

            return build(compacter)
        }

        fun build(file: File): LocalPath = LocalPath(file)
    }


}