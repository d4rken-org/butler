package eu.darken.butler.common.files.smb.credentials

import io.kotest.assertions.throwables.shouldThrow
import io.kotest.matchers.types.shouldBeInstanceOf
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import testhelpers.BaseTest
import java.security.InvalidKeyException
import java.security.Key
import java.security.KeyStoreSpi
import java.security.Provider
import java.security.Security
import java.security.cert.Certificate
import java.util.Date
import java.util.Enumeration
import java.util.Collections
import javax.crypto.spec.SecretKeySpec
import kotlin.uuid.Uuid

/**
 * A keystore key can survive as an object and still be rejected when it is used: an invalidated key
 * is handed out and only throws at `Cipher.init`. Both directions have to report that as a
 * credential that cannot be produced, not as a raw crypto failure.
 */
class KeystoreSmbCredentialCipherTest : BaseTest() {

    private val locationId = Uuid.parse("11111111-2222-3333-4444-555555555555")

    @BeforeEach
    fun installUnusableKeystore() {
        Security.addProvider(UnusableKeyProvider())
    }

    @AfterEach
    fun removeUnusableKeystore() {
        Security.removeProvider(UnusableKeyProvider.NAME)
    }

    @Test
    fun `a key that only fails on use is reported as unavailable while encrypting`() {
        val cipher = KeystoreSmbCredentialCipher()

        val error = shouldThrow<SmbCredentialUnavailableException> {
            cipher.encrypt(locationId, 1, "payload".encodeToByteArray())
        }

        error.cause.shouldBeInstanceOf<InvalidKeyException>()
    }

    @Test
    fun `a key that only fails on use is reported as unavailable while decrypting`() {
        val cipher = KeystoreSmbCredentialCipher()

        val error = shouldThrow<SmbCredentialUnavailableException> {
            cipher.decrypt(
                locationId = locationId,
                payloadVersion = 1,
                envelope = SmbCredentialCipher.Envelope(
                    envelopeVersion = SmbCredentialCipher.ENVELOPE_VERSION,
                    keyAlias = "butler_smb_credentials",
                    iv = ByteArray(12),
                    ciphertext = ByteArray(32),
                ),
            )
        }

        error.cause.shouldBeInstanceOf<InvalidKeyException>()
    }

    /** Stands in for AndroidKeyStore and hands out a key every AES cipher rejects. */
    class UnusableKeyProvider : Provider(NAME, 1.0, "Test keystore handing out an unusable key") {
        init {
            put("KeyStore.$NAME", UnusableKeyStoreSpi::class.java.name)
        }

        companion object {
            const val NAME = "AndroidKeyStore"
        }
    }

    class UnusableKeyStoreSpi : KeyStoreSpi() {
        override fun engineGetKey(alias: String?, password: CharArray?): Key =
            SecretKeySpec(ByteArray(3), "AES")

        override fun engineGetCertificateChain(alias: String?): Array<Certificate>? = null
        override fun engineGetCertificate(alias: String?): Certificate? = null
        override fun engineGetCreationDate(alias: String?): Date = Date(0)
        override fun engineSetKeyEntry(a: String?, k: Key?, p: CharArray?, c: Array<out Certificate>?) = Unit
        override fun engineSetKeyEntry(alias: String?, key: ByteArray?, chain: Array<out Certificate>?) = Unit
        override fun engineSetCertificateEntry(alias: String?, cert: Certificate?) = Unit
        override fun engineDeleteEntry(alias: String?) = Unit
        override fun engineAliases(): Enumeration<String> = Collections.enumeration(listOf(ALIAS))
        override fun engineContainsAlias(alias: String?): Boolean = true
        override fun engineSize(): Int = 1
        override fun engineIsKeyEntry(alias: String?): Boolean = true
        override fun engineIsCertificateEntry(alias: String?): Boolean = false
        override fun engineGetCertificateAlias(cert: Certificate?): String? = null
        override fun engineStore(stream: java.io.OutputStream?, password: CharArray?) = Unit
        override fun engineLoad(stream: java.io.InputStream?, password: CharArray?) = Unit

        companion object {
            private const val ALIAS = "butler_smb_credentials"
        }
    }
}
