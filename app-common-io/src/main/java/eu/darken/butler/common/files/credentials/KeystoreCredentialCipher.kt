package eu.darken.butler.common.files.credentials

import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import eu.darken.butler.common.debug.logging.Logging.Priority.ERROR
import eu.darken.butler.common.debug.logging.Logging.Priority.WARN
import eu.darken.butler.common.debug.logging.asLog
import eu.darken.butler.common.debug.logging.log
import java.io.IOException
import java.security.GeneralSecurityException
import java.security.KeyStore
import java.security.ProviderException
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec
import kotlin.uuid.Uuid

/**
 * AES-256-GCM through a non-auth-bound AndroidKeyStore key, so background operations keep working
 * on a locked screen.
 *
 * Each protocol gets its own [keyAlias] and [aadDomain]. The AAD is `aadDomain + locationId +
 * payloadVersion`, e.g. for location `11111111-…` at payload version 1 with the domain `sftp`:
 * `73 66 74 70 | 11 11 11 11 … | 01`. An empty domain yields the bare `locationId + payloadVersion`.
 *
 * @param unavailable builds the protocol's own "credential unavailable" error
 */
class KeystoreCredentialCipher(
    private val keyAlias: String,
    aadDomain: ByteArray,
    private val tag: String,
    private val unavailable: (locationId: Uuid, message: String, cause: Throwable?) -> IOException,
) : CredentialCipher {

    private val aadDomain = aadDomain.copyOf()

    private val keyStore: KeyStore
        get() = KeyStore.getInstance(KEYSTORE_PROVIDER).apply { load(null) }

    /**
     * Everything a keystore that is present but unusable throws at us: crypto errors, the hardware
     * or service failures the provider reports as an unchecked [ProviderException], and the
     * [IOException] a keystore that cannot be loaded raises.
     */
    private val Throwable.isKeystoreFailure: Boolean
        get() = this is GeneralSecurityException || this is ProviderException || this is IOException

    override fun encrypt(locationId: Uuid, payloadVersion: Int, plaintext: ByteArray): CredentialCipher.Envelope {
        // A key can also fail on use rather than on lookup: an invalidated one is still returned and
        // only throws at init, so the whole sequence translates into the same failure.
        return try {
            val key = getOrCreateKey()

            val cipher = Cipher.getInstance(TRANSFORMATION).apply {
                init(Cipher.ENCRYPT_MODE, key)
                updateAAD(aad(locationId, payloadVersion))
            }

            CredentialCipher.Envelope(
                envelopeVersion = CredentialCipher.ENVELOPE_VERSION,
                keyAlias = keyAlias,
                iv = cipher.iv,
                ciphertext = cipher.doFinal(plaintext),
            )
        } catch (e: Exception) {
            if (!e.isKeystoreFailure) throw e
            log(tag, ERROR) { "Credential for $locationId could not be encrypted: ${e.asLog()}" }
            throw unavailable(locationId, "Keystore key unavailable", e)
        }
    }

    override fun decrypt(
        locationId: Uuid,
        payloadVersion: Int,
        envelope: CredentialCipher.Envelope,
    ): ByteArray {
        if (envelope.envelopeVersion != CredentialCipher.ENVELOPE_VERSION) {
            throw unavailable(locationId, "Unknown credential envelope version ${envelope.envelopeVersion}", null)
        }

        val key = try {
            keyStore.getKey(envelope.keyAlias, null) as? SecretKey
        } catch (e: Exception) {
            if (!e.isKeystoreFailure) throw e
            log(tag, WARN) { "Keystore lookup failed for ${envelope.keyAlias}: ${e.asLog()}" }
            throw unavailable(locationId, "Keystore key ${envelope.keyAlias} is unavailable", e)
        } ?: throw unavailable(locationId, "Keystore key ${envelope.keyAlias} is gone", null)

        return try {
            Cipher.getInstance(TRANSFORMATION).run {
                init(Cipher.DECRYPT_MODE, key, GCMParameterSpec(GCM_TAG_BITS, envelope.iv))
                updateAAD(aad(locationId, payloadVersion))
                doFinal(envelope.ciphertext)
            }
        } catch (e: Exception) {
            if (!e.isKeystoreFailure) throw e
            log(tag, WARN) { "Credential for $locationId failed to decrypt: ${e.asLog()}" }
            throw unavailable(locationId, "Credential did not authenticate", e)
        }
    }

    override fun isKeyAvailable(keyAlias: String): Boolean = try {
        keyStore.containsAlias(keyAlias)
    } catch (e: Exception) {
        if (!e.isKeystoreFailure) throw e
        log(tag, WARN) { "Keystore is unusable: ${e.asLog()}" }
        false
    }

    private fun aad(locationId: Uuid, payloadVersion: Int): ByteArray =
        aadDomain + locationId.toByteArray() + payloadVersion.toByte()

    private fun getOrCreateKey(): SecretKey {
        (keyStore.getKey(keyAlias, null) as? SecretKey)?.let { return it }

        val generator = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, KEYSTORE_PROVIDER)
        generator.init(
            KeyGenParameterSpec.Builder(
                keyAlias,
                KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT,
            )
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                .setKeySize(KEY_SIZE_BITS)
                .setUserAuthenticationRequired(false)
                .setRandomizedEncryptionRequired(true)
                .build(),
        )
        return generator.generateKey()
    }

    companion object {
        private const val KEYSTORE_PROVIDER = "AndroidKeyStore"
        private const val TRANSFORMATION = "AES/GCM/NoPadding"
        private const val GCM_TAG_BITS = 128
        private const val KEY_SIZE_BITS = 256
    }
}
