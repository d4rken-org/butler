package eu.darken.butler.common.files.errors

import eu.darken.butler.common.ca.CaString
import eu.darken.butler.common.ca.caString
import eu.darken.butler.common.ca.toCaString
import eu.darken.butler.common.error.HasLocalizedError
import eu.darken.butler.common.error.LocalizedError
import eu.darken.butler.common.error.LocalizedErrorContext
import eu.darken.butler.common.files.APath
import eu.darken.butler.common.io.R

/**
 * Thrown when a path operation cannot be performed due to a permission failure.
 *
 * Carries only structural data — `reason` describes *what* went wrong (no mechanism available,
 * read-only filesystem, OS denial, …). The "which Setup screen would help?" decision is left to
 * the renderer via [LocalizedErrorContext.permissionFixResolver], so the throw site does not
 * need to know about the UX taxonomy.
 */
class PathPermissionDeniedException(
    path: APath<*>,
    val operation: String,
    val reason: Reason,
    cause: Throwable? = null,
) : PathException(
    message = "Permission denied (${reason.name}) for $operation",
    path = path,
    cause = cause,
), HasLocalizedError {

    enum class Reason {
        /** Gateway could not attempt the op — no usable mode (direct/root/adb) available. */
        NO_MECHANISM,

        /** EROFS — the filesystem is mounted read-only. Even elevation cannot bypass this. */
        READONLY_FILESYSTEM,

        /** EPERM — SELinux / immutable / kernel policy denied the op. */
        NOT_PERMITTED,

        /** EACCES — generic access denial. */
        ACCESS_DENIED,
    }

    override fun getLocalizedError(context: LocalizedErrorContext): LocalizedError {
        val fix = context.permissionFixResolver?.resolve(this)
        return LocalizedError(
            throwable = this,
            label = R.string.path_action_permission_denied_title.toCaString(),
            description = describe(),
            fixActionLabel = fix?.label(),
            fixAction = fix?.action(context),
        )
    }

    private fun describe(): CaString {
        val resolvedPath = path!!
        val pathDisplay = resolvedPath.name.ifBlank { resolvedPath.path }
        val parentDisplay = resolvedPath.parent?.path ?: "/"
        val descriptionRes = when (reason) {
            Reason.NO_MECHANISM -> R.string.path_action_permission_denied_no_mechanism_description
            Reason.READONLY_FILESYSTEM -> R.string.path_action_permission_denied_readonly_description
            Reason.NOT_PERMITTED -> R.string.path_action_permission_denied_not_permitted_description
            Reason.ACCESS_DENIED -> R.string.path_action_permission_denied_description
        }
        return caString { it.getString(descriptionRes, pathDisplay, parentDisplay) }
    }
}
