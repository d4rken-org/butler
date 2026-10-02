package eu.darken.butler.common.files.sftp

import eu.darken.butler.common.files.sftp.location.SftpLocation
import eu.darken.butler.common.files.sftp.location.TrustedHostKey
import eu.darken.ssh.HostKey
import eu.darken.ssh.HostKeyPolicy
import eu.darken.ssh.SftpEndpoint
import eu.darken.ssh.SftpEntry
import eu.darken.ssh.SftpFileType
import eu.darken.ssh.SftpSession
import eu.darken.ssh.SshCredentials
import eu.darken.ssh.SshException
import eu.darken.ssh.SshException.Kind
import io.kotest.matchers.shouldBe
import io.kotest.matchers.types.shouldBeInstanceOf
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Test
import testhelpers.BaseTest
import java.net.ConnectException
import kotlin.time.Instant
import eu.darken.ssh.SftpPath as ServerPath

class SftpConnectionTesterTest : BaseTest() {

    private val pinned: HostKey = testHostKey(1).toHostKey()
    private val presented: HostKey = testHostKey(2).toHostKey()
    private val home = ServerPath(listOf("home", "darken"))

    private val session = mockk<SftpSession>(relaxed = true) {
        coEvery { canonicalize(".") } returns home
        coEvery { stat(home) } returns entry(home, SftpFileType.DIRECTORY)
        every { list(home) } returns emptyFlow()
    }

    private var connectFailure: Throwable? = null
    private var closedClients = 0
    private val credentials = mutableListOf<SshCredentials>()

    private val factory = SftpClientFactory {
        object : SftpClient {
            override suspend fun connect(
                endpoint: SftpEndpoint,
                credentials: SshCredentials,
                hostKeyPolicy: HostKeyPolicy,
            ): SftpSession {
                this@SftpConnectionTesterTest.credentials.add(credentials)
                policies.add(hostKeyPolicy)
                connectFailure?.let { throw it }
                return session
            }

            override fun close() {
                closedClients++
            }
        }
    }

    private val policies = mutableListOf<HostKeyPolicy>()

    private val tester = SftpConnectionTester(factory)

    private fun entry(path: ServerPath, type: SftpFileType) = SftpEntry(
        path = path,
        type = type,
        size = 0,
        modifiedAt = Instant.fromEpochSeconds(0),
        permissions = null,
        uid = null,
        gid = null,
    )

    private suspend fun test(
        basePath: String = "",
        policy: HostKeyPolicy = HostKeyPolicy.Pinned(pinned),
        authType: SftpLocation.AuthType = SftpLocation.AuthType.PASSWORD,
    ) = tester.test(
        host = "nas.local",
        port = 22,
        username = "darken",
        authType = authType,
        password = "hunter2".toCharArray(),
        privateKey = "key".toByteArray(),
        passphrase = "phrase".toCharArray(),
        basePath = basePath,
        hostKeyPolicy = policy,
    )

    @Test
    fun `a listable base directory succeeds with the resolved root`() = runTest {
        test() shouldBe SftpConnectionTester.Result.Success(home)

        coVerify { session.close() }
        closedClients shouldBe 1
    }

    @Test
    fun `the secret matching the auth type is used`() = runTest {
        test(authType = SftpLocation.AuthType.PASSWORD)
        test(authType = SftpLocation.AuthType.PRIVATE_KEY)

        credentials[0].shouldBeInstanceOf<SshCredentials.Password>()
        credentials[1].shouldBeInstanceOf<SshCredentials.PrivateKey>().passphrase?.concatToString() shouldBe "phrase"
    }

    @Test
    fun `an unknown server reports the presented key`() = runTest {
        connectFailure = SshException(Kind.HOST_KEY_UNKNOWN, presentedHostKey = presented)

        test(policy = HostKeyPolicy.Unknown) shouldBe SftpConnectionTester.Result.HostKeyUnknown(presented)
        closedClients shouldBe 1
    }

    @Test
    fun `a changed key reports both keys`() = runTest {
        connectFailure = SshException(Kind.HOST_KEY_MISMATCH, presentedHostKey = presented)

        test() shouldBe SftpConnectionTester.Result.HostKeyMismatch(expected = pinned, presented = presented)
    }

    @Test
    fun `sign-in failures are told apart`() = runTest {
        connectFailure = SshException(Kind.AUTHENTICATION)
        test() shouldBe SftpConnectionTester.Result.AuthenticationFailed

        connectFailure = SshException(Kind.KEY_FORMAT)
        test() shouldBe SftpConnectionTester.Result.KeyFormatInvalid

        connectFailure = SshException(Kind.KEY_PASSPHRASE)
        test() shouldBe SftpConnectionTester.Result.KeyPassphraseInvalid
    }

    @Test
    fun `a refused connection is unreachable`() = runTest {
        val refused = ConnectException("refused")
        connectFailure = refused

        test() shouldBe SftpConnectionTester.Result.Unreachable(refused)
    }

    @Test
    fun `a missing base path is reported`() = runTest {
        coEvery { session.canonicalize("gone") } throws SshException(Kind.MISSING)

        test(basePath = "gone") shouldBe SftpConnectionTester.Result.BasePathMissing
        coVerify { session.close() }
    }

    @Test
    fun `a file as base path is not a directory`() = runTest {
        val file = home.child("notes.txt")
        coEvery { session.canonicalize("notes.txt") } returns file
        coEvery { session.stat(file) } returns entry(file, SftpFileType.FILE)

        test(basePath = "notes.txt") shouldBe SftpConnectionTester.Result.BasePathNotDirectory
    }

    @Test
    fun `a directory that cannot be listed is denied`() = runTest {
        val locked = ServerPath(listOf("srv", "locked"))
        coEvery { session.canonicalize("/srv/locked") } returns locked
        coEvery { session.stat(locked) } returns entry(locked, SftpFileType.DIRECTORY)
        every { session.list(locked) } returns flow { throw SshException(Kind.ACCESS_DENIED) }

        test(basePath = "/srv/locked") shouldBe SftpConnectionTester.Result.BasePathAccessDenied
    }

    private suspend fun testTrusted(trustedKey: TrustedHostKey?) = tester.test(
        host = "nas.local",
        port = 22,
        username = "darken",
        authType = SftpLocation.AuthType.PASSWORD,
        password = "hunter2".toCharArray(),
        privateKey = null,
        passphrase = null,
        basePath = "",
        trustedKey = trustedKey,
    )

    @Test
    fun `no trusted key asks the server for its key`() = runTest {
        testTrusted(null) shouldBe SftpConnectionTester.Result.Success(home)

        policies.single() shouldBe HostKeyPolicy.Unknown
    }

    @Test
    fun `a trusted key pins the connection to it`() = runTest {
        testTrusted(testHostKey(1)) shouldBe SftpConnectionTester.Result.Success(home)

        policies.single() shouldBe HostKeyPolicy.Pinned(pinned)
    }

    @Test
    fun `host key results offer the keys as trusted keys`() = runTest {
        connectFailure = SshException(Kind.HOST_KEY_UNKNOWN, presentedHostKey = presented)
        testTrusted(null).shouldBeInstanceOf<SftpConnectionTester.Result.HostKeyUnknown>()
            .presentedKey shouldBe testHostKey(2)

        connectFailure = SshException(Kind.HOST_KEY_MISMATCH, presentedHostKey = presented)
        testTrusted(testHostKey(1)).shouldBeInstanceOf<SftpConnectionTester.Result.HostKeyMismatch>().let {
            it.expectedKey shouldBe testHostKey(1)
            it.presentedKey shouldBe testHostKey(2)
        }
    }
}
