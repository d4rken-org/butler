package eu.darken.butler.common.files.sftp

import androidx.test.ext.junit.runners.AndroidJUnit4
import eu.darken.butler.common.files.credentials.CredentialCipher
import eu.darken.butler.common.files.sftp.credentials.KeystoreSftpCredentialCipher
import eu.darken.butler.common.files.sftp.credentials.SftpCredentialUnavailableException
import eu.darken.butler.common.files.smb.credentials.KeystoreSmbCredentialCipher
import eu.darken.butler.common.files.smb.credentials.SmbCredentialCipher
import eu.darken.butler.common.files.smb.credentials.SmbCredentialUnavailableException
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.matchers.shouldBe
import io.kotest.matchers.shouldNotBe
import org.junit.Test
import org.junit.runner.RunWith
import kotlin.uuid.Uuid

/** The SFTP credential cipher against the device's real AndroidKeyStore. */
@RunWith(AndroidJUnit4::class)
class SftpCredentialCipherDeviceTest {

    private val sftp = KeystoreSftpCredentialCipher()
    private val smb = KeystoreSmbCredentialCipher()

    private val locationId = Uuid.random()
    private val plaintext = """{"username":"butler","password":"butlerpass"}""".encodeToByteArray()

    private fun SmbCredentialCipher.Envelope.toSftp() = CredentialCipher.Envelope(envelopeVersion, keyAlias, iv, ciphertext)

    private fun CredentialCipher.Envelope.toSmb() = SmbCredentialCipher.Envelope(envelopeVersion, keyAlias, iv, ciphertext)

    @Test
    fun roundTripThroughTheKeystore() {
        val envelope = sftp.encrypt(locationId, PAYLOAD_VERSION, plaintext)

        envelope.keyAlias shouldBe SFTP_ALIAS
        envelope.ciphertext.contentEquals(plaintext) shouldBe false
        sftp.isKeyAvailable(SFTP_ALIAS) shouldBe true
        sftp.decrypt(locationId, PAYLOAD_VERSION, envelope).contentEquals(plaintext) shouldBe true

        val again = sftp.encrypt(locationId, PAYLOAD_VERSION, plaintext)
        again.iv.contentEquals(envelope.iv) shouldBe false
        sftp.decrypt(locationId, PAYLOAD_VERSION, again).contentEquals(plaintext) shouldBe true
    }

    @Test
    fun anEnvelopeOnlyDecryptsForItsLocationAndPayloadVersion() {
        val envelope = sftp.encrypt(locationId, PAYLOAD_VERSION, plaintext)

        shouldThrow<SftpCredentialUnavailableException> { sftp.decrypt(Uuid.random(), PAYLOAD_VERSION, envelope) }
        shouldThrow<SftpCredentialUnavailableException> { sftp.decrypt(locationId, PAYLOAD_VERSION + 1, envelope) }
    }

    @Test
    fun smbAndSftpEnvelopesDoNotCrossDecrypt() {
        val smbEnvelope = smb.encrypt(locationId, PAYLOAD_VERSION, plaintext)
        val sftpEnvelope = sftp.encrypt(locationId, PAYLOAD_VERSION, plaintext)
        smbEnvelope.keyAlias shouldNotBe sftpEnvelope.keyAlias

        // As stored: each envelope names its own protocol's key.
        shouldThrow<SftpCredentialUnavailableException> {
            sftp.decrypt(locationId, PAYLOAD_VERSION, smbEnvelope.toSftp())
        }
        shouldThrow<SmbCredentialUnavailableException> {
            smb.decrypt(locationId, PAYLOAD_VERSION, sftpEnvelope.toSmb())
        }

        // Relabelled to the reader's own key alias.
        shouldThrow<SftpCredentialUnavailableException> {
            sftp.decrypt(locationId, PAYLOAD_VERSION, smbEnvelope.toSftp().copy(keyAlias = SFTP_ALIAS))
        }
        shouldThrow<SmbCredentialUnavailableException> {
            smb.decrypt(locationId, PAYLOAD_VERSION, sftpEnvelope.toSmb().copy(keyAlias = smbEnvelope.keyAlias))
        }

        smb.decrypt(locationId, PAYLOAD_VERSION, smbEnvelope).contentEquals(plaintext) shouldBe true
        sftp.decrypt(locationId, PAYLOAD_VERSION, sftpEnvelope).contentEquals(plaintext) shouldBe true
    }

    companion object {
        private const val PAYLOAD_VERSION = 1
        private const val SFTP_ALIAS = "butler_sftp_credentials"
    }
}
