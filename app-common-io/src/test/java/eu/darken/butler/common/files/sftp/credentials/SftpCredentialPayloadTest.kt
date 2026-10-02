package eu.darken.butler.common.files.sftp.credentials

import io.kotest.matchers.shouldBe
import kotlinx.serialization.json.Json
import org.junit.Test
import testhelpers.BaseTest

/**
 * Pins the stored shape of a credential payload.
 *
 * The payload is serialized, encrypted and written to the vault, so every remembered secret on
 * every device is sitting in this exact shape. Renaming a field makes stored credentials
 * undecodable, which surfaces as every location asking to sign in again. A deliberate change needs
 * a new [SftpCredentialPayload.VERSION] and a read path for the old one.
 */
class SftpCredentialPayloadTest : BaseTest() {

    /** Same configuration the vault reads with, see SftpCredentialStore. */
    private val json = Json { ignoreUnknownKeys = true }

    @Test
    fun `a password payload writes the stored shape`() {
        val payload = SftpCredentialPayload(username = "darken", password = "hunter2")

        json.encodeToString(payload) shouldBe """{"username":"darken","password":"hunter2"}"""
    }

    @Test
    fun `a key payload writes the stored shape`() {
        val payload = SftpCredentialPayload(username = "darken", privateKey = "LS0tLS1CRUdJTg==", passphrase = "pp")

        json.encodeToString(payload) shouldBe """{"username":"darken","privateKey":"LS0tLS1CRUdJTg==","passphrase":"pp"}"""
    }

    @Test
    fun `a stored version 1 password payload still decodes`() {
        val payload = json.decodeFromString<SftpCredentialPayload>(
            """{"v":1,"username":"darken","password":"hunter2"}"""
        )

        payload.version shouldBe 1
        payload.username shouldBe "darken"
        payload.password shouldBe "hunter2"
        payload.privateKey shouldBe null
        payload.passphrase shouldBe null
    }

    @Test
    fun `a stored version 1 key payload still decodes`() {
        val payload = json.decodeFromString<SftpCredentialPayload>(
            """{"v":1,"username":"darken","privateKey":"LS0tLS1CRUdJTg==","passphrase":"pp"}"""
        )

        payload.password shouldBe null
        payload.privateKey shouldBe "LS0tLS1CRUdJTg=="
        payload.passphrase shouldBe "pp"
    }

    @Test
    fun `a stored payload without a version decodes as version 1`() {
        json.decodeFromString<SftpCredentialPayload>("""{"username":"darken","password":"hunter2"}""")
            .version shouldBe 1
    }

    @Test
    fun `a payload carrying an unknown field still decodes`() {
        val stored = """{"v":1,"username":"darken","password":"hunter2","somethingNewer":true}"""

        json.decodeFromString<SftpCredentialPayload>(stored).username shouldBe "darken"
    }
}
