package eu.darken.butler.explorer.core.permissions

import eu.darken.butler.common.ca.CaString
import eu.darken.butler.common.permissions.Permission
import eu.darken.butler.explorer.core.ExplorerNavigation

data class PermissionRequirement(
    val permission: Permission,
    val isRequired: Boolean,
    val reason: CaString,
    val alternativeAccess: ExplorerNavigation.Target? = null,
)

data class LocationPermissions(
    val requirements: List<PermissionRequirement> = emptyList(),
    val hasSufficientPermissions: Boolean = true,
    val missingCritical: List<Permission> = emptyList(),
) {
    val needsPermissions: Boolean
        get() = missingCritical.isNotEmpty()
}