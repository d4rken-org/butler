package eu.darken.butler.common.files

import android.net.Uri
import androidx.annotation.Keep
import eu.darken.butler.common.SafUri
import eu.darken.butler.common.ca.CaString
import eu.darken.butler.common.ca.caString
import kotlinx.parcelize.Parcelize
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import java.io.File

@Keep @Parcelize
@Serializable
@SerialName("SAF")
data class SAFPath(
    internal val treeRoot: String,
    override val segments: List<String>,
) : APath<SAFPath> {

    val treeRootUri: SafUri
        get() = SafUri.parse(treeRoot)

    init {
        val paths = treeRootUri.pathSegments
        require(paths.size >= 2 && "tree" == paths[0]) { "SAFFile URI's must be a tree uri: $treeRoot" }
    }

    override val userReadableName: CaString
        get() = super.userReadableName

    override val userReadablePath: CaString
        get() {
            val treeRootPath = treeRootUri.path
            val documentId = treeRootPath?.let { TREE_DOCUMENT_ID_REGEX.matchEntire(it)?.groupValues?.get(1) }

            if (documentId != null) {
                // Parse document ID: "primary" or "primary:Folder1" or "primary:Folder1/SubFolder"
                val parts = documentId.split(":", limit = 2)
                val storageId = parts[0]

                // Extract base path from tree root (after the colon)
                val basePath = parts.getOrNull(1)
                    ?.split("/")
                    ?.filter { it.isNotEmpty() }
                    ?: emptyList()

                // Combine base path with additional segments
                val allSegments = basePath + segments

                return when {
                    allSegments.isNotEmpty() -> caString {
                        "[$storageId]/${allSegments.joinToString("/")}"
                    }
                    else -> caString {
                        "[$storageId]"
                    }
                }
            }

            return super.userReadablePath
        }

    override val path: String
        get() = "${File.separator}${(treeRootUri.pathSegments + segments).joinToString(File.separator)}"

    val pathUri: SafUri
        get() {
            if (segments.isEmpty()) return treeRootUri

            val uriString = StringBuilder(treeRoot).apply {
                append("%3A") // SafUri.encode(":")
                segments.forEachIndexed { index, segment ->
                    // By position, not by value: an earlier segment may repeat the last one
                    if (index != 0) {
                        append("%2F") // SafUri.encode(File.separator)
                    }
                    append(SafUri.encode(segment))
                }
            }
            return SafUri.parse(uriString.toString())
        }

    override val name: String
        get() = when {
            segments.isNotEmpty() -> segments.last()
            else -> treeRootUri.pathSegments.last().split('/').last()
        }

    override fun child(vararg segments: String): SAFPath {
        return build(this.treeRoot, *this.segments.toTypedArray(), *segments)
    }

    override val parent: SAFPath?
        get() = when {
            segments.isEmpty() -> null
            segments.size == 1 -> build(treeRoot)
            else -> build(treeRoot, *segments.dropLast(1).toTypedArray())
        }

    override fun toString(): String = "SAFPath(treeRoot=$treeRoot, segments=$segments)"

    companion object {
        fun build(base: String, vararg segs: String): SAFPath = SAFPath(base, segs.toList())

        fun build(base: Uri, vararg segs: String): SAFPath = build(base.toString(), *segs)

        fun build(base: SafUri, vararg segs: String): SAFPath = build(base.toString(), *segs)

        private val TREE_DOCUMENT_ID_REGEX by lazy { Regex("^/tree/(.+)$") }
    }
}