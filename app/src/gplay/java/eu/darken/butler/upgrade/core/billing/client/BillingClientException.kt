package eu.darken.butler.upgrade.core.billing.client

import com.android.billingclient.api.BillingResult
import eu.darken.butler.upgrade.core.billing.BillingException

class BillingClientException(val result: BillingResult) : BillingException(result.debugMessage) {

    override fun toString(): String =
        "BillingClientException(code=${result.responseCode}, message=${result.debugMessage})"
}