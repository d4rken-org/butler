package eu.darken.butler.common.files.webdav.credentials

import eu.darken.butler.common.debug.logging.logTag
import eu.darken.butler.common.files.credentials.CredentialCipher
import eu.darken.butler.common.files.credentials.KeystoreCredentialCipher
import javax.inject.Inject
import javax.inject.Singleton

/** The cipher behind the WebDAV credential vault, failing with [WebDavCredentialUnavailableException]. */
interface WebDavCredentialCipher : CredentialCipher

/**
 * Own key alias and a `webdav` AAD prefix; KeystoreCredentialCipherTest pins that WebDAV ciphertexts
 * never authenticate as SMB or SFTP ones, even under the same key.
 */
@Singleton
class KeystoreWebDavCredentialCipher @Inject constructor() :
    WebDavCredentialCipher,
    CredentialCipher by KeystoreCredentialCipher(
        keyAlias = KEY_ALIAS,
        aadDomain = AAD_DOMAIN.encodeToByteArray(),
        tag = TAG,
        unavailable = ::WebDavCredentialUnavailableException,
    ) {

    companion object {
        private val TAG = logTag("WebDAV", "Credentials", "Cipher")
        private const val KEY_ALIAS = "butler_webdav_credentials"
        private const val AAD_DOMAIN = "webdav"
    }
}
