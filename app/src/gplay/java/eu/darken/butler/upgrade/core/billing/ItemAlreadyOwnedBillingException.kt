package eu.darken.butler.upgrade.core.billing

import eu.darken.butler.R
import eu.darken.butler.common.ca.toCaString
import eu.darken.butler.common.error.HasLocalizedError
import eu.darken.butler.common.error.LocalizedError
import eu.darken.butler.common.error.LocalizedErrorContext

class ItemAlreadyOwnedBillingException(cause: Throwable) :
    BillingException("Item is already owned.", cause), HasLocalizedError {

    override fun getLocalizedError(context: LocalizedErrorContext): LocalizedError = LocalizedError(
        throwable = this,
        label = R.string.upgrades_gplay_already_owned_error_title.toCaString(),
        description = R.string.upgrades_gplay_already_owned_error_description.toCaString(),
    )
}
