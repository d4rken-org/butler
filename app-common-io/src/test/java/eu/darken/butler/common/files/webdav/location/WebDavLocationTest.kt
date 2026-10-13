package eu.darken.butler.common.files.webdav.location

import android.content.Context
import eu.darken.butler.common.files.webdav.testCertificate
import eu.darken.webdav.ServerCertificate
import eu.darken.webdav.TestCertificates
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.matchers.shouldBe
import io.mockk.mockk
import org.junit.jupiter.api.Test
import testhelpers.BaseTest
import kotlin.time.Instant
import kotlin.uuid.Uuid

class WebDavLocationTest : BaseTest() {

    private val context = mockk<Context>()

    private val location = WebDavLocation(
        id = Uuid.parse("11111111-2222-3333-4444-555555555555"),
        label = null,
        url = "https://cloud.example.com/remote.php/dav/files/alice/",
        authType = WebDavLocation.AuthType.BASIC,
        username = "alice",
        rememberCredential = true,
        credentialVersion = 1,
        pinnedCertificate = null,
        trustRevision = 1,
        createdAt = Instant.fromEpochMilliseconds(0),
        updatedAt = Instant.fromEpochMilliseconds(0),
    )

    private val anonymous = location.copy(
        authType = WebDavLocation.AuthType.ANONYMOUS,
        username = null,
        rememberCredential = false,
    )

    @Test
    fun `host, port and scheme come from the URL`() {
        location.host shouldBe "cloud.example.com"
        location.port shouldBe 443
        location.isSecure shouldBe true

        val plain = location.copy(url = "http://nas.local/dav/")
        plain.host shouldBe "nas.local"
        plain.port shouldBe 80
        plain.isSecure shouldBe false

        location.copy(url = "https://nas.local:8443/").port shouldBe 8443
    }

    @Test
    fun `the endpoint label names user, host, a non-default port and the path`() {
        location.endpointLabel shouldBe "alice@cloud.example.com/remote.php/dav/files/alice"
        location.copy(url = "https://cloud.example.com:8443/dav/").endpointLabel shouldBe
            "alice@cloud.example.com:8443/dav"
        location.copy(url = "http://nas.local/dav/").endpointLabel shouldBe "alice@nas.local/dav"
        location.copy(url = "http://nas.local:443/").endpointLabel shouldBe "alice@nas.local:443"
        anonymous.copy(url = "https://nas.local/").endpointLabel shouldBe "nas.local"
        anonymous.copy(url = "http://[::1]:8080/dav/").endpointLabel shouldBe "[::1]:8080/dav"
        anonymous.copy(url = "https://nas.local/My%20Files/").endpointLabel shouldBe "nas.local/My Files"
    }

    @Test
    fun `the display name falls back to the host`() {
        location.displayName.get(context) shouldBe "cloud.example.com"
        location.copy(label = " ").displayName.get(context) shouldBe "cloud.example.com"
        location.copy(label = "Nextcloud").displayName.get(context) shouldBe "Nextcloud"
    }

    @Test
    fun `only a normalized http or https URL is accepted`() {
        location.copy(url = "https://cloud.example.com/dav/").url shouldBe "https://cloud.example.com/dav/"

        shouldThrow<IllegalArgumentException> { location.copy(url = "https://cloud.example.com/dav") }
        shouldThrow<IllegalArgumentException> { location.copy(url = "HTTPS://cloud.example.com/dav/") }
        shouldThrow<IllegalArgumentException> { location.copy(url = "ftp://cloud.example.com/") }
        shouldThrow<IllegalArgumentException> { location.copy(url = "https://bob@cloud.example.com/") }
        shouldThrow<IllegalArgumentException> { location.copy(url = "https://cloud.example.com/?x=1") }
        shouldThrow<IllegalArgumentException> { location.copy(url = "not a url") }
    }

    @Test
    fun `a Basic location needs a usable username`() {
        shouldThrow<IllegalArgumentException> { location.copy(username = null) }
        shouldThrow<IllegalArgumentException> { location.copy(username = " ") }
        shouldThrow<IllegalArgumentException> { location.copy(username = "al:ice") }
    }

    @Test
    fun `an anonymous location has no username and nothing to remember`() {
        anonymous.username shouldBe null
        shouldThrow<IllegalArgumentException> { anonymous.copy(username = "alice") }
        shouldThrow<IllegalArgumentException> { anonymous.copy(rememberCredential = true) }
    }

    @Test
    fun `a plain HTTP location cannot pin a certificate`() {
        location.copy(pinnedCertificate = testCertificate(1)).pinnedCertificate shouldBe testCertificate(1)

        shouldThrow<IllegalArgumentException> {
            location.copy(url = "http://nas.local/dav/", pinnedCertificate = testCertificate(1))
        }
    }

    @Test
    fun `the location converts to the library's endpoint`() {
        val endpoint = location.toEndpoint()

        endpoint.url shouldBe location.url
        endpoint.host shouldBe "cloud.example.com"
        endpoint.port shouldBe 443
    }

    @Test
    fun `a trusted certificate converts to and from the library's certificate`() {
        val server = ServerCertificate(TestCertificates.leaf("cloud.example.com").certificate.encoded)

        val trusted = TrustedCertificate.from(server)

        trusted.fingerprint shouldBe server.sha256Fingerprint
        trusted.der.contentEquals(server.encoded) shouldBe true
        trusted.toServerCertificate() shouldBe server
        trusted.toString() shouldBe "TrustedCertificate(${server.sha256Fingerprint})"
    }

    @Test
    fun `trusted certificates compare by content`() {
        testCertificate(1) shouldBe testCertificate(1)
        (testCertificate(1) == testCertificate(2)) shouldBe false
        testCertificate(1).hashCode() shouldBe testCertificate(1).hashCode()
    }

    @Test
    fun `a malformed fingerprint or certificate is rejected`() {
        val valid = testCertificate(1)

        shouldThrow<IllegalArgumentException> { valid.copy(fingerprint = "SHA256:abc") }
        shouldThrow<IllegalArgumentException> { valid.copy(fingerprint = valid.fingerprint.lowercase()) }
        shouldThrow<IllegalArgumentException> { valid.copy(der = ByteArray(0)) }
        shouldThrow<IllegalArgumentException> { valid.toServerCertificate() }
    }
}
