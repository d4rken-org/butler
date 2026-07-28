package eu.darken.butler.upgrade.core.billing

import eu.darken.butler.R
import eu.darken.butler.common.ca.toCaString
import eu.darken.butler.common.error.HasLocalizedError
import eu.darken.butler.common.error.LocalizedError
import eu.darken.butler.common.error.LocalizedErrorContext

class NetworkBillingException(cause: Throwable) :
    BillingException("Unable to connect to Google Play.", cause), HasLocalizedError {

    override fun getLocalizedError(context: LocalizedErrorContext): LocalizedError = LocalizedError(
        throwable = this,
        label = R.string.upgrades_gplay_network_error_title.toCaString(),
        description = R.string.upgrades_gplay_network_error_description.toCaString(),
    )
}
