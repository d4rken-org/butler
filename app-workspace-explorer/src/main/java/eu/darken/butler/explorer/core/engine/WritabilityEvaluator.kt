package eu.darken.butler.explorer.core.engine

import eu.darken.butler.common.files.APath
import eu.darken.butler.common.files.ArchivePath
import eu.darken.butler.common.files.LocalPath
import eu.darken.butler.common.files.SAFPath
import eu.darken.butler.common.files.SmbPath
import eu.darken.butler.common.files.metadata.Ownership
import eu.darken.butler.common.files.metadata.Permissions
import javax.inject.Inject

/**
 * Evaluates writability for explorer items based on permissions, ownership, and access context.
 *
 * Writability rules (in priority order):
 * 1. Root/ADB available for local paths → always writable
 * 2. SAF paths → use SAFLocation.hasWritePermission
 * 3. Unknown permissions → null (treated as writable by consumers)
 * 4. Check Unix permissions based on ownership
 */
class WritabilityEvaluator @Inject constructor() {

    /**
     * Evaluates writability for an item.
     *
     * @return true if writable, false if not writable, null if unknown (treated as writable)
     */
    fun evaluate(
        path: APath<*>,
        permissions: Permissions?,
        ownership: Ownership?,
        context: WritabilityContext,
    ): Boolean? {
        // Archive contents are never writable, regardless of access level.
        if (path is ArchivePath) {
            return false
        }

        // Rule 1: Elevated access for local paths = always writable
        if (path is LocalPath && context.hasElevatedAccess) {
            return true
        }

        // Rule 2: SAF paths use SAF location permission
        if (path is SAFPath) {
            return context.safCanWrite
        }

        // Rule 3: The SMB server enforces access, there are no Unix bits to evaluate here.
        if (path is SmbPath) {
            return true
        }

        // Rule 4: Unknown permissions = unknown (treated as writable by consumers)
        if (permissions == null) {
            return null
        }

        // Rule 5: Evaluate Unix permissions
        return evaluateUnixPermissions(permissions, ownership, context.appUid)
    }

    private fun evaluateUnixPermissions(
        permissions: Permissions,
        ownership: Ownership?,
        appUid: Int,
    ): Boolean {
        // If we own the file, check owner write
        if (ownership?.userId == appUid.toLong()) {
            return permissions.ownerCanWrite
        }

        // If we're in the group (simplified: check if GID matches app UID which happens for app data)
        if (ownership?.groupId == appUid.toLong()) {
            return permissions.groupCanWrite
        }

        // Fall back to "others" permission
        return permissions.othersCanWrite
    }
}
