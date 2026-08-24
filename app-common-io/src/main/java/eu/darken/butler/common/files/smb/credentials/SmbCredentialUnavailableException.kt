package eu.darken.butler.common.files.smb.credentials

import eu.darken.butler.common.ca.toCaString
import eu.darken.butler.common.error.HasLocalizedError
import eu.darken.butler.common.error.LocalizedError
import eu.darken.butler.common.error.LocalizedErrorContext
import eu.darken.butler.common.io.R
import java.io.IOException
import kotlin.uuid.Uuid

/**
 * A stored credential exists but cannot be turned back into a password: the Keystore key is gone
 * (screen lock reset, app data restored to another device) or the envelope failed to authenticate.
 *
 * Never resolved by deleting the location or by silently falling back to guest access, the user
 * re-enters the password instead.
 */
class SmbCredentialUnavailableException(
    val locationId: Uuid,
    message: String,
    cause: Throwable? = null,
) : IOException(message, cause), HasLocalizedError {

    override fun getLocalizedError(context: LocalizedErrorContext) = LocalizedError(
        throwable = this,
        label = R.string.smb_error_credential_unavailable_title.toCaString(),
        description = R.string.smb_error_credential_unavailable_description.toCaString(),
    )
}
