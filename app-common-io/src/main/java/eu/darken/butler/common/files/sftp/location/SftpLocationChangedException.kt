package eu.darken.butler.common.files.sftp.location

import eu.darken.butler.common.ca.toCaString
import eu.darken.butler.common.error.HasLocalizedError
import eu.darken.butler.common.error.LocalizedError
import eu.darken.butler.common.error.LocalizedErrorContext
import eu.darken.butler.common.io.R
import kotlin.uuid.Uuid

/** An edit was made against a credential generation the location no longer points at. */
class SftpLocationChangedException(
    val locationId: Uuid,
    val expectedCredentialVersion: Int,
    val actualCredentialVersion: Int,
) : IllegalStateException(
    "Location $locationId is at credential $actualCredentialVersion, the edit expected $expectedCredentialVersion",
), HasLocalizedError {

    override fun getLocalizedError(context: LocalizedErrorContext) = LocalizedError(
        throwable = this,
        label = R.string.sftp_error_location_changed_title.toCaString(),
        description = R.string.sftp_error_location_changed_description.toCaString(),
    )
}
