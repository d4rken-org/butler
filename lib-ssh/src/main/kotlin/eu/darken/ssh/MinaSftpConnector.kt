package eu.darken.ssh

import java.io.ByteArrayInputStream
import java.net.SocketAddress
import java.nio.CharBuffer
import java.security.KeyPair
import java.security.PublicKey
import java.time.Duration
import org.apache.sshd.client.SshClient
import org.apache.sshd.client.auth.AbstractUserAuth
import org.apache.sshd.client.auth.UserAuth
import org.apache.sshd.client.auth.UserAuthFactory
import org.apache.sshd.client.auth.pubkey.UserAuthPublicKeyFactory
import org.apache.sshd.client.config.hosts.HostConfigEntryResolver
import org.apache.sshd.client.keyverifier.ServerKeyVerifier
import org.apache.sshd.client.session.ClientSession
import org.apache.sshd.common.AttributeRepository
import org.apache.sshd.common.NamedResource
import org.apache.sshd.common.SshConstants
import org.apache.sshd.common.auth.UserAuthMethodFactory
import org.apache.sshd.common.cipher.BuiltinCiphers
import org.apache.sshd.common.config.keys.FilePasswordProvider
import org.apache.sshd.common.config.keys.KeyUtils
import org.apache.sshd.common.kex.KexProposalOption
import org.apache.sshd.common.keyprovider.KeyIdentityProvider
import org.apache.sshd.common.session.Session
import org.apache.sshd.common.session.SessionContext
import org.apache.sshd.common.session.SessionListener
import org.apache.sshd.common.util.buffer.Buffer
import org.apache.sshd.common.util.buffer.ByteArrayBuffer
import org.apache.sshd.common.util.security.SecurityUtils
import org.apache.sshd.core.CoreModuleProperties
import org.apache.sshd.sftp.SftpModuleProperties
import org.apache.sshd.sftp.client.impl.AbstractSftpClient

/**
 * SFTP over Apache MINA SSHD. The connector owns one MINA client, started by the first [connect];
 * [close] stops it, which drops every session it opened. Blocking MINA calls run on
 * [kotlinx.coroutines.Dispatchers.IO]. MINA's process-wide setup is described in [MinaSetup].
 */
public class MinaSftpConnector(private val config: SftpConfig = SftpConfig()) : SftpConnector, java.io.Closeable {
    private val lock = Any()
    private var client: SshClient? = null
    private var closed = false

    override suspend fun connect(
        endpoint: SftpEndpoint,
        credentials: SshCredentials,
        hostKeyPolicy: HostKeyPolicy,
    ): SftpSession {
        MinaSetup.install()
        val handshake = Handshake.of(hostKeyPolicy, credentials)
        try {
            val session = MinaSession(config)
            try {
                session.open(client(), endpoint, credentials.username, handshake)
            } catch (error: Throwable) {
                session.disconnect()
                throw error
            }
            return session
        } finally {
            handshake.wipe()
        }
    }

    /** Stops the client and disconnects its sessions; later [connect] calls fail. Idempotent. */
    override fun close() {
        val running =
            synchronized(lock) {
                closed = true
                client.also { client = null }
            }
        running?.stop()
    }

    private fun client(): SshClient =
        synchronized(lock) {
            check(!closed) { "Connector is closed" }
            client ?: newClient().also { client = it }
        }

    private fun newClient(): SshClient =
        SshClient.setUpDefaultClient().apply {
            hostConfigEntryResolver = HostConfigEntryResolver.EMPTY
            keyIdentityProvider = KeyIdentityProvider.EMPTY_KEYS_PROVIDER
            filePasswordProvider = FilePasswordProvider.EMPTY
            serverKeyVerifier = HandshakeHooks
            addSessionListener(HandshakeHooks)
            // A pinned plain key could never match a certificate, and certificates are out of scope.
            signatureFactories = signatureFactories.filterNot { KeyUtils.isCertificateAlgorithm(it.name) }
            cipherFactories = cipherFactories.sortedBy { cipherRank(it.name) }
            userAuthFactories =
                listOf(UserAuthPublicKeyFactory.INSTANCE, PasswordAuthFactory, KeyboardInteractiveAuthFactory)
            val requestTimeout = Duration.ofMillis(config.requestTimeout.inWholeMilliseconds)
            // Idle sessions stay open; pooling policy belongs to the caller.
            CoreModuleProperties.IDLE_TIMEOUT.set(this, Duration.ZERO)
            // Small window adjustments and requests must not wait for the peer's delayed ACK.
            CoreModuleProperties.TCP_NODELAY.set(this, true)
            CoreModuleProperties.IO_CONNECT_TIMEOUT.set(
                this,
                Duration.ofMillis(config.connectTimeout.inWholeMilliseconds),
            )
            SftpModuleProperties.SFTP_CHANNEL_OPEN_TIMEOUT.set(this, requestTimeout)
            AbstractSftpClient.SFTP_CLIENT_CMD_TIMEOUT.set(this, requestTimeout)
            start()
        }
}

/** AES runs on the CPU's AES instructions; MINA computes chacha20-poly1305 in plain Java. */
private val preferredCiphers =
    listOf(
        BuiltinCiphers.Constants.AES128_GCM,
        BuiltinCiphers.Constants.AES256_GCM,
        BuiltinCiphers.Constants.AES128_CTR,
        BuiltinCiphers.Constants.AES192_CTR,
        BuiltinCiphers.Constants.AES256_CTR,
        BuiltinCiphers.Constants.CC20P1305_OPENSSH,
    )

/** Unlisted ciphers keep MINA's order after the preferred ones. */
private fun cipherRank(name: String): Int = preferredCiphers.indexOf(name).takeIf { it >= 0 } ?: preferredCiphers.size

/**
 * Per-connection state, reached by the client-wide [HandshakeHooks] through the connection context.
 * Secrets are wiped once authentication ends.
 */
internal class Handshake private constructor(
    val policy: HostKeyPolicy,
    private var password: CharArray?,
    @Volatile var keyPair: KeyPair?,
) {
    @Volatile var refusal: SshException? = null
    @Volatile var failure: Throwable? = null
    private var passwordClaimed = false

    /** Whether [claimPassword] would still return the password. */
    val passwordAvailable: Boolean
        @Synchronized get() = !passwordClaimed && password != null

    /** The password, returned once per connection to whichever method sends it first. */
    @Synchronized
    fun claimPassword(): CharArray? = password?.takeUnless { passwordClaimed }?.also { passwordClaimed = true }

    val context: AttributeRepository
        get() = AttributeRepository.ofKeyValuePair(KEY, this)

    fun verify(serverKey: PublicKey): Boolean {
        val presented = HostKey.fromBlob(ByteArrayBuffer().apply { putRawPublicKey(serverKey) }.compactData)
        val kind =
            when (policy) {
                is HostKeyPolicy.Pinned ->
                    if (presented == policy.expected) return true else SshException.Kind.HOST_KEY_MISMATCH
                HostKeyPolicy.Unknown -> SshException.Kind.HOST_KEY_UNKNOWN
            }
        refusal = SshException(kind, presented, "Host key ${presented.sha256Fingerprint} refused: $kind")
        return false
    }

    @Synchronized
    fun wipe() {
        password?.fill('\u0000')
        password = null
        keyPair = null
    }

    companion object {
        val KEY = AttributeRepository.AttributeKey<Handshake>()

        /** Private keys are parsed here, before any network I/O. */
        fun of(policy: HostKeyPolicy, credentials: SshCredentials): Handshake =
            when (credentials) {
                is SshCredentials.Password -> Handshake(policy, credentials.password.copyOf(), null)
                is SshCredentials.PrivateKey ->
                    Handshake(policy, null, loadKey(credentials.keyBytes, credentials.passphrase))
            }

        /** MINA's key parsers only take the passphrase as a String, which cannot be erased. */
        private fun loadKey(keyBytes: ByteArray, passphrase: CharArray?): KeyPair {
            val secret = passphrase?.copyOf()
            var requested = false
            val provider =
                object : FilePasswordProvider {
                    override fun getPassword(session: SessionContext?, resourceKey: NamedResource?, retryIndex: Int) =
                        secret?.concatToString().also { requested = true }

                    /**
                     * Every parser decrypts through here. The default fails an empty passphrase with
                     * `javax.security.auth.login.FailedLoginException`, which Android does not ship.
                     */
                    override fun <T> decode(
                        session: SessionContext?,
                        resourceKey: NamedResource?,
                        decoder: FilePasswordProvider.Decoder<out T>,
                    ): T {
                        val password = getPassword(session, resourceKey, 0)
                        if (password.isNullOrEmpty()) {
                            throw SshException(SshException.Kind.KEY_PASSPHRASE, message = "Missing key passphrase")
                        }
                        return decoder.decode(password)
                    }
                }
            try {
                val pairs =
                    SecurityUtils.loadKeyPairIdentities(
                        null,
                        NamedResource.ofName("private key"),
                        ByteArrayInputStream(keyBytes),
                        provider,
                    )
                return pairs?.firstOrNull()
                    ?: throw SshException(SshException.Kind.KEY_FORMAT, message = "Unsupported key format")
            } catch (error: SshException) {
                throw error
            } catch (error: Exception) {
                val kind = if (requested) SshException.Kind.KEY_PASSPHRASE else SshException.Kind.KEY_FORMAT
                throw SshException(kind, message = "Private key could not be loaded: $kind", cause = error)
            } finally {
                secret?.fill('\u0000')
            }
        }
    }
}

internal val ClientSession.handshake: Handshake?
    get() = connectionContext?.getAttribute(Handshake.KEY)

/** Client-wide hooks that apply each connection's [Handshake] during key exchange. */
private object HandshakeHooks : ServerKeyVerifier, SessionListener {
    override fun verifyServerKey(session: ClientSession, remoteAddress: SocketAddress, serverKey: PublicKey): Boolean =
        session.handshake?.verify(serverKey) ?: false

    /** Offers the pinned key's algorithms first, so a server with several host keys presents that one. */
    override fun sessionNegotiationOptionsCreated(session: Session, proposal: MutableMap<KexProposalOption, String>) {
        val pinned = (session as? ClientSession)?.handshake?.policy as? HostKeyPolicy.Pinned ?: return
        val offered = proposal[KexProposalOption.SERVERKEYS]?.split(',') ?: return
        val preferred = signatureAlgorithms(pinned.expected.type)
        val (first, rest) = offered.partition { it in preferred }
        proposal[KexProposalOption.SERVERKEYS] = (first + rest).joinToString(",")
    }

    override fun sessionException(session: Session, t: Throwable) {
        val handshake = (session as? ClientSession)?.handshake ?: return
        if (handshake.failure == null) handshake.failure = t
    }

    private fun signatureAlgorithms(keyType: String): List<String> =
        if (keyType == "ssh-rsa") listOf("rsa-sha2-512", "rsa-sha2-256", "ssh-rsa") else listOf(keyType)
}

private object PasswordAuthFactory : UserAuthFactory {
    override fun getName(): String = PasswordAuth.NAME

    override fun createUserAuth(session: ClientSession): UserAuth = PasswordAuth()
}

/**
 * RFC 4252 password method, sent from the handshake's char copy. MINA's own method takes the
 * password as a String, which cannot be erased. A password change request fails authentication.
 */
private class PasswordAuth : AbstractUserAuth(NAME) {
    override fun sendAuthDataRequest(session: ClientSession, service: String): Boolean {
        if (!UserAuthMethodFactory.isSecureAuthenticationTransport(session)) return false
        val password = session.handshake?.claimPassword() ?: return false
        val bytes = utf8(password)
        try {
            val buffer: Buffer = session.createBuffer(SshConstants.SSH_MSG_USERAUTH_REQUEST, bytes.size + 128)
            buffer.putString(session.username)
            buffer.putString(service)
            buffer.putString(NAME)
            buffer.putBoolean(false)
            buffer.putBytes(bytes)
            session.writePacket(buffer)
        } finally {
            bytes.fill(0)
        }
        return true
    }

    override fun processAuthDataRequest(session: ClientSession, service: String, buffer: Buffer): Boolean = false

    companion object {
        const val NAME = "password"
    }
}

private object KeyboardInteractiveAuthFactory : UserAuthFactory {
    override fun getName(): String = KeyboardInteractiveAuth.NAME

    override fun createUserAuth(session: ClientSession): UserAuth = KeyboardInteractiveAuth()
}

/**
 * RFC 4256 keyboard-interactive, for servers that take passwords only this way, such as stock FreeBSD
 * sshd. It starts only while the password is unsent, so it never repeats a password the password
 * method already sent. A single hidden prompt gets the password, a request without prompts gets an
 * empty reply, and any other request ends the method, which fails authentication.
 */
private class KeyboardInteractiveAuth : AbstractUserAuth(NAME) {
    private var requested = false

    override fun sendAuthDataRequest(session: ClientSession, service: String): Boolean {
        if (requested || !UserAuthMethodFactory.isSecureAuthenticationTransport(session)) return false
        if (session.handshake?.passwordAvailable != true) return false
        requested = true
        val buffer: Buffer = session.createBuffer(SshConstants.SSH_MSG_USERAUTH_REQUEST, 128)
        buffer.putString(session.username)
        buffer.putString(service)
        buffer.putString(NAME)
        buffer.putString("") // language tag
        buffer.putString("") // submethods
        session.writePacket(buffer)
        return true
    }

    override fun processAuthDataRequest(session: ClientSession, service: String, buffer: Buffer): Boolean {
        if (buffer.getUByte() != SshConstants.SSH_MSG_USERAUTH_INFO_REQUEST.toInt()) return false
        buffer.getString() // name
        buffer.getString() // instruction
        buffer.getString() // language tag
        when (buffer.getInt()) {
            0 -> {
                val reply = session.createBuffer(SshConstants.SSH_MSG_USERAUTH_INFO_RESPONSE, Int.SIZE_BYTES)
                reply.putUInt(0)
                session.writePacket(reply)
            }
            1 -> {
                buffer.getString() // prompt
                val echo = buffer.getBoolean()
                if (echo) return false
                val password = session.handshake?.claimPassword() ?: return false
                val bytes = utf8(password)
                try {
                    val reply =
                        session.createBuffer(SshConstants.SSH_MSG_USERAUTH_INFO_RESPONSE, bytes.size + 64)
                    reply.putUInt(1)
                    reply.putBytes(bytes)
                    session.writePacket(reply)
                } finally {
                    bytes.fill(0)
                }
            }
            else -> return false
        }
        return true
    }

    companion object {
        const val NAME = "keyboard-interactive"
    }
}

/** The caller wipes the returned bytes; the encoder's own buffer is wiped here. */
private fun utf8(password: CharArray): ByteArray {
    val encoded = Charsets.UTF_8.newEncoder().encode(CharBuffer.wrap(password))
    val bytes = ByteArray(encoded.remaining()).also { encoded.get(it) }
    encoded.array().fill(0)
    return bytes
}
