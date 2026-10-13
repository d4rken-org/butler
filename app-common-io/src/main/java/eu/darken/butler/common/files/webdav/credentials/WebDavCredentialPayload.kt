package eu.darken.butler.common.files.webdav.credentials

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/**
 * Plaintext shape of a stored credential. Only ever exists inside the vault: it is serialized,
 * encrypted and immediately discarded.
 */
@Serializable
data class WebDavCredentialPayload(
    @SerialName("v") val version: Int = VERSION,
    val username: String,
    val password: String,
) {
    companion object {
        const val VERSION = 1
    }
}
