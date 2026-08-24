package eu.darken.butler.common.files.smb.credentials

import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import eu.darken.butler.common.debug.logging.Logging.Priority.ERROR
import eu.darken.butler.common.debug.logging.Logging.Priority.WARN
import eu.darken.butler.common.debug.logging.asLog
import eu.darken.butler.common.debug.logging.log
import eu.darken.butler.common.debug.logging.logTag
import java.security.GeneralSecurityException
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.uuid.Uuid

/**
 * AES-256-GCM through a non-auth-bound AndroidKeyStore key, so background operations keep working
 * on a locked screen.
 */
@Singleton
class KeystoreSmbCredentialCipher @Inject constructor() : SmbCredentialCipher {

    private val keyStore: KeyStore
        get() = KeyStore.getInstance(KEYSTORE_PROVIDER).apply { load(null) }

    override fun encrypt(locationId: Uuid, payloadVersion: Int, plaintext: ByteArray): SmbCredentialCipher.Envelope {
        val key = try {
            getOrCreateKey()
        } catch (e: GeneralSecurityException) {
            log(TAG, ERROR) { "No key available to encrypt with: ${e.asLog()}" }
            throw SmbCredentialUnavailableException(locationId, "Keystore key unavailable", e)
        }

        val cipher = Cipher.getInstance(TRANSFORMATION).apply {
            init(Cipher.ENCRYPT_MODE, key)
            updateAAD(aad(locationId, payloadVersion))
        }

        return SmbCredentialCipher.Envelope(
            envelopeVersion = SmbCredentialCipher.ENVELOPE_VERSION,
            keyAlias = KEY_ALIAS,
            iv = cipher.iv,
            ciphertext = cipher.doFinal(plaintext),
        )
    }

    override fun decrypt(
        locationId: Uuid,
        payloadVersion: Int,
        envelope: SmbCredentialCipher.Envelope,
    ): ByteArray {
        if (envelope.envelopeVersion != SmbCredentialCipher.ENVELOPE_VERSION) {
            throw SmbCredentialUnavailableException(
                locationId,
                "Unknown credential envelope version ${envelope.envelopeVersion}",
            )
        }

        val key = try {
            keyStore.getKey(envelope.keyAlias, null) as? SecretKey
        } catch (e: GeneralSecurityException) {
            log(TAG, WARN) { "Keystore lookup failed for ${envelope.keyAlias}: ${e.asLog()}" }
            null
        } ?: throw SmbCredentialUnavailableException(locationId, "Keystore key ${envelope.keyAlias} is gone")

        return try {
            Cipher.getInstance(TRANSFORMATION).run {
                init(Cipher.DECRYPT_MODE, key, GCMParameterSpec(GCM_TAG_BITS, envelope.iv))
                updateAAD(aad(locationId, payloadVersion))
                doFinal(envelope.ciphertext)
            }
        } catch (e: GeneralSecurityException) {
            log(TAG, WARN) { "Credential for $locationId failed to decrypt: ${e.asLog()}" }
            throw SmbCredentialUnavailableException(locationId, "Credential did not authenticate", e)
        }
    }

    override fun isKeyAvailable(keyAlias: String): Boolean = try {
        keyStore.containsAlias(keyAlias)
    } catch (e: GeneralSecurityException) {
        log(TAG, WARN) { "Keystore is unusable: ${e.asLog()}" }
        false
    }

    private fun aad(locationId: Uuid, payloadVersion: Int): ByteArray =
        locationId.toByteArray() + payloadVersion.toByte()

    private fun getOrCreateKey(): SecretKey {
        (keyStore.getKey(KEY_ALIAS, null) as? SecretKey)?.let { return it }

        val generator = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, KEYSTORE_PROVIDER)
        generator.init(
            KeyGenParameterSpec.Builder(
                KEY_ALIAS,
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
        private val TAG = logTag("SMB", "Credentials", "Cipher")
        private const val KEYSTORE_PROVIDER = "AndroidKeyStore"
        private const val KEY_ALIAS = "butler_smb_credentials"
        private const val TRANSFORMATION = "AES/GCM/NoPadding"
        private const val GCM_TAG_BITS = 128
        private const val KEY_SIZE_BITS = 256
    }
}
