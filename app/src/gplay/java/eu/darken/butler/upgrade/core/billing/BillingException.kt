package eu.darken.butler.upgrade.core.billing

import eu.darken.butler.R
import eu.darken.butler.common.ca.toCaString
import eu.darken.butler.common.error.HasLocalizedError
import eu.darken.butler.common.error.LocalizedError
import eu.darken.butler.common.error.LocalizedErrorContext

open class BillingException(
    override val message: String? = null,
    override val cause: Throwable? = null,
) : Exception(), HasLocalizedError {

    override fun getLocalizedError(context: LocalizedErrorContext): LocalizedError = LocalizedError(
        throwable = this,
        label = R.string.upgrades_gplay_billing_error_label.toCaString(),
        description = R.string.upgrades_gplay_billing_error_description.toCaString(message ?: "?")
    )
}