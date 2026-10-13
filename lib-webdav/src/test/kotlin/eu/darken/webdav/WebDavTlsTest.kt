package eu.darken.webdav

import eu.darken.webdav.WebDavException.Kind
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.runBlocking
import okhttp3.tls.HeldCertificate
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test

class WebDavTlsTest {
    private val servers = mutableListOf<FakeWebDavServer>()

    @AfterEach
    fun tearDown() = servers.forEach { it.close() }

    private fun serve(certificate: HeldCertificate) = FakeWebDavServer(certificate = certificate).start().also { servers += it }

    private val authority = TestCertificates.authority()

    /** A connector whose "system" store trusts only [authority]. */
    private val connector = OkHttpWebDavConnector(WebDavConfig()) { TestCertificates.trusting(authority) }

    private suspend fun connect(server: FakeWebDavServer, policy: CertificatePolicy) =
        connector.connect(WebDavEndpoint(server.baseUrl), basic(), policy)

    private fun HeldCertificate.server() = ServerCertificate(certificate.encoded)

    @Test
    fun `System policy rejects a self-signed server and carries its leaf`() {
        val held = TestCertificates.leaf("localhost")
        val server = serve(held)
        val error = assertKind(Kind.CERTIFICATE_UNTRUSTED) { connect(server, CertificatePolicy.System) }
        assertEquals(held.server(), error.presentedCertificate)
        assertEquals(held.server().sha256Fingerprint, error.presentedCertificate?.sha256Fingerprint)
        assertEquals(0, server.requestCount)
    }

    @Test
    fun `the platform store rejects a self-signed server too`() {
        val held = TestCertificates.leaf("localhost")
        val server = serve(held)
        val error =
            assertKind(Kind.CERTIFICATE_UNTRUSTED) {
                OkHttpWebDavConnector().connect(WebDavEndpoint(server.baseUrl), basic(), CertificatePolicy.System)
            }
        assertEquals(held.server(), error.presentedCertificate)
    }

    @Test
    fun `System policy accepts a trusted chain for the right host`(): Unit = runBlocking {
        val server = serve(TestCertificates.leaf("localhost", issuer = authority))
        connect(server, CertificatePolicy.System).use { assertTrue(it.stat(WebDavPath.Root).isCollection) }
    }

    @Test
    fun `a trusted chain for another host name is untrusted and carries its leaf`() {
        val held = TestCertificates.leaf("other.example", issuer = authority)
        val server = serve(held)
        val error = assertKind(Kind.CERTIFICATE_UNTRUSTED) { connect(server, CertificatePolicy.System) }
        assertEquals(held.server(), error.presentedCertificate)
        assertEquals(0, server.requestCount)
    }

    @Test
    fun `a pinned leaf passes even with a host name mismatch`(): Unit = runBlocking {
        val held = TestCertificates.leaf("other.example")
        val server = serve(held)
        connect(server, CertificatePolicy.Pinned(held.server())).use {
            it.writeAll(path("file"), byteArrayOf(1, 2, 3))
            assertArrayEquals(byteArrayOf(1, 2, 3), it.readAll(path("file")))
        }
    }

    @Test
    fun `a pin does not block a system-trusted replacement certificate`(): Unit = runBlocking {
        val server = serve(TestCertificates.leaf("localhost", issuer = authority))
        connect(server, CertificatePolicy.Pinned(TestCertificates.leaf("localhost").server())).use { it.stat(WebDavPath.Root) }
    }

    @Test
    fun `another untrusted leaf under a pin fails before any request is sent`() {
        val presented = TestCertificates.leaf("localhost")
        val server = serve(presented)
        val pinned = TestCertificates.leaf("localhost").server()
        val error = assertKind(Kind.CERTIFICATE_MISMATCH) { connect(server, CertificatePolicy.Pinned(pinned)) }
        assertEquals(presented.server(), error.presentedCertificate)
        assertNotEquals(pinned, error.presentedCertificate)
        assertEquals(0, server.requestCount)
    }

    @Test
    fun `concurrent connections each report their own leaf`(): Unit = runBlocking {
        val certificates = List(4) { TestCertificates.leaf("localhost") }
        val endpoints = certificates.map { serve(it) }
        val reported =
            (0 until 32)
                .map { attempt ->
                    async(Dispatchers.IO) {
                        val index = attempt % endpoints.size
                        val error =
                            try {
                                connect(endpoints[index], CertificatePolicy.System)
                                null
                            } catch (error: WebDavException) {
                                error
                            }
                        index to error
                    }
                }
                .awaitAll()
        reported.forEach { (index, error) ->
            assertEquals(Kind.CERTIFICATE_UNTRUSTED, error?.kind)
            assertEquals(certificates[index].server(), error?.presentedCertificate)
        }
    }

    @Test
    fun `http endpoints ignore the certificate policy`(): Unit = runBlocking {
        FakeWebDavServer().start().use { plain ->
            connector
                .connect(WebDavEndpoint(plain.baseUrl), basic(), CertificatePolicy.Pinned(TestCertificates.leaf("x").server()))
                .use { it.stat(WebDavPath.Root) }
        }
    }
}
