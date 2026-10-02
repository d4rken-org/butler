package eu.darken.butler.common.files.sftp.credentials

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/**
 * Plaintext shape of a stored credential. Only ever exists inside the vault: it is serialized,
 * encrypted and immediately discarded.
 *
 * Holds either a [password] or a [privateKey] (base64 of the key file bytes) with an optional
 * [passphrase].
 */
@Serializable
data class SftpCredentialPayload(
    @SerialName("v") val version: Int = VERSION,
    val username: String,
    val password: String? = null,
    val privateKey: String? = null,
    val passphrase: String? = null,
) {
    companion object {
        const val VERSION = 1
    }
}
