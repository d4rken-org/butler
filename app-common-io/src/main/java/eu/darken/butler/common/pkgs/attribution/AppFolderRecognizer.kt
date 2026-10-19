package eu.darken.butler.common.pkgs.attribution

import eu.darken.butler.common.files.LocalPath
import eu.darken.butler.common.files.extensions.LocalPathNormalization

/**
 * A folder that Android names after the app owning it.
 *
 * [userId] is null where the path does not say which user the folder belongs to, e.g.
 * `Android/data` on an SD card.
 */
data class AppFolder(
    val pkgName: String,
    val userId: Int?,
)

/**
 * Recognizes app folders by their location alone; no filesystem or package queries.
 *
 * Only immediate children of an app area count:
 * ```
 * /data/data/com.example                               -> com.example, user 0
 * /data/user_de/10/com.example                         -> com.example, user 10
 * /storage/emulated/10/Android/data/com.example        -> com.example, user 10
 * /storage/1A2B-3C4D/Android/media/com.example         -> com.example, user unknown
 * /storage/emulated/0/Android/data/com.example/files   -> null
 * /storage/emulated/obb/com.example                    -> null (shared by all users)
 * ```
 */
object AppFolderRecognizer {

    private val PUBLIC_AREAS = setOf("data", "obb", "media")
    private val PRIVATE_AREAS = setOf("user", "user_de")
    private val PKG_NAME = Regex("[A-Za-z][A-Za-z0-9_]*(\\.[A-Za-z][A-Za-z0-9_]*)+")

    /** The cheap half of [recognize]: whether a folder name could be a package name at all. */
    fun isPackageName(name: String): Boolean = PKG_NAME.matches(name)

    fun recognize(path: LocalPath): AppFolder? {
        if (!isPackageName(path.name)) return null
        val segments = LocalPathNormalization.normalize(path.path)
            .split('/')
            .filter { it.isNotEmpty() }
        val name = segments.lastOrNull()?.takeIf { isPackageName(it) } ?: return null
        val userId = userIdOf(segments.dropLast(1)) ?: return null
        return AppFolder(pkgName = name, userId = userId.value)
    }

    /** Null when the parent is no app area; [UserId.value] null when the area has no owning user. */
    private fun userIdOf(parent: List<String>): UserId? = when {
        parent == listOf("data", "data") -> UserId(0)

        parent.size == 3 && parent[0] == "data" && parent[1] in PRIVATE_AREAS ->
            parent[2].toUserId()

        parent.size == 5 && parent[0] == "mnt" && parent[1] == "expand" && parent[3] in PRIVATE_AREAS ->
            parent[4].toUserId()

        parent.size == 5 && parent[3] == "Android" && parent[4] in PUBLIC_AREAS -> when {
            parent[0] == "storage" && parent[1] == "emulated" -> parent[2].toUserId()
            parent[0] == "data" && parent[1] == "media" -> parent[2].toUserId()
            parent[0] == "mnt" && parent[1] == "media_rw" -> UserId(null)
            else -> null
        }

        parent.size == 4 && parent[0] == "storage" && parent[2] == "Android" && parent[3] in PUBLIC_AREAS ->
            UserId(null).takeIf { parent[1] != "emulated" && parent[1] != "self" }

        else -> null
    }

    private fun String.toUserId(): UserId? = toIntOrNull()?.let { UserId(it) }

    private data class UserId(val value: Int?)
}
