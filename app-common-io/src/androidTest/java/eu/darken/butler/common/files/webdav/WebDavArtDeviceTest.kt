package eu.darken.butler.common.files.webdav

import androidx.test.ext.junit.runners.AndroidJUnit4
import eu.darken.butler.common.files.webdav.location.TrustedCertificate
import eu.darken.butler.common.files.webdav.location.WebDavLocation
import eu.darken.webdav.CertificatePolicy
import eu.darken.webdav.FakeWebDavServer
import eu.darken.webdav.OkHttpWebDavConnector
import eu.darken.webdav.ServerCertificate
import eu.darken.webdav.TestCertificates
import eu.darken.webdav.WebDavCredentials
import eu.darken.webdav.WebDavEndpoint
import eu.darken.webdav.WebDavEntry
import eu.darken.webdav.WebDavException
import eu.darken.webdav.WebDavPath
import eu.darken.webdav.use
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.matchers.collections.shouldContainExactlyInAnyOrder
import io.kotest.matchers.shouldBe
import io.kotest.matchers.shouldNotBe
import io.kotest.matchers.types.shouldBeInstanceOf
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.runBlocking
import mockwebserver3.MockResponse
import okhttp3.tls.HeldCertificate
import org.junit.After
import org.junit.Test
import org.junit.runner.RunWith
import java.security.MessageDigest

/**
 * Runs lib-webdav's XML and TLS code on ART: Android's SAX parser is Expat-backed and its trust
 * manager is Conscrypt's, neither of which the host tests exercise. [FakeWebDavServer] runs
 * in-process, every server is HTTPS so the test APK's default cleartext policy does not matter.
 * `runBlocking`, not `runTest`: the connection timeouts are real time.
 */
@RunWith(AndroidJUnit4::class)
class WebDavArtDeviceTest {

    private val servers = mutableListOf<FakeWebDavServer>()

    @After
    fun tearDown() = servers.forEach { it.close() }

    private fun server(certificate: HeldCertificate, configure: FakeWebDavServer.() -> Unit = {}) =
        FakeWebDavServer(certificate = certificate).apply(configure).start().also { servers.add(it) }

    private val tester = WebDavConnectionTester(WebDavConnectorFactoryModule.connectorFactory())

    private suspend fun test(url: String, pin: TrustedCertificate? = null) = tester.test(
        url = url,
        authType = WebDavLocation.AuthType.BASIC,
        username = "butler",
        password = "butlerpass".toCharArray(),
        trustedCertificate = pin,
    )

    private fun credentials() = WebDavCredentials.Basic("butler", "butlerpass".toCharArray())

    private fun HeldCertificate.trusted() = TrustedCertificate.from(ServerCertificate(certificate.encoded))

    private fun HeldCertificate.policy() = CertificatePolicy.Pinned(ServerCertificate(certificate.encoded))

    /** Computed here rather than by the code under test, e.g. `3A:0F:…:9C`. */
    private fun HeldCertificate.fingerprint(): String =
        MessageDigest.getInstance("SHA-256").digest(certificate.encoded).joinToString(":") { "%02X".format(it) }

    @Test
    fun depthOneListingParsesWithThePlatformParser() = runBlocking<Unit> {
        val held = TestCertificates.leaf("localhost")
        val server = server(held) {
            putFile("a b.txt", ByteArray(3))
            putFile("100% <&> x+y=z.bin", ByteArray(5))
            putFile("Übersicht – 日本.md", ByteArray(7))
            putFile("photos/2024/x.jpg", ByteArray(1))
            mkdirs("empty")
        }
        val connector = OkHttpWebDavConnector()

        for (style in FakeWebDavServer.HrefStyle.entries) {
            server.hrefStyle = style
            val entries = connector
                .connect(WebDavEndpoint(server.baseUrl), credentials(), held.policy())
                .use { it.list(WebDavPath.Root).toList() }

            entries.map { it.summary() } shouldContainExactlyInAnyOrder listOf(
                Summary("a b.txt", false, 3),
                Summary("100% <&> x+y=z.bin", false, 5),
                Summary("Übersicht – 日本.md", false, 7),
                Summary("photos", true, null),
                Summary("empty", true, null),
            )
            entries.filterNot { it.isCollection }.forEach {
                it.modifiedAt shouldNotBe null
                it.etag shouldNotBe null
            }
        }
    }

    @Test
    fun multistatusWithDoctypeIsRejected() = runBlocking<Unit> {
        val held = TestCertificates.leaf("localhost")
        val server = server(held)
        fun multistatus(name: String) =
            """<d:multistatus xmlns:d="DAV:"><d:response><d:href>${server.basePath}$name</d:href>""" +
                """<d:propstat><d:prop><d:resourcetype/></d:prop><d:status>HTTP/1.1 200 OK</d:status></d:propstat>""" +
                """</d:response></d:multistatus>"""

        fun answerListingWith(body: String) {
            server.responseTransformer = { request, response ->
                if (request.method == "PROPFIND" && request.headers["Depth"] == "1") {
                    MockResponse.Builder().code(207).addHeader("Content-Type", "application/xml").body(body).build()
                } else {
                    response
                }
            }
        }

        OkHttpWebDavConnector()
            .connect(WebDavEndpoint(server.baseUrl), credentials(), held.policy())
            .use { session ->
                // The same document without a DOCTYPE parses, so only the DOCTYPE is what gets refused.
                answerListingWith("""<?xml version="1.0"?>""" + multistatus("x"))
                session.list(WebDavPath.Root).toList().map { it.path.name } shouldBe listOf("x")

                val doctypes = listOf(
                    """<!DOCTYPE m [<!ENTITY x SYSTEM "file:///proc/version">]>""" + multistatus("x&x;"),
                    """<!DOCTYPE m [<!ENTITY a "aaaaaaaaaa"><!ENTITY b "&a;&a;&a;&a;&a;&a;&a;&a;">]>""" +
                        multistatus("&b;"),
                    """<!DOCTYPE m>""" + multistatus("x"),
                )
                for (doctype in doctypes) {
                    answerListingWith("""<?xml version="1.0"?>""" + doctype)
                    val error = shouldThrow<WebDavException> { session.list(WebDavPath.Root).toList() }
                    error.kind shouldBe WebDavException.Kind.PROTOCOL
                }
            }
    }

    @Test
    fun untrustedSelfSignedServerReportsItsLeaf() = runBlocking<Unit> {
        val held = TestCertificates.leaf("localhost")
        val server = server(held)

        val result = test(server.baseUrl).shouldBeInstanceOf<WebDavConnectionTester.Result.CertificateUntrusted>()
        result.presented.sha256Fingerprint shouldBe held.fingerprint()
        result.presented.der.contentEquals(held.certificate.encoded) shouldBe true
        server.requestCount shouldBe 0
    }

    @Test
    fun pinnedLeafConnectsDespiteHostNameMismatch() = runBlocking<Unit> {
        val held = TestCertificates.leaf("other.example")
        val server = server(held)
        // A host name rather than an IP: without SNI, MockWebServer crashes on Android 11 when it records the request.
        val url = server.baseUrl

        test(url).shouldBeInstanceOf<WebDavConnectionTester.Result.CertificateUntrusted>()
        test(url, pin = held.trusted()) shouldBe WebDavConnectionTester.Result.Success
    }

    @Test
    fun differentLeafUnderPinIsMismatch() = runBlocking<Unit> {
        val pinned = TestCertificates.leaf("localhost")
        val presented = TestCertificates.leaf("localhost")
        val server = server(presented)

        val result = test(server.baseUrl, pin = pinned.trusted())
            .shouldBeInstanceOf<WebDavConnectionTester.Result.CertificateMismatch>()
        result.expected.sha256Fingerprint shouldBe pinned.fingerprint()
        result.presented.sha256Fingerprint shouldBe presented.fingerprint()
        server.requestCount shouldBe 0
    }

    @Test
    fun concurrentConnectionsReportTheirOwnLeaf() = runBlocking<Unit> {
        val heldA = TestCertificates.leaf("localhost")
        val heldB = TestCertificates.leaf("localhost")
        val serverA = server(heldA)
        val serverB = server(heldB)

        val results = (1..ROUNDS).flatMap {
            listOf(serverA to heldA, serverB to heldB).map { (server, held) ->
                async(Dispatchers.IO) { held to test(server.baseUrl) }
            }
        }.awaitAll()

        results.size shouldBe ROUNDS * 2
        results.forEach { (held, result) ->
            result.shouldBeInstanceOf<WebDavConnectionTester.Result.CertificateUntrusted>()
                .presented.sha256Fingerprint shouldBe held.fingerprint()
        }
    }

    private data class Summary(val name: String, val isCollection: Boolean, val size: Long?)

    private fun WebDavEntry.summary() = Summary(path.name!!, isCollection, size.takeUnless { isCollection })

    companion object {
        private const val ROUNDS = 8
    }
}
