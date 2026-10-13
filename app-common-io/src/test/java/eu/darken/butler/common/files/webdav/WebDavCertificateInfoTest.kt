package eu.darken.butler.common.files.webdav

import eu.darken.butler.common.files.webdav.location.TrustedCertificate
import eu.darken.webdav.ServerCertificate
import eu.darken.webdav.TestCertificates
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.matchers.shouldBe
import io.kotest.matchers.shouldNotBe
import io.kotest.matchers.string.shouldContain
import org.junit.jupiter.api.Test
import testhelpers.BaseTest
import kotlin.time.Instant

class WebDavCertificateInfoTest : BaseTest() {

    private val held = TestCertificates.leaf("cloud.example.com")
    private val server = ServerCertificate(held.certificate.encoded)

    @Test
    fun `a presented certificate keeps its identity and validity`() {
        val info = WebDavCertificateInfo.from(server)

        info.der shouldBe held.certificate.encoded
        info.sha256Fingerprint shouldBe server.sha256Fingerprint
        info.subject shouldContain "CN=cloud.example.com"
        info.issuer shouldBe info.subject
        info.notBefore shouldBe Instant.fromEpochMilliseconds(held.certificate.notBefore.time)
        info.notAfter shouldBe Instant.fromEpochMilliseconds(held.certificate.notAfter.time)
    }

    @Test
    fun `a stored pin reads back as the certificate it was made from`() {
        val pin = TrustedCertificate.from(server)

        WebDavCertificateInfo.from(pin) shouldBe WebDavCertificateInfo.from(server)
        WebDavCertificateInfo.from(pin).toTrustedCertificate() shouldBe pin
    }

    @Test
    fun `the encoded certificate cannot be changed from outside`() {
        val info = WebDavCertificateInfo.from(server)

        info.der[0] = (info.der[0] + 1).toByte()

        info.der shouldBe held.certificate.encoded
    }

    @Test
    fun `two certificates are told apart`() {
        val other = ServerCertificate(TestCertificates.leaf("cloud.example.com").certificate.encoded)

        WebDavCertificateInfo.from(other) shouldNotBe WebDavCertificateInfo.from(server)
    }

    @Test
    fun `a pin that is no certificate is refused`() {
        shouldThrow<IllegalArgumentException> { WebDavCertificateInfo.from(testCertificate(1)) }
    }
}
