package eu.darken.butler.common.files.sftp.credentials

import eu.darken.butler.common.ca.toCaString
import eu.darken.butler.common.error.HasLocalizedError
import eu.darken.butler.common.error.LocalizedError
import eu.darken.butler.common.error.LocalizedErrorContext
import eu.darken.butler.common.io.R
import java.io.IOException
import kotlin.uuid.Uuid

/**
 * The location keeps its key but not the key's passphrase, and none was entered since the app
 * started. Raised before anything is sent to the server.
 */
class SftpKeyPassphraseRequiredException(
    val locationId: Uuid,
) : IOException("No passphrase held for the key of $locationId"), HasLocalizedError {

    override fun getLocalizedError(context: LocalizedErrorContext) = LocalizedError(
        throwable = this,
        label = R.string.sftp_error_key_passphrase_required_title.toCaString(),
        description = R.string.sftp_error_key_passphrase_required_description.toCaString(),
    )
}
