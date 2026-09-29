package eu.darken.butler.common.files.credentials

import eu.darken.butler.common.files.sftp.credentials.KeystoreSftpCredentialCipher
import eu.darken.butler.common.files.sftp.credentials.SftpCredentialUnavailableException
import eu.darken.butler.common.files.smb.credentials.KeystoreSmbCredentialCipher
import eu.darken.butler.common.files.smb.credentials.SmbCredentialCipher
import eu.darken.butler.common.files.smb.credentials.SmbCredentialUnavailableException
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import testhelpers.BaseTest
import java.security.Key
import java.security.KeyStoreSpi
import java.security.Provider
import java.security.Security
import java.security.cert.Certificate
import java.util.Collections
import java.util.Date
import java.util.Enumeration
import javax.crypto.Cipher
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.SecretKeySpec
import kotlin.uuid.Uuid

/**
 * Real AES-GCM under a stand-in AndroidKeyStore that hands out the same key for every alias, the
 * worst case for keeping the protocols apart: only the AAD separates them.
 */
class KeystoreCredentialCipherTest : BaseTest() {

    private val locationId = Uuid.parse("11111111-2222-3333-4444-555555555555")
    private val plaintext = """{"username":"darken","password":"hunter2"}"""

    @BeforeEach
    fun installKeystore() {
        Security.removeProvider(FixedKeyProvider.NAME)
        Security.addProvider(FixedKeyProvider())
    }

    @AfterEach
    fun removeKeystore() {
        Security.removeProvider(FixedKeyProvider.NAME)
    }

    /**
     * Sealed outside the app: AES-256-GCM with key `00..1f`, IV `a0..ab` and the AAD every stored SMB
     * credential carries, `locationId.toByteArray() + payloadVersion.toByte()`, here
     * `11111111222233334444555555555555 01`.
     */
    @Test
    fun `an SMB credential sealed before the cipher was shared still decrypts`() {
        val envelope = SmbCredentialCipher.Envelope(
            envelopeVersion = 1,
            keyAlias = "butler_smb_credentials",
            iv = "a0a1a2a3a4a5a6a7a8a9aaab".hexToByteArray(),
            ciphertext = (
                "9d3a095e20b96cde0f00a5e9251ea1ac1bc93732be95320def7d51e90dcf573b" +
                    "f01e3291db47210f7de117834a2c651349a6fa5de827ab36b444"
                ).hexToByteArray(),
        )

        KeystoreSmbCredentialCipher().decrypt(locationId, 1, envelope).decodeToString() shouldBe plaintext
    }

    @Test
    fun `SMB still seals under its own alias and the bare AAD`() {
        val envelope = KeystoreSmbCredentialCipher().encrypt(locationId, 1, plaintext.encodeToByteArray())

        envelope.keyAlias shouldBe "butler_smb_credentials"
        envelope.envelopeVersion shouldBe 1
        open(envelope.iv, envelope.ciphertext, aad = locationId.toByteArray() + 1.toByte()) shouldBe plaintext
    }

    @Test
    fun `SFTP seals under its own alias and an sftp prefixed AAD`() {
        val envelope = KeystoreSftpCredentialCipher().encrypt(locationId, 1, plaintext.encodeToByteArray())

        envelope.keyAlias shouldBe "butler_sftp_credentials"
        open(
            envelope.iv,
            envelope.ciphertext,
            aad = "sftp".encodeToByteArray() + locationId.toByteArray() + 1.toByte(),
        ) shouldBe plaintext
        KeystoreSftpCredentialCipher().decrypt(locationId, 1, envelope).decodeToString() shouldBe plaintext
    }

    @Test
    fun `an SFTP credential never decrypts as an SMB one`() {
        val sftp = KeystoreSftpCredentialCipher().encrypt(locationId, 1, plaintext.encodeToByteArray())

        shouldThrow<SmbCredentialUnavailableException> {
            KeystoreSmbCredentialCipher().decrypt(
                locationId = locationId,
                payloadVersion = 1,
                envelope = SmbCredentialCipher.Envelope(sftp.envelopeVersion, sftp.keyAlias, sftp.iv, sftp.ciphertext),
            )
        }
    }

    @Test
    fun `an SMB credential never decrypts as an SFTP one`() {
        val smb = KeystoreSmbCredentialCipher().encrypt(locationId, 1, plaintext.encodeToByteArray())

        shouldThrow<SftpCredentialUnavailableException> {
            KeystoreSftpCredentialCipher().decrypt(
                locationId = locationId,
                payloadVersion = 1,
                envelope = CredentialCipher.Envelope(smb.envelopeVersion, smb.keyAlias, smb.iv, smb.ciphertext),
            )
        }
    }

    @Test
    fun `an SFTP credential does not decrypt for another payload version`() {
        val envelope = KeystoreSftpCredentialCipher().encrypt(locationId, 1, plaintext.encodeToByteArray())

        shouldThrow<SftpCredentialUnavailableException> {
            KeystoreSftpCredentialCipher().decrypt(locationId, 2, envelope)
        }
    }

    @Test
    fun `an unknown envelope version is refused`() {
        val envelope = KeystoreSftpCredentialCipher().encrypt(locationId, 1, plaintext.encodeToByteArray())

        shouldThrow<SftpCredentialUnavailableException> {
            KeystoreSftpCredentialCipher().decrypt(locationId, 1, envelope.copy(envelopeVersion = 2))
        }
    }

    private fun open(iv: ByteArray, ciphertext: ByteArray, aad: ByteArray): String =
        Cipher.getInstance("AES/GCM/NoPadding").run {
            init(Cipher.DECRYPT_MODE, SecretKeySpec(KEY, "AES"), GCMParameterSpec(128, iv))
            updateAAD(aad)
            doFinal(ciphertext).decodeToString()
        }

    class FixedKeyProvider : Provider(NAME, 1.0, "Test keystore handing out one AES key for every alias") {
        init {
            put("KeyStore.$NAME", FixedKeyStoreSpi::class.java.name)
        }

        companion object {
            const val NAME = "AndroidKeyStore"
        }
    }

    class FixedKeyStoreSpi : KeyStoreSpi() {
        override fun engineGetKey(alias: String?, password: CharArray?): Key = SecretKeySpec(KEY, "AES")
        override fun engineGetCertificateChain(alias: String?): Array<Certificate>? = null
        override fun engineGetCertificate(alias: String?): Certificate? = null
        override fun engineGetCreationDate(alias: String?): Date = Date(0)
        override fun engineSetKeyEntry(a: String?, k: Key?, p: CharArray?, c: Array<out Certificate>?) = Unit
        override fun engineSetKeyEntry(alias: String?, key: ByteArray?, chain: Array<out Certificate>?) = Unit
        override fun engineSetCertificateEntry(alias: String?, cert: Certificate?) = Unit
        override fun engineDeleteEntry(alias: String?) = Unit
        override fun engineAliases(): Enumeration<String> = Collections.emptyEnumeration()
        override fun engineContainsAlias(alias: String?): Boolean = true
        override fun engineSize(): Int = 0
        override fun engineIsKeyEntry(alias: String?): Boolean = true
        override fun engineIsCertificateEntry(alias: String?): Boolean = false
        override fun engineGetCertificateAlias(cert: Certificate?): String? = null
        override fun engineStore(stream: java.io.OutputStream?, password: CharArray?) = Unit
        override fun engineLoad(stream: java.io.InputStream?, password: CharArray?) = Unit
    }

    companion object {
        private val KEY = ByteArray(32) { it.toByte() }
    }
}
