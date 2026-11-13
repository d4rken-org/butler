package eu.darken.butler.common.files.smb.location

import android.content.Context
import io.kotest.matchers.shouldBe
import io.mockk.mockk
import org.junit.jupiter.api.Test
import testhelpers.BaseTest
import kotlin.time.Instant
import kotlin.uuid.Uuid

class SmbLocationTest : BaseTest() {

    private val context = mockk<Context>()

    private val server = SmbLocation(
        id = Uuid.parse("11111111-2222-3333-4444-555555555555"),
        label = null,
        host = "nas.local",
        share = null,
        authType = SmbLocation.AuthType.GUEST,
        rememberCredential = false,
        credentialVersion = 1,
        createdAt = Instant.fromEpochMilliseconds(0),
        updatedAt = Instant.fromEpochMilliseconds(0),
    )

    @Test
    fun `a server entry's endpoint label is the host and a non-default port`() {
        server.endpointLabel shouldBe "nas.local"
        server.copy(port = 4450).endpointLabel shouldBe "nas.local:4450"
    }

    @Test
    fun `a share location's endpoint label names the share and the base path`() {
        val share = server.copy(share = "media", basePath = listOf("movies", "2024"))
        share.endpointLabel shouldBe "nas.local/media/movies/2024"
        share.copy(port = 4450).endpointLabel shouldBe "nas.local:4450/media/movies/2024"
    }

    @Test
    fun `a server entry's display name falls back to the host`() {
        server.isServer shouldBe true
        server.displayName.get(context) shouldBe "nas.local"
        server.copy(label = " ").displayName.get(context) shouldBe "nas.local"
        server.copy(label = "Home NAS").displayName.get(context) shouldBe "Home NAS"
    }

    @Test
    fun `a share location's display name falls back to the share`() {
        val share = server.copy(share = "media")
        share.isServer shouldBe false
        share.displayName.get(context) shouldBe "media"
        share.copy(label = "Home NAS").displayName.get(context) shouldBe "Home NAS"
    }
}
