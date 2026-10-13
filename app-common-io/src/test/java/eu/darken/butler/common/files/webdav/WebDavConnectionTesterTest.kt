package eu.darken.butler.common.files.webdav

import eu.darken.butler.common.files.smb.FakeUpgradeRepo
import eu.darken.butler.common.files.webdav.credentials.WebDavCredentialStore
import eu.darken.butler.common.files.webdav.location.TrustedCertificate
import eu.darken.butler.common.files.webdav.location.WebDavLocation
import eu.darken.webdav.FakeWebDavServer
import eu.darken.webdav.OkHttpWebDavConnector
import eu.darken.webdav.ServerCertificate
import eu.darken.webdav.TestCertificates
import eu.darken.webdav.WebDavConnector
import eu.darken.webdav.WebDavSession
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.matchers.shouldBe
import io.kotest.matchers.types.shouldBeInstanceOf
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.TestScope
import mockwebserver3.MockResponse
import okhttp3.tls.HeldCertificate
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.Timeout
import testhelpers.BaseTest
import java.util.concurrent.atomic.AtomicInteger
import kotlin.uuid.Uuid

/**
 * Runs against [FakeWebDavServer] with the real connector, whose system trust is the JVM's store: a
 * [TestCertificates.leaf] is never trusted by it.
 */
@Timeout(60)
class WebDavConnectionTesterTest : BaseTest() {

    private val servers = mutableListOf<FakeWebDavServer>()

    @AfterEach
    fun tearDown() = servers.forEach { it.close() }

    private fun server(
        certificate: HeldCertificate? = null,
        username: String? = "butler",
        configure: FakeWebDavServer.() -> Unit = {},
    ) = FakeWebDavServer(username = username, certificate = certificate).apply(configure).start().also { servers.add(it) }

    private val connects = AtomicInteger()
    private val disconnects = AtomicInteger()

    private val connectorFactory = WebDavConnectorFactory {
        val real = OkHttpWebDavConnector()
        WebDavConnector { endpoint, credentials, policy ->
            connects.incrementAndGet()
            val session = real.connect(endpoint, credentials, policy)
            object : WebDavSession by session {
                override fun disconnect() {
                    disconnects.incrementAndGet()
                    session.disconnect()
                }
            }
        }
    }

    private val tester = WebDavConnectionTester(connectorFactory)

    private suspend fun test(
        url: String,
        authType: WebDavLocation.AuthType = WebDavLocation.AuthType.BASIC,
        username: String? = "butler",
        password: CharArray? = "butlerpass".toCharArray(),
        pin: TrustedCertificate? = null,
    ) = tester.test(
        url = url,
        authType = authType,
        username = username,
        password = password,
        trustedCertificate = pin,
    )

    private fun HeldCertificate.trusted() = TrustedCertificate.from(ServerCertificate(certificate.encoded))

    @Test
    fun `a reachable collection succeeds and is disconnected again`(): Unit = runBlocking {
        val server = server()

        test(server.baseUrl) shouldBe WebDavConnectionTester.Result.Success
        connects.get() shouldBe 1
        disconnects.get() shouldBe 1
    }

    @Test
    fun `the caller keeps its password`(): Unit = runBlocking {
        val server = server()
        val password = "butlerpass".toCharArray()

        test(server.baseUrl, password = password) shouldBe WebDavConnectionTester.Result.Success
        password.concatToString() shouldBe "butlerpass"
    }

    @Test
    fun `a self-signed server is untrusted until its certificate is pinned`(): Unit = runBlocking {
        val held = TestCertificates.leaf("localhost")
        val server = server(certificate = held)

        val untrusted = test(server.baseUrl).shouldBeInstanceOf<WebDavConnectionTester.Result.CertificateUntrusted>()
        untrusted.presented.toTrustedCertificate() shouldBe held.trusted()
        untrusted.presented.sha256Fingerprint shouldBe ServerCertificate(held.certificate.encoded).sha256Fingerprint
        server.requestCount shouldBe 0

        test(server.baseUrl, pin = untrusted.presented.toTrustedCertificate()) shouldBe
            WebDavConnectionTester.Result.Success
    }

    @Test
    fun `a changed certificate under a pin reports both`(): Unit = runBlocking {
        val held = TestCertificates.leaf("localhost")
        val pinned = TestCertificates.leaf("localhost")
        val server = server(certificate = held)

        val mismatch = test(server.baseUrl, pin = pinned.trusted())
            .shouldBeInstanceOf<WebDavConnectionTester.Result.CertificateMismatch>()
        mismatch.expected.sha256Fingerprint shouldBe pinned.trusted().fingerprint
        mismatch.presented.sha256Fingerprint shouldBe held.trusted().fingerprint
        server.requestCount shouldBe 0
    }

    @Test
    fun `a wrong password fails the sign-in`(): Unit = runBlocking {
        val server = server()

        test(server.baseUrl, password = "wrong".toCharArray()) shouldBe
            WebDavConnectionTester.Result.AuthenticationFailed
        test(server.baseUrl, authType = WebDavLocation.AuthType.ANONYMOUS, username = null, password = null) shouldBe
            WebDavConnectionTester.Result.AuthenticationFailed
    }

    @Test
    fun `a forbidden collection is access denied`(): Unit = runBlocking {
        val server = server {
            responseTransformer = { _, _ -> MockResponse.Builder().code(403).build() }
        }

        test(server.baseUrl) shouldBe WebDavConnectionTester.Result.AccessDenied
    }

    @Test
    fun `a URL naming a file is not a collection`(): Unit = runBlocking {
        val server = server { putFile("notes.txt", "hello".encodeToByteArray()) }

        test(server.baseUrl + "notes.txt") shouldBe WebDavConnectionTester.Result.NotACollection
    }

    @Test
    fun `a server that refuses PROPFIND is not a WebDAV collection`(): Unit = runBlocking {
        val server = server {
            responseTransformer = { request, response ->
                if (request.method == "PROPFIND") MockResponse.Builder().code(405).build() else response
            }
        }

        test(server.baseUrl) shouldBe WebDavConnectionTester.Result.NotACollection
    }

    @Test
    fun `a missing base URL is reported`(): Unit = runBlocking {
        val server = server()

        test(server.baseUrl + "gone/") shouldBe WebDavConnectionTester.Result.Missing
    }

    @Test
    fun `a refused connection is unreachable`(): Unit = runBlocking {
        val closed = server().also { it.close() }

        test(closed.baseUrl).shouldBeInstanceOf<WebDavConnectionTester.Result.Unreachable>()
        disconnects.get() shouldBe 0
    }

    @Test
    fun `an unexpected response fails`(): Unit = runBlocking {
        val server = server {
            responseTransformer = { _, _ -> MockResponse.Builder().code(207).body("<not-xml").build() }
        }

        test(server.baseUrl).shouldBeInstanceOf<WebDavConnectionTester.Result.Failed>()
    }

    @Test
    fun `anonymous access over plain HTTP works`(): Unit = runBlocking {
        val server = server(username = null)
        server.baseUrl.startsWith("http://") shouldBe true

        test(server.baseUrl, authType = WebDavLocation.AuthType.ANONYMOUS, username = null, password = null) shouldBe
            WebDavConnectionTester.Result.Success
        server.takeRequest().headers["Authorization"] shouldBe null
    }

    @Test
    fun `a free user can test a server the pool refuses to open`(): Unit = runBlocking {
        val server = server()
        val location = testWebDavLocation(id = Uuid.random(), url = server.baseUrl, username = "butler")
        val pool = WebDavClientPool(
            appScope = TestScope(),
            locationManager = FakeWebDavLocationManager(listOf(location)),
            credentialStore = mockk<WebDavCredentialStore>(relaxed = true) {
                every { evictions } returns MutableSharedFlow()
            },
            connectorFactory = connectorFactory,
            upgradeRepo = FakeUpgradeRepo(pro = false),
        )

        shouldThrow<WebDavProRequiredException> { pool.acquire(location.id) }
        server.requestCount shouldBe 0

        test(server.baseUrl) shouldBe WebDavConnectionTester.Result.Success
        server.requestCount shouldBe 1
    }
}
