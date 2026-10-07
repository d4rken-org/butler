package eu.darken.butler.common.files.sftp.location

import android.content.Context
import eu.darken.butler.common.files.sftp.testHostKey
import eu.darken.ssh.HostKey
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.matchers.shouldBe
import io.mockk.mockk
import org.junit.jupiter.api.Test
import testhelpers.BaseTest
import kotlin.time.Instant
import kotlin.uuid.Uuid

class SftpLocationTest : BaseTest() {

    private val context = mockk<Context>()

    private val location = SftpLocation(
        id = Uuid.parse("11111111-2222-3333-4444-555555555555"),
        label = null,
        host = "nas.local",
        username = "darken",
        authType = SftpLocation.AuthType.PASSWORD,
        rememberCredential = true,
        credentialVersion = 1,
        hostKey = testHostKey(1),
        trustRevision = 1,
        createdAt = Instant.fromEpochMilliseconds(0),
        updatedAt = Instant.fromEpochMilliseconds(0),
    )

    @Test
    fun `the endpoint label names user, host, a non-default port and the base path`() {
        location.endpointLabel shouldBe "darken@nas.local"
        location.copy(port = 2222).endpointLabel shouldBe "darken@nas.local:2222"
        location.copy(basePath = "/srv/media").endpointLabel shouldBe "darken@nas.local/srv/media"
        location.copy(port = 2222, basePath = "media").endpointLabel shouldBe "darken@nas.local:2222/media"
    }

    @Test
    fun `the display name falls back to the host`() {
        location.displayName.get(context) shouldBe "nas.local"
        location.copy(label = " ").displayName.get(context) shouldBe "nas.local"
        location.copy(label = "Home server").displayName.get(context) shouldBe "Home server"
    }

    @Test
    fun `a base path containing NUL is rejected`() {
        shouldThrow<IllegalArgumentException> { location.copy(basePath = "srv\u0000") }
    }

    @Test
    fun `a trusted host key converts to and from the SSH library's key`() {
        val trusted = testHostKey(7)

        val hostKey = trusted.toHostKey()

        hostKey.type shouldBe "ssh-ed25519"
        hostKey.blob.contentEquals(trusted.blob) shouldBe true
        hostKey.sha256Fingerprint shouldBe trusted.fingerprint
        TrustedHostKey.from(hostKey) shouldBe trusted
        trusted.fingerprint.startsWith("SHA256:") shouldBe true
    }

    @Test
    fun `trusted host keys compare by content`() {
        testHostKey(1) shouldBe testHostKey(1)
        (testHostKey(1) == testHostKey(2)) shouldBe false
    }

    @Test
    fun `a malformed stored key fails the conversion`() {
        val mangled = TrustedHostKey(type = "ssh-rsa", blob = testHostKey(1).blob, fingerprint = "SHA256:x")

        shouldThrow<IllegalArgumentException> { mangled.toHostKey() }
        HostKey("ssh-ed25519", mangled.blob).type shouldBe "ssh-ed25519"
    }
}
