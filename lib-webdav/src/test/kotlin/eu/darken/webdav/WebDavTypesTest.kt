package eu.darken.webdav

import okhttp3.HttpUrl.Companion.toHttpUrl
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.ValueSource

class WebDavTypesTest {
    @Test
    fun `endpoints normalize to a collection URL with default ports`() {
        val endpoint = WebDavEndpoint("https://Cloud.Example/remote.php/dav/files/alice")
        assertEquals("https://cloud.example/remote.php/dav/files/alice/", endpoint.url)
        assertEquals("cloud.example", endpoint.host)
        assertEquals(443, endpoint.port)
        assertTrue(endpoint.isSecure)
        assertEquals(endpoint, WebDavEndpoint("https://cloud.example:443/remote.php/dav/files/alice/"))

        val plain = WebDavEndpoint("http://nas.local:8080")
        assertEquals("http://nas.local:8080/", plain.url)
        assertEquals(8080, plain.port)
        assertFalse(plain.isSecure)
        assertEquals(80, WebDavEndpoint("http://nas.local/dav/").port)
    }

    @ParameterizedTest
    @ValueSource(
        strings = [
            "ftp://nas.local/",
            "nas.local/dav",
            "/dav/",
            "https://user:pass@nas.local/",
            "https://user@nas.local/",
            "https://nas.local/dav?x=1",
            "https://nas.local/dav#top",
            "https://nas.local/dav/#",
        ],
    )
    fun `endpoints reject other schemes, userinfo, queries and fragments`(url: String) {
        assertThrows(IllegalArgumentException::class.java) { WebDavEndpoint(url) }
    }

    @Test
    fun `endpoint toString carries no credentials because none are accepted`() {
        assertEquals("https://nas.local/dav/", WebDavEndpoint("https://nas.local/dav").toString())
    }

    @Test
    fun `path segments are validated`() {
        listOf("", ".", "..", "a/b", "a\u0000b").forEach {
            assertFalse(WebDavPath.isValidSegment(it), it)
            assertThrows(IllegalArgumentException::class.java) { WebDavPath(listOf(it)) }
        }
        listOf("a b", "a\\b", " ", "...", "ü", "%2F", "?#").forEach { assertTrue(WebDavPath.isValidSegment(it), it) }
        val path = WebDavPath.Root.child("a").child("b c")
        assertEquals("/a/b c", path.toString())
        assertEquals(WebDavPath(listOf("a")), path.parent)
        assertEquals("b c", path.name)
        assertNull(WebDavPath.Root.parent)
    }

    @Test
    fun `path segments are percent-encoded one by one`() {
        val session =
            OkHttpWebDavSession(WebDavEndpoint("https://nas.local/remote.php/dav/files/a%40b/"), okhttp3.OkHttpClient(), okhttp3.OkHttpClient())
        assertEquals(
            "https://nas.local/remote.php/dav/files/a%40b/a%20b/%3F%23%25/x%5Cy/%252F",
            session.url(WebDavPath(listOf("a b", "?#%", "x\\y", "%2F"))).toString(),
        )
        assertEquals("https://nas.local/remote.php/dav/files/a%40b/", session.url(WebDavPath.Root).toString())
    }

    @Test
    fun `credentials redact secrets`() {
        val basic = WebDavCredentials.Basic("alice", "secret".toCharArray())
        assertFalse("secret" in basic.toString())
        assertFalse("alice" in basic.toString())
        assertThrows(IllegalArgumentException::class.java) { WebDavCredentials.Basic("a:b", CharArray(0)) }
    }

    @Test
    fun `server certificates expose fingerprint and names and compare by encoding`() {
        val held = TestCertificates.leaf("nas.local")
        val certificate = ServerCertificate(held.certificate.encoded)
        val digest = java.security.MessageDigest.getInstance("SHA-256").digest(held.certificate.encoded)
        assertEquals(digest.joinToString(":") { "%02X".format(it) }, certificate.sha256Fingerprint)
        assertTrue(Regex("([0-9A-F]{2}:){31}[0-9A-F]{2}").matches(certificate.sha256Fingerprint))
        assertEquals("CN=nas.local", certificate.subject)
        assertEquals("CN=nas.local", certificate.issuer)
        assertEquals(held.certificate.notAfter.time, certificate.notAfter.toEpochMilliseconds())
        assertEquals(held.certificate.notBefore.time, certificate.notBefore.toEpochMilliseconds())
        assertEquals(certificate, ServerCertificate(certificate.encoded))
        assertNotEquals(certificate, ServerCertificate(TestCertificates.leaf("nas.local").certificate.encoded))
        assertThrows(IllegalArgumentException::class.java) { ServerCertificate(byteArrayOf(1, 2, 3)) }
    }

    @Test
    fun `hrefs map to decoded segments in every common form`() {
        val request = "https://nas.local/remote.php/dav/files/u/a%20b/".toHttpUrl()
        val expected = listOf("remote.php", "dav", "files", "u", "a b", "c d")
        listOf(
            "/remote.php/dav/files/u/a%20b/c%20d",
            "/remote.php/dav/files/u/a%20b/c%20d/",
            "/remote.php/dav/files/u/a b/c d",
            "https://nas.local/remote.php/dav/files/u/a%20b/c%20d/",
            "http://proxy:8080/remote.php/dav/files/u/a%20b/c%20d",
            "c%20d",
            " /remote.php/dav/files/u/a%20b/c%20d\n",
        ).forEach { assertEquals(expected, hrefSegments(request, it), it) }
        assertEquals(listOf("u", "100%.txt"), hrefSegments(request, "/u/100%.txt"))
        assertEquals(listOf("u", "ü"), hrefSegments(request, "/u/%C3%BC"))
    }

    @Test
    fun `content ranges are parsed strictly`() {
        assertEquals(100L to 1000L, OkHttpWebDavSession.parseContentRange("bytes 100-199/1000"))
        assertEquals(100L to null, OkHttpWebDavSession.parseContentRange("bytes 100-199/*"))
        assertNull(OkHttpWebDavSession.parseContentRange("bytes 100-99/1000"))
        assertNull(OkHttpWebDavSession.parseContentRange("bytes 100-1000/1000"))
        assertNull(OkHttpWebDavSession.parseContentRange("items 1-2/3"))
        assertEquals(1000L, OkHttpWebDavSession.parseUnsatisfiedRange("bytes */1000"))
        assertNull(OkHttpWebDavSession.parseUnsatisfiedRange("bytes 0-1/1000"))
    }

    @Test
    fun `capacity totals only known values`() {
        assertEquals(30L, WebDavCapacity(10, 20).totalBytes)
        assertNull(WebDavCapacity(10, null).totalBytes)
        assertNull(WebDavCapacity(Long.MAX_VALUE, 1).totalBytes)
    }
}
