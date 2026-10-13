package eu.darken.butler.common.files.webdav.credentials

import io.kotest.assertions.throwables.shouldThrow
import io.kotest.matchers.shouldBe
import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.Json
import org.junit.Test
import testhelpers.BaseTest

/**
 * Pins the stored shape of a credential payload.
 *
 * The payload is serialized, encrypted and written to the vault, so every remembered secret on
 * every device is sitting in this exact shape. Renaming a field makes stored credentials
 * undecodable, which surfaces as every location asking to sign in again. A deliberate change needs
 * a new [WebDavCredentialPayload.VERSION] and a read path for the old one.
 */
class WebDavCredentialPayloadTest : BaseTest() {

    /** Same configuration the vault reads with, see WebDavCredentialStore. */
    private val json = Json { ignoreUnknownKeys = true }

    @Test
    fun `a password payload writes the stored shape`() {
        val payload = WebDavCredentialPayload(username = "alice", password = "hunter2")

        json.encodeToString(payload) shouldBe """{"username":"alice","password":"hunter2"}"""
    }

    @Test
    fun `a stored version 1 payload still decodes`() {
        val payload = json.decodeFromString<WebDavCredentialPayload>(
            """{"v":1,"username":"alice","password":"hunter2"}"""
        )

        payload.version shouldBe 1
        payload.username shouldBe "alice"
        payload.password shouldBe "hunter2"
    }

    @Test
    fun `a stored payload without a version decodes as version 1`() {
        json.decodeFromString<WebDavCredentialPayload>("""{"username":"alice","password":"hunter2"}""")
            .version shouldBe 1
    }

    @Test
    fun `a payload carrying an unknown field still decodes`() {
        val stored = """{"v":1,"username":"alice","password":"hunter2","somethingNewer":true}"""

        json.decodeFromString<WebDavCredentialPayload>(stored).username shouldBe "alice"
    }

    @Test
    fun `a payload without a password does not decode`() {
        shouldThrow<SerializationException> {
            json.decodeFromString<WebDavCredentialPayload>("""{"v":1,"username":"alice"}""")
        }
    }
}
