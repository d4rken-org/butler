package eu.darken.butler.common.files.sftp

import eu.darken.ssh.HostKey
import eu.darken.ssh.HostKeyPolicy
import eu.darken.ssh.MinaSftpConnector
import eu.darken.ssh.SftpEndpoint
import eu.darken.ssh.SshCredentials
import eu.darken.ssh.SshException
import kotlinx.coroutines.runBlocking
import org.apache.sshd.common.config.keys.KeyUtils
import org.apache.sshd.common.file.virtualfs.VirtualFileSystemFactory
import org.apache.sshd.common.keyprovider.KeyPairProvider
import org.apache.sshd.common.util.buffer.ByteArrayBuffer
import org.apache.sshd.server.SshServer
import org.apache.sshd.server.auth.password.PasswordAuthenticator
import org.apache.sshd.server.channel.ChannelSession
import org.apache.sshd.server.command.Command
import org.apache.sshd.sftp.server.SftpSubsystem
import org.apache.sshd.sftp.server.SftpSubsystemFactory
import java.nio.file.FileSystem
import java.nio.file.Files
import java.nio.file.Path
import java.security.KeyPairGenerator

/**
 * An in-process Apache MINA SFTP server whose user starts in `/home/butler` rather than `/`, which a
 * chrooted OpenSSH user never does. [files] is the server's `/`.
 */
class HomeDirectorySftpServer : AutoCloseable {

    init {
        installMinaSetup()
    }

    val files: Path = Files.createTempDirectory("sftp-home").also { it.toFile().deleteOnExit() }

    val home: Path = Files.createDirectories(files.resolve("home").resolve(USER))

    private val hostKeyPair = KeyPairGenerator.getInstance("EC").apply { initialize(256) }.generateKeyPair()

    val hostKey: HostKey = HostKey(
        KeyUtils.getKeyType(hostKeyPair.public),
        ByteArrayBuffer().apply { putRawPublicKey(hostKeyPair.public) }.compactData,
    )

    private val server = SshServer.setUpDefaultServer().apply {
        host = "127.0.0.1"
        port = 0
        keyPairProvider = KeyPairProvider.wrap(hostKeyPair)
        passwordAuthenticator = PasswordAuthenticator { user, password, _ -> user == USER && password == PASSWORD }
        fileSystemFactory = VirtualFileSystemFactory(files)
        subsystemFactories = listOf(HomeSubsystemFactory())
        start()
    }

    val port: Int
        get() = server.port

    override fun close() {
        server.stop(true)
        files.toFile().deleteRecursively()
    }

    private class HomeSubsystemFactory : SftpSubsystemFactory() {
        override fun createSubsystem(channel: ChannelSession): Command = object : SftpSubsystem(channel, this) {
            override fun setFileSystem(fileSystem: FileSystem) {
                super.setFileSystem(fileSystem)
                defaultDir = fileSystem.getPath("/home/$USER")
            }
        }
    }

    companion object {
        const val USER = "butler"
        const val PASSWORD = "butlerpass"

        /**
         * MINA's process-wide setup lives in lib-ssh and must run before this process touches any other
         * MINA class. Its public entry point is a connect, and an unparseable key fails right after the
         * setup, before any network I/O.
         */
        private fun installMinaSetup() = runBlocking {
            MinaSftpConnector().use { connector ->
                val error = runCatching {
                    connector.connect(
                        SftpEndpoint("127.0.0.1", 1),
                        SshCredentials.PrivateKey("setup", "not a key".toByteArray()),
                        HostKeyPolicy.Unknown,
                    )
                }.exceptionOrNull()
                check((error as? SshException)?.kind == SshException.Kind.KEY_FORMAT) { "MINA setup did not run: $error" }
            }
        }
    }
}
