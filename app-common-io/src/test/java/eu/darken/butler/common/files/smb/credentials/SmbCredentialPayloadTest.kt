package eu.darken.butler.common.files.smb.credentials

import io.kotest.matchers.shouldBe
import kotlinx.serialization.json.Json
import org.junit.Test
import testhelpers.BaseTest

/**
 * Pins the stored shape of a credential payload.
 *
 * The payload is serialized, encrypted and written to the vault, so every remembered password on
 * every device is sitting in this exact shape. Renaming a field here does not fail a build or a
 * round trip; it makes stored credentials undecodable, which surfaces as every location asking to
 * sign in again and reads like a credential bug rather than a format break. A deliberate change
 * needs a new [SmbCredentialPayload.VERSION] and a read path for the old one, since the version a
 * row was written with is stored alongside it.
 */
class SmbCredentialPayloadTest : BaseTest() {

    /** Same configuration the vault reads with, see SmbCredentialStore. */
    private val json = Json { ignoreUnknownKeys = true }

    @Test
    fun `a stored version 1 payload still decodes`() {
        val stored = """{"v":1,"username":"darken","domain":"WORKGROUP","password":"hunter2"}"""

        val payload = json.decodeFromString<SmbCredentialPayload>(stored)

        payload.version shouldBe 1
        payload.username shouldBe "darken"
        payload.domain shouldBe "WORKGROUP"
        payload.password shouldBe "hunter2"
    }

    @Test
    fun `a stored version 1 payload without a domain still decodes`() {
        val stored = """{"v":1,"username":"darken","password":"hunter2"}"""

        val payload = json.decodeFromString<SmbCredentialPayload>(stored)

        payload.domain shouldBe null
        payload.username shouldBe "darken"
        payload.password shouldBe "hunter2"
    }

    /**
     * Note what is NOT written: the vault's Json leaves `encodeDefaults` off, and `version` has a
     * default, so no `v` field reaches the ciphertext. Stored payloads are version-less, and the
     * authoritative version is the row's own `payloadVersion` column, which the cipher also binds
     * into the AAD. [SmbCredentialPayload.VERSION] only ever surfaces on the decode side, where a
     * missing `v` falls back to it.
     */
    @Test
    fun `today's payload writes the stored shape, without a version marker`() {
        val payload = SmbCredentialPayload(
            username = "darken",
            domain = "WORKGROUP",
            password = "hunter2",
        )

        json.encodeToString(payload) shouldBe
            """{"username":"darken","domain":"WORKGROUP","password":"hunter2"}"""
    }

    /** What the vault actually holds today: no `v`, so the decode default is what supplies it. */
    @Test
    fun `a stored payload without a version decodes as version 1`() {
        val stored = """{"username":"darken","domain":"WORKGROUP","password":"hunter2"}"""

        json.decodeFromString<SmbCredentialPayload>(stored).version shouldBe 1
    }

    /**
     * A field added by a newer build has to be survivable, or installing an older build once would
     * cost the user every remembered password.
     */
    @Test
    fun `a payload carrying an unknown field still decodes`() {
        val stored = """{"v":1,"username":"darken","password":"hunter2","somethingNewer":true}"""

        json.decodeFromString<SmbCredentialPayload>(stored).username shouldBe "darken"
    }
}
