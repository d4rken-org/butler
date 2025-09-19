package eu.darken.butler.workspace.core.permissions

import eu.darken.butler.common.ca.CaString
import eu.darken.butler.common.permissions.Permission

data class SetupRequirement(
    val permission: Permission,
    val isRequired: Boolean,
    val description: CaString,
)