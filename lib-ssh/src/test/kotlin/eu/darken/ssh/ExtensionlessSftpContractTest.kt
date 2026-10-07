package eu.darken.ssh

import java.nio.file.Files
import java.nio.file.Path
import java.security.KeyPairGenerator
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.runBlocking
import org.apache.sshd.client.SshClient
import org.apache.sshd.client.config.hosts.HostConfigEntryResolver
import org.apache.sshd.client.keyverifier.AcceptAllServerKeyVerifier
import org.apache.sshd.common.file.virtualfs.VirtualFileSystemFactory
import org.apache.sshd.common.keyprovider.KeyIdentityProvider
import org.apache.sshd.common.keyprovider.KeyPairProvider
import org.apache.sshd.common.util.buffer.Buffer
import org.apache.sshd.common.util.buffer.ByteArrayBuffer
import org.apache.sshd.server.SshServer
import org.apache.sshd.server.channel.ChannelSession
import org.apache.sshd.server.command.Command
import org.apache.sshd.server.session.ServerSession
import org.apache.sshd.sftp.SftpModuleProperties
import org.apache.sshd.sftp.client.SftpClientFactory
import org.apache.sshd.sftp.common.SftpConstants
import org.apache.sshd.sftp.common.SftpException
import org.apache.sshd.sftp.server.FileHandle
import org.apache.sshd.sftp.server.SftpEventListener
import org.apache.sshd.sftp.server.SftpSubsystem
import org.apache.sshd.sftp.server.SftpSubsystemFactory
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Tag
import org.junit.jupiter.api.Test

/** Behavior against a server that advertises neither `statvfs@openssh.com` nor `fsync@openssh.com`. */
@Tag("sftp-embedded")
internal abstract class ExtensionlessSftpContractTest {
    protected abstract fun connector(config: SftpConfig = SftpConfig()): SftpConnector

    private suspend fun session(): SftpSession =
        connector()
            .connect(
                server.endpoint,
                SshCredentials.Password(ExtensionlessServer.USER, ExtensionlessServer.PASSWORD.toCharArray()),
                HostKeyPolicy.Pinned(server.hostKey),
            )

    @Test
    fun `server advertises neither statvfs nor fsync`() {
        val extensions = server.advertisedExtensions()
        assertTrue(extensions.isNotEmpty(), "Server reported no extensions at all: rig is broken")
        assertFalse("statvfs@openssh.com" in extensions)
        assertFalse("fsync@openssh.com" in extensions)
    }

    @Test
    fun `capacity is null without statvfs`(): Unit = runBlocking {
        session().use { assertNull(it.capacity(SftpPath.Root)) }
        assertFalse("statvfs@openssh.com" in server.extendedRequests)
    }

    @Test
    fun `upload flush and close complete without fsync and the bytes round trip`(): Unit = runBlocking {
        val payload = ByteArray(3 * 1024 * 1024 + 5).also { java.util.Random(7).nextBytes(it) }
        val path = SftpPath.Root.child("upload-${System.nanoTime()}.bin")
        session().use { session ->
            session.openFile(path, SftpOpenMode.CREATE_NEW).use { file ->
                var offset = 0
                while (offset < payload.size) {
                    val count = minOf(256 * 1024, payload.size - offset)
                    file.write(offset.toLong(), payload, offset, count)
                    offset += count
                }
                file.flush()
            }
            assertEquals(payload.size.toLong(), session.stat(path).size)
            val readBack = ByteArray(payload.size)
            session.openFile(path).use { file ->
                var done = 0
                while (done < readBack.size) {
                    val count = file.read(done.toLong(), readBack, done, readBack.size - done)
                    assertTrue(count > 0)
                    done += count
                }
            }
            assertArrayEquals(payload, readBack)
            assertArrayEquals(payload, server.written(path))
            session.delete(path)
        }
        assertFalse("fsync@openssh.com" in server.extendedRequests, "Client sent fsync")
    }

    /**
     * A rejected write may surface from write() itself; if it does not, flush() is where it must
     * appear, before anything else touches the handle. The handle is closed only afterwards, so a
     * failure reported by close() cannot satisfy the check.
     */
    private suspend fun assertRejectedWriteFailsByFlush(
        write: suspend (SftpFile) -> Unit,
        flush: suspend (SftpFile) -> Unit,
    ) {
        val path = SftpPath.Root.child("rejected-${System.nanoTime()}.bin")
        session().use { session ->
            val file = session.openFile(path, SftpOpenMode.CREATE_NEW)
            try {
                server.rejectWrites = true
                val error = runCatching {
                    write(file)
                    flush(file)
                }.exceptionOrNull()
                assertEquals(SshException.Kind.ACCESS_DENIED, (error as? SshException)?.kind, "Unexpected: $error")
            } finally {
                server.rejectWrites = false
                runCatching { file.close() }
            }
            session.delete(path)
        }
    }

    @Test
    fun `a write the server rejects fails at the latest on flush`(): Unit = runBlocking {
        assertRejectedWriteFailsByFlush(
            write = { it.write(0, ByteArray(16) { index -> index.toByte() }) },
            flush = { it.flush() },
        )
    }

    @Test
    fun `a write the server rejects fails at the latest on a blocking flush`(): Unit = runBlocking {
        assertRejectedWriteFailsByFlush(
            write = { it.blocking().write(0, ByteArray(16) { index -> index.toByte() }) },
            flush = { it.blocking().flush() },
        )
    }

    companion object {
        private val server: ExtensionlessServer by lazy { ExtensionlessServer() }
    }
}

internal class ExtensionlessServer {
    init {
        // MINA's process-wide setup must precede the first MINA class below.
        MinaSetup.install()
    }

    val root: Path = Files.createTempDirectory("sftp-extensionless").also { it.toFile().deleteOnExit() }
    val extendedRequests = CopyOnWriteArrayList<String>()

    /** While set, every SSH_FXP_WRITE is answered with SSH_FX_PERMISSION_DENIED. */
    @Volatile
    var rejectWrites = false
    private val hostKeyPair = KeyPairGenerator.getInstance("EC").apply { initialize(256) }.generateKeyPair()
    val hostKey: HostKey =
        HostKey.fromBlob(ByteArrayBuffer().apply { putRawPublicKey(hostKeyPair.public) }.compactData)

    private val server =
        SshServer.setUpDefaultServer().apply {
            host = "127.0.0.1"
            port = 0
            keyPairProvider = KeyPairProvider.wrap(hostKeyPair)
            passwordAuthenticator =
                org.apache.sshd.server.auth.password.PasswordAuthenticator { user, password, _ ->
                    user == USER && password == PASSWORD
                }
            fileSystemFactory = VirtualFileSystemFactory(root)
            subsystemFactories = listOf(RecordingSftpSubsystemFactory())
            // Replaces the default list (fsync, hardlink, lsetstat, posix-rename, limits).
            SftpModuleProperties.OPENSSH_EXTENSIONS.set(
                this,
                "posix-rename@openssh.com=1,hardlink@openssh.com=1",
            )
            start()
        }

    val endpoint: SftpEndpoint
        get() = SftpEndpoint("127.0.0.1", server.port)

    fun written(path: SftpPath): ByteArray? =
        root.resolve(path.segments.joinToString("/")).takeIf { Files.exists(it) }?.let { Files.readAllBytes(it) }

    /** Reads the VERSION extensions with MINA's own client, independent of the connector under test. */
    fun advertisedExtensions(): Set<String> {
        val client = SshClient.setUpDefaultClient()
        client.serverKeyVerifier = AcceptAllServerKeyVerifier.INSTANCE
        client.hostConfigEntryResolver = HostConfigEntryResolver.EMPTY
        client.keyIdentityProvider = KeyIdentityProvider.EMPTY_KEYS_PROVIDER
        client.start()
        try {
            client.connect(USER, "127.0.0.1", server.port).verify(10, TimeUnit.SECONDS).session.use { session ->
                session.addPasswordIdentity(PASSWORD)
                session.auth().verify(10, TimeUnit.SECONDS)
                SftpClientFactory.instance().createSftpClient(session).use { return it.serverExtensions.keys.toSet() }
            }
        } finally {
            client.stop()
        }
    }

    private val writeRejecter =
        object : SftpEventListener {
            override fun writing(
                session: ServerSession,
                remoteHandle: String,
                localHandle: FileHandle,
                offset: Long,
                data: ByteArray,
                dataOffset: Int,
                dataLen: Int,
            ) {
                if (rejectWrites) throw SftpException(SftpConstants.SSH_FX_PERMISSION_DENIED, "Writes rejected")
            }
        }

    private inner class RecordingSftpSubsystemFactory : SftpSubsystemFactory() {
        override fun createSubsystem(channel: ChannelSession): Command =
            object : SftpSubsystem(channel, this) {
                override fun executeExtendedCommand(buffer: Buffer, id: Int, extension: String) {
                    extendedRequests += extension
                    super.executeExtendedCommand(buffer, id, extension)
                }
            }.also { it.addSftpEventListener(writeRejecter) }
    }

    companion object {
        const val USER = "butler"
        const val PASSWORD = "butlerpass"
    }
}
