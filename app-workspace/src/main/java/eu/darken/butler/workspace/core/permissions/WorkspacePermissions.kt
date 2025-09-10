package eu.darken.butler.workspace.core.permissions

import eu.darken.butler.common.permissions.Permission

data class WorkspacePermissions(
    val requirements: List<PermissionRequirement> = emptyList(),
    val hasSufficientPermissions: Boolean = true,
    val missingCritical: List<Permission> = emptyList(),
) {
    val needsPermissions: Boolean
        get() = missingCritical.isNotEmpty()
}