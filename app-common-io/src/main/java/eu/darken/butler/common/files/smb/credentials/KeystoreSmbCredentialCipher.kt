package eu.darken.butler.common.files.smb.credentials

import eu.darken.butler.common.debug.logging.logTag
import eu.darken.butler.common.files.credentials.CredentialCipher
import eu.darken.butler.common.files.credentials.KeystoreCredentialCipher
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.uuid.Uuid

/**
 * The SMB instance of [KeystoreCredentialCipher]. Its AAD carries no domain prefix, the layout every
 * stored SMB credential was sealed with, pinned by KeystoreCredentialCipherTest.
 */
@Singleton
class KeystoreSmbCredentialCipher @Inject constructor() : SmbCredentialCipher {

    private val delegate = KeystoreCredentialCipher(
        keyAlias = KEY_ALIAS,
        aadDomain = ByteArray(0),
        tag = TAG,
        unavailable = ::SmbCredentialUnavailableException,
    )

    override fun encrypt(locationId: Uuid, payloadVersion: Int, plaintext: ByteArray): SmbCredentialCipher.Envelope =
        delegate.encrypt(locationId, payloadVersion, plaintext).let {
            SmbCredentialCipher.Envelope(
                envelopeVersion = it.envelopeVersion,
                keyAlias = it.keyAlias,
                iv = it.iv,
                ciphertext = it.ciphertext,
            )
        }

    override fun decrypt(
        locationId: Uuid,
        payloadVersion: Int,
        envelope: SmbCredentialCipher.Envelope,
    ): ByteArray = delegate.decrypt(
        locationId = locationId,
        payloadVersion = payloadVersion,
        envelope = CredentialCipher.Envelope(
            envelopeVersion = envelope.envelopeVersion,
            keyAlias = envelope.keyAlias,
            iv = envelope.iv,
            ciphertext = envelope.ciphertext,
        ),
    )

    override fun isKeyAvailable(keyAlias: String): Boolean = delegate.isKeyAvailable(keyAlias)

    companion object {
        private val TAG = logTag("SMB", "Credentials", "Cipher")
        private const val KEY_ALIAS = "butler_smb_credentials"
    }
}
