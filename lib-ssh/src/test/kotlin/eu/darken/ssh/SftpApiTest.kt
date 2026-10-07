package eu.darken.ssh

import java.lang.reflect.Modifier
import kotlin.time.Duration
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeoutOrNull
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.ValueSource

internal class SftpApiTest {
    @ParameterizedTest
    @ValueSource(
        strings = ["a\\b", " ", "   ", "trail.", "a:b", "...", ".hidden", "Grüße 日本語 🎉", "\t", "C:"]
    )
    fun `legal segments`(segment: String) {
        assertTrue(SftpPath.isValidSegment(segment))
        assertEquals(listOf("dir", segment), SftpPath.Root.child("dir").child(segment).segments)
    }

    @ParameterizedTest
    @ValueSource(strings = ["", ".", "..", "a/b", "/", "nul\u0000"])
    fun `illegal segments`(segment: String) {
        assertFalse(SftpPath.isValidSegment(segment))
        assertThrows(IllegalArgumentException::class.java) { SftpPath.Root.child(segment) }
    }

    @Test
    fun `paths render absolute and navigate`() {
        assertEquals("/", SftpPath.Root.toString())
        val path = SftpPath.Root.child("srv").child("a\\b")
        assertEquals("/srv/a\\b", path.toString())
        assertEquals(SftpPath(listOf("srv")), path.parent)
        assertNull(SftpPath.Root.parent)
        assertEquals(path, SftpPath(listOf("srv", "a\\b")))
        assertEquals(path.hashCode(), SftpPath(listOf("srv", "a\\b")).hashCode())
    }

    @Test
    fun `canonical server paths parse strictly`() {
        assertEquals(SftpPath.Root, SftpPath.parseAbsolute("/"))
        assertEquals(SftpPath(listOf("upload", "x y")), SftpPath.parseAbsolute("/upload//x y/"))
        assertNull(SftpPath.parseAbsolute("upload"))
        assertNull(SftpPath.parseAbsolute("/upload/../etc"))
        assertNull(SftpPath.parseAbsolute("/./upload"))
    }

    @Test
    fun `host key fingerprint matches ssh-keygen`() {
        assertEquals(TestKeys.HOST_ED25519_FINGERPRINT, TestKeys.hostEd25519.sha256Fingerprint)
        assertEquals(TestKeys.HOST_RSA_FINGERPRINT, TestKeys.hostRsa.sha256Fingerprint)
        assertEquals("ssh-ed25519", TestKeys.hostEd25519.type)
    }

    @Test
    fun `host keys compare by type and blob and copy their bytes`() {
        val blob = TestKeys.hostEd25519.blob
        val key = HostKey("ssh-ed25519", blob)
        blob.fill(0)
        assertEquals(TestKeys.hostEd25519, key)
        assertEquals(TestKeys.hostEd25519.hashCode(), key.hashCode())
        assertNotEquals(TestKeys.hostRsa, key)
        key.blob.fill(0)
        assertEquals(TestKeys.hostEd25519, key)
        assertEquals(key, HostKey.fromBlob(key.blob))
        assertThrows(IllegalArgumentException::class.java) { HostKey("ssh-rsa", key.blob) }
        assertThrows(IllegalArgumentException::class.java) { HostKey("x", byteArrayOf(0, 0)) }
    }

    @Test
    fun `credentials never print secrets`() {
        val password = SshCredentials.Password("alice", "hunter2".toCharArray())
        val key = SshCredentials.PrivateKey("alice", "KEYDATA".toByteArray(), "phrase".toCharArray())
        for (text in listOf(password.toString(), key.toString())) {
            assertFalse(text.contains("hunter2"))
            assertFalse(text.contains("KEYDATA"))
            assertFalse(text.contains("phrase"))
            assertTrue(text.contains("redacted"))
        }
    }

    @Test
    fun `endpoint and config validation`() {
        SftpEndpoint("nas.local")
        SftpEndpoint("::1", 2222)
        for (host in listOf("", " ", "a/b", "a\\b", "a\u0000")) {
            assertThrows(IllegalArgumentException::class.java) { SftpEndpoint(host) }
        }
        for (port in listOf(0, 65536, -1)) {
            assertThrows(IllegalArgumentException::class.java) { SftpEndpoint("h", port) }
        }
        SftpConfig(1.milliseconds, 1.milliseconds)
        for (timeout in listOf(Duration.ZERO, Duration.INFINITE, (-1).seconds, Int.MAX_VALUE.seconds)) {
            assertThrows(IllegalArgumentException::class.java) { SftpConfig(connectTimeout = timeout) }
            assertThrows(IllegalArgumentException::class.java) { SftpConfig(requestTimeout = timeout) }
        }
    }

    @Test
    fun `SFTP status codes map to failure kinds`() {
        val expected =
            mapOf(
                2 to SshException.Kind.MISSING,
                3 to SshException.Kind.ACCESS_DENIED,
                4 to SshException.Kind.OTHER,
                5 to SshException.Kind.PROTOCOL,
                6 to SshException.Kind.TRANSPORT,
                7 to SshException.Kind.TRANSPORT,
                8 to SshException.Kind.UNSUPPORTED,
                10 to SshException.Kind.MISSING,
                11 to SshException.Kind.ALREADY_EXISTS,
                12 to SshException.Kind.ACCESS_DENIED,
                14 to SshException.Kind.DISK_FULL,
                15 to SshException.Kind.DISK_FULL,
                18 to SshException.Kind.DIRECTORY_NOT_EMPTY,
                19 to SshException.Kind.NOT_DIRECTORY,
                24 to SshException.Kind.IS_DIRECTORY,
                99 to SshException.Kind.OTHER,
            )
        for ((code, kind) in expected) assertEquals(kind, SshException.kindOfStatus(code), "status $code")
    }

    @Test
    fun `public API exposes no backend types`() {
        val publicTypes =
            listOf(
                SftpEndpoint::class.java,
                SftpPath::class.java,
                SshCredentials::class.java,
                SshCredentials.Password::class.java,
                SshCredentials.PrivateKey::class.java,
                HostKey::class.java,
                HostKeyPolicy::class.java,
                HostKeyPolicy.Pinned::class.java,
                HostKeyPolicy.Unknown::class.java,
                SftpConfig::class.java,
                SftpConnector::class.java,
                SftpResource::class.java,
                SftpEntry::class.java,
                SftpCapacity::class.java,
                SftpSession::class.java,
                SftpFile::class.java,
                SftpBlockingFile::class.java,
                SshException::class.java,
                MinaSftpConnector::class.java,
            )
        for (type in publicTypes) {
            val signatures =
                type.declaredMethods.filter { Modifier.isPublic(it.modifiers) }.flatMap {
                    it.parameterTypes.toList() + it.returnType
                } +
                    type.declaredConstructors.filter { Modifier.isPublic(it.modifiers) }.flatMap {
                        it.parameterTypes.toList()
                    } +
                    type.declaredFields.filter { Modifier.isPublic(it.modifiers) }.map { it.type } +
                    type.interfaces.toList() +
                    listOfNotNull(type.superclass)
            for (signature in signatures) {
                val name = signature.name
                assertFalse(
                    name.startsWith("org.apache.sshd") ||
                        name.startsWith("org.bouncycastle") ||
                        name.startsWith("net.schmizz") ||
                        name.startsWith("com.hierynomus"),
                    "${type.name} exposes $name",
                )
            }
        }
    }

    @Test
    fun `resource cleanup survives cancellation and preserves original failure`(): Unit = runBlocking {
        val original = IllegalStateException("body")
        val closeFailure = IllegalStateException("close")
        var closed = false
        val resource =
            object : SftpResource {
                override suspend fun close() {
                    delay(1)
                    closed = true
                    throw closeFailure
                }
            }
        try {
            resource.use { throw original }
        } catch (error: IllegalStateException) {
            assertSame(original, error)
            assertArrayEquals(arrayOf(closeFailure), error.suppressed)
        }
        assertTrue(closed)
        var cancelledClose = false
        withTimeoutOrNull(25.milliseconds) {
            object : SftpResource {
                    override suspend fun close() {
                        delay(1)
                        cancelledClose = true
                    }
                }
                .use { awaitCancellation() }
        }
        assertTrue(cancelledClose)
    }
}
