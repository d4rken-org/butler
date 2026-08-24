package eu.darken.butler.common.files.smb.credentials

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/**
 * Plaintext shape of a stored credential. Only ever exists inside the vault: it is serialized,
 * encrypted and immediately discarded.
 */
@Serializable
data class SmbCredentialPayload(
    @SerialName("v") val version: Int = VERSION,
    val username: String,
    val domain: String? = null,
    val password: String,
) {
    companion object {
        const val VERSION = 1
    }
}
