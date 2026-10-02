package eu.darken.butler.common.files.sftp

import android.os.Build
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import eu.darken.ssh.HostKey
import eu.darken.ssh.HostKeyPolicy
import eu.darken.ssh.MinaSftpConnector
import eu.darken.ssh.SftpEndpoint
import eu.darken.ssh.SftpFileType
import eu.darken.ssh.SftpOpenMode
import eu.darken.ssh.SftpPath
import eu.darken.ssh.SftpSession
import eu.darken.ssh.SshCredentials
import eu.darken.ssh.SshException
import eu.darken.ssh.use
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.matchers.collections.shouldContain
import io.kotest.matchers.shouldBe
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.runBlocking
import org.junit.AfterClass
import org.junit.Assume.assumeTrue
import org.junit.Before
import org.junit.BeforeClass
import org.junit.Test
import org.junit.runner.RunWith
import java.security.Security
import java.util.Base64
import java.util.UUID

/**
 * Runs lib-ssh on ART against tools/sftp-test-server.sh, reached through the `sftpHost`/`sftpPort`
 * instrumentation arguments (see tools/sftp-device-test.sh). `runBlocking`, not `runTest`: the
 * connection timeouts are real time.
 */
@RunWith(AndroidJUnit4::class)
class SftpArtSmokeDeviceTest {

    private lateinit var endpoint: SftpEndpoint

    @Before
    fun setup() {
        val args = InstrumentationRegistry.getArguments()
        val host = args.getString("sftpHost")
        val port = args.getString("sftpPort")
        assumeTrue("sftpHost/sftpPort not set, see tools/sftp-device-test.sh", host != null && port != null)
        endpoint = SftpEndpoint(host!!, port!!.toInt())
    }

    private fun key(name: String): ByteArray =
        InstrumentationRegistry.getInstrumentation().context.assets.open("keys/$name").use { it.readBytes() }

    private fun hostKey(pubName: String): HostKey {
        val (type, blob) = key(pubName).decodeToString().trim().split(' ')
        return HostKey(type, Base64.getDecoder().decode(blob))
    }

    private val hostEd25519 by lazy { hostKey("host_ed25519.pub") }
    private val hostRsa by lazy { hostKey("host_rsa.pub") }

    private fun password() = SshCredentials.Password(PASSWORD_USER, PASSWORD.toCharArray())

    private suspend fun connect(credentials: SshCredentials, policy: HostKeyPolicy): SftpSession =
        connector.connect(endpoint, credentials, policy)

    private suspend fun SftpSession.names(path: SftpPath): List<String> =
        list(path).toList().map { it.path.segments.last() }

    private suspend fun SftpSession.readAll(path: SftpPath): ByteArray = openFile(path).use { file ->
        val result = ByteArray(file.size().toInt())
        var done = 0
        while (done < result.size) {
            val count = file.read(done.toLong(), result, done, result.size - done)
            check(count > 0)
            done += count
        }
        result
    }

    @Test
    fun unknownPolicyRefusesAndPresentsTheHostKey() = runBlocking<Unit> {
        val error = shouldThrow<SshException> { connect(password(), HostKeyPolicy.Unknown) }
        error.kind shouldBe SshException.Kind.HOST_KEY_UNKNOWN
        error.presentedHostKey shouldBe hostEd25519
        error.presentedHostKey?.sha256Fingerprint shouldBe HOST_ED25519_FINGERPRINT
    }

    @Test
    fun passwordAuthListsWritesAndReads() = runBlocking<Unit> {
        connect(password(), HostKeyPolicy.Pinned(hostEd25519)).use { session ->
            session.names(SftpPath.Root) shouldContain "upload"
            session.readAll(UPLOAD.child("hello.txt")).decodeToString() shouldBe "Hello from Butler\n"

            val file = UPLOAD.child("art-smoke-${UUID.randomUUID()}.txt")
            val content = "Written on API ${Build.VERSION.SDK_INT}\n".repeat(1000).encodeToByteArray()
            session.openFile(file, SftpOpenMode.CREATE_NEW).use {
                it.write(0, content)
                it.flush()
            }
            session.readAll(file).contentEquals(content) shouldBe true
            session.delete(file)
            session.names(UPLOAD).contains(file.segments.last()) shouldBe false
        }
    }

    @Test
    fun encryptedEd25519KeyAuth() = runBlocking<Unit> {
        val credentials =
            SshCredentials.PrivateKey(KEY_USER, key("user_ed25519"), ED25519_PASSPHRASE.toCharArray())
        connect(credentials, HostKeyPolicy.Pinned(hostEd25519)).use { session ->
            session.names(SftpPath.Root) shouldContain "upload"
        }
    }

    @Test
    fun pemRsaKeyAuth() = runBlocking<Unit> {
        val credentials = SshCredentials.PrivateKey(KEY_USER, key("user_rsa_pem"), RSA_PASSPHRASE.toCharArray())
        connect(credentials, HostKeyPolicy.Pinned(hostEd25519)).use { session ->
            session.names(SftpPath.Root) shouldContain "upload"
        }
    }

    /** MINA's own check for this throws `javax.security.auth.login.FailedLoginException`, absent on ART. */
    @Test
    fun encryptedKeyWithoutPassphraseAsksForIt() = runBlocking<Unit> {
        for (name in listOf("user_ed25519", "user_rsa_pem")) {
            for (passphrase in listOf(null, CharArray(0))) {
                val credentials = SshCredentials.PrivateKey(KEY_USER, key(name), passphrase)
                val error = shouldThrow<SshException> { connect(credentials, HostKeyPolicy.Pinned(hostEd25519)) }
                error.kind shouldBe SshException.Kind.KEY_PASSPHRASE
            }
        }
    }

    /** Accepting a pinned RSA key proves the server presented it: any other key is a mismatch. */
    @Test
    fun pinnedRsaHostKeyIsNegotiated() = runBlocking<Unit> {
        connect(password(), HostKeyPolicy.Pinned(hostRsa)).use { session ->
            session.stat(SftpPath.Root).type shouldBe SftpFileType.DIRECTORY
        }
    }

    @Test
    fun connectingLeavesSecurityProvidersUntouched() = runBlocking<Unit> {
        val before = providers()
        before shouldBe providersAtStart
        connect(password(), HostKeyPolicy.Pinned(hostEd25519)).use { it.stat(UPLOAD) }
        SshCredentials.PrivateKey(KEY_USER, key("user_ed25519"), ED25519_PASSPHRASE.toCharArray()).let {
            connect(it, HostKeyPolicy.Pinned(hostEd25519)).use { session -> session.stat(UPLOAD) }
        }
        providers() shouldBe before
    }

    companion object {
        private const val PASSWORD_USER = "butler"
        private const val PASSWORD = "butlerpass"
        private const val KEY_USER = "keyuser"
        private const val ED25519_PASSPHRASE = "ed25519-passphrase"
        private const val RSA_PASSPHRASE = "rsa-passphrase"
        private const val HOST_ED25519_FINGERPRINT = "SHA256:Utlnml924yfwY1Df/Rf4pu3A8u5JKZ118Cd9/hz+ijM"
        private val UPLOAD = SftpPath(listOf("upload"))

        private fun providers() = Security.getProviders().map { "${it.name}:${it.javaClass.name}" }

        private val connector by lazy { MinaSftpConnector() }

        private lateinit var providersAtStart: List<String>

        @BeforeClass
        @JvmStatic
        fun recordProviders() {
            providersAtStart = providers()
        }

        @AfterClass
        @JvmStatic
        fun closeConnector() {
            connector.close()
        }
    }
}
