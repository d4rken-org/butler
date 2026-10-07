package eu.darken.butler.common.files.sftp.credentials

import eu.darken.butler.common.debug.logging.logTag
import eu.darken.butler.common.files.credentials.CredentialCipher
import eu.darken.butler.common.files.credentials.KeystoreCredentialCipher
import javax.inject.Inject
import javax.inject.Singleton

/** The cipher behind the SFTP credential vault, failing with [SftpCredentialUnavailableException]. */
interface SftpCredentialCipher : CredentialCipher

/**
 * Own key alias and an `sftp` AAD prefix; KeystoreCredentialCipherTest pins that SFTP and SMB
 * ciphertexts never authenticate as each other, even under the same key.
 */
@Singleton
class KeystoreSftpCredentialCipher @Inject constructor() :
    SftpCredentialCipher,
    CredentialCipher by KeystoreCredentialCipher(
        keyAlias = KEY_ALIAS,
        aadDomain = AAD_DOMAIN.encodeToByteArray(),
        tag = TAG,
        unavailable = ::SftpCredentialUnavailableException,
    ) {

    companion object {
        private val TAG = logTag("SFTP", "Credentials", "Cipher")
        private const val KEY_ALIAS = "butler_sftp_credentials"
        private const val AAD_DOMAIN = "sftp"
    }
}
