package eu.darken.butler.common.files.smb.credentials

import kotlin.uuid.Uuid

/**
 * Encrypts credential payloads with a key that never leaves the device.
 *
 * The location id and payload version are authenticated but not encrypted, so a ciphertext copied
 * onto another location's row fails to decrypt instead of handing that location a foreign password.
 */
interface SmbCredentialCipher {

    data class Envelope(
        val envelopeVersion: Int,
        val keyAlias: String,
        val iv: ByteArray,
        val ciphertext: ByteArray,
    ) {
        override fun equals(other: Any?): Boolean {
            if (this === other) return true
            if (other !is Envelope) return false
            return envelopeVersion == other.envelopeVersion &&
                keyAlias == other.keyAlias &&
                iv.contentEquals(other.iv) &&
                ciphertext.contentEquals(other.ciphertext)
        }

        override fun hashCode(): Int {
            var result = envelopeVersion
            result = 31 * result + keyAlias.hashCode()
            result = 31 * result + iv.contentHashCode()
            result = 31 * result + ciphertext.contentHashCode()
            return result
        }
    }

    /**
     * @throws SmbCredentialUnavailableException if no key is available to encrypt with
     */
    fun encrypt(locationId: Uuid, payloadVersion: Int, plaintext: ByteArray): Envelope

    /**
     * @throws SmbCredentialUnavailableException if the key is gone or the envelope does not
     * authenticate against [locationId] and [payloadVersion]
     */
    fun decrypt(locationId: Uuid, payloadVersion: Int, envelope: Envelope): ByteArray

    /** Whether [decrypt] could even be attempted, i.e. the key behind [keyAlias] still exists. */
    fun isKeyAvailable(keyAlias: String): Boolean

    companion object {
        const val ENVELOPE_VERSION = 1
    }
}
