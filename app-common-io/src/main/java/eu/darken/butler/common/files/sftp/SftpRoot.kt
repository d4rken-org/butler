package eu.darken.butler.common.files.sftp

import eu.darken.butler.common.files.SftpPath
import eu.darken.ssh.SftpSession
import kotlin.uuid.Uuid
import eu.darken.ssh.SftpPath as ServerPath

/** Maps between a location's paths and absolute server paths below its resolved root. */
object SftpRoot {

    /**
     * The server resolves every form: `""` is its initial directory, `media` is relative to it and
     * `/srv/media` is absolute, e.g. `media` for a user starting in `/home/darken` is `/home/darken/media`.
     */
    suspend fun resolve(session: SftpSession, basePath: String): ServerPath =
        session.canonicalize(basePath.ifEmpty { "." })

    fun toServer(root: ServerPath, path: SftpPath): ServerPath = ServerPath(root.segments + path.segments)

    /** @return null if [server] is outside [root] */
    fun toLocation(root: ServerPath, locationId: Uuid, server: ServerPath): SftpPath? {
        if (server.segments.size < root.segments.size) return null
        if (server.segments.subList(0, root.segments.size) != root.segments) return null
        return SftpPath(locationId, server.segments.drop(root.segments.size))
    }

    /**
     * Resolves a raw link target lexically against the link's directory, like `realpath -s`:
     * `../shared` stored in `/home/darken/photos/link` is `/home/darken/shared`, and `..` at `/` stays
     * at `/`.
     *
     * @return null if the target contains a segment no path can hold, e.g. a NUL
     */
    fun resolveLinkTarget(linkDirectory: ServerPath, rawTarget: String): ServerPath? {
        val resolved = ArrayDeque(if (rawTarget.startsWith("/")) emptyList() else linkDirectory.segments)
        rawTarget.split('/').forEach { segment ->
            when (segment) {
                "", "." -> Unit
                ".." -> resolved.removeLastOrNull()
                else -> resolved.addLast(segment)
            }
        }
        if (!resolved.all { ServerPath.isValidSegment(it) }) return null
        return ServerPath(resolved.toList())
    }
}
