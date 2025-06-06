package eu.darken.butler.common.pkgs

import eu.darken.butler.common.ca.toCaString
import eu.darken.butler.common.error.HasLocalizedError
import eu.darken.butler.common.error.LocalizedError

class InvalidPkgInventoryException(
    override val message: String
) : IllegalStateException(), HasLocalizedError {

    override fun getLocalizedError(): LocalizedError = LocalizedError(
        throwable = this,
        label = R.string.pkgrepo_invalid_inventory_error_title.toCaString(),
        description = R.string.pkgrepo_invalid_inventory_error_description.toCaString(),
    )
}