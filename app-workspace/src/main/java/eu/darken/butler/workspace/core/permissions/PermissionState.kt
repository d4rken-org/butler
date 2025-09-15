package eu.darken.butler.workspace.core.permissions

import eu.darken.butler.common.permissions.Permission

data class PermissionState(
    val requirements: List<SetupRequirement> = emptyList(),
    val hasSufficientPermissions: Boolean = true,
    val missingCritical: List<Permission> = emptyList(),
) {
    val needsPermissions: Boolean
        get() = missingCritical.isNotEmpty()
}