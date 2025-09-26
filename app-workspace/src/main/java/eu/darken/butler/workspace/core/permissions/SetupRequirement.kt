package eu.darken.butler.workspace.core.permissions

import eu.darken.butler.common.ca.CaString
import eu.darken.butler.common.ca.toCaString
import eu.darken.butler.common.permissions.Permission

data class SetupRequirement(
    val permission: Permission,
    val isRequired: Boolean,
) {
    val description: CaString
        get() = when (permission) {
            Permission.READ_EXTERNAL_STORAGE,
            Permission.WRITE_EXTERNAL_STORAGE,
            Permission.MANAGE_EXTERNAL_STORAGE -> eu.darken.butler.common.R.string.common_permission_storage_manage_description.toCaString()

            else -> throw NotImplementedError("Not implemented for $permission")
        }
}