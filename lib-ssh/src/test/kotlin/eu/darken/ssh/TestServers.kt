package eu.darken.ssh

import java.util.Base64
import org.testcontainers.containers.GenericContainer
import org.testcontainers.containers.wait.strategy.Wait
import org.testcontainers.images.builder.Transferable

internal object TestKeys {
    fun bytes(name: String): ByteArray =
        requireNotNull(TestKeys::class.java.getResourceAsStream("/keys/$name")) { name }
            .use { it.readBytes() }

    /** Parses an OpenSSH `.pub` line such as `ssh-ed25519 AAAA... comment`. */
    fun hostKey(pubName: String): HostKey {
        val (type, blob) = bytes(pubName).decodeToString().trim().split(' ')
        return HostKey(type, Base64.getDecoder().decode(blob))
    }

    val hostEd25519: HostKey by lazy { hostKey("host_ed25519.pub") }
    val hostRsa: HostKey by lazy { hostKey("host_rsa.pub") }

    const val HOST_ED25519_FINGERPRINT = "SHA256:Utlnml924yfwY1Df/Rf4pu3A8u5JKZ118Cd9/hz+ijM"
    const val HOST_RSA_FINGERPRINT = "SHA256:gtMpsf94AplmuYlgCVSpj9LfdofX9r4PFRoI3xJFEAE"
    const val ED25519_PASSPHRASE = "ed25519-passphrase"
    const val RSA_PASSPHRASE = "rsa-passphrase"
}

/**
 * atmoz/sftp runs Debian's OpenSSH (9.2) `internal-sftp` chrooted into each user's home, so the
 * session sees `/` (root-owned, read-only) with a writable `/upload`. sshd logs to stderr, which
 * makes `Invalid user` lines visible through the container log.
 *
 * With [passwordsOnlyInteractively], sshd takes passwords only through `keyboard-interactive`, which
 * PAM answers with a single hidden `Password: ` prompt, as stock FreeBSD sshd is configured.
 */
internal class OpenSshServer(passwordsOnlyInteractively: Boolean = false) :
    GenericContainer<OpenSshServer>(
        "atmoz/sftp@sha256:75dcc29683ad479bdb99010666f2f55e99014350a1e862fe32b565845feb0fcd"
    ) {
    init {
        withExposedPorts(22)
        withCommand("$PASSWORD_USER:$PASSWORD:1001:100:upload", "$KEY_USER::1002:100:upload")
        for (name in listOf("host_ed25519", "host_rsa")) {
            val target = "/etc/ssh/ssh_host_${name.removePrefix("host_")}_key"
            withCopyToContainer(Transferable.of(TestKeys.bytes(name), 0x180), target)
            withCopyToContainer(Transferable.of(TestKeys.bytes("$name.pub"), 0x1a4), "$target.pub")
        }
        for (name in listOf("user_ed25519", "user_rsa_pem", "user_ecdsa_pkcs8")) {
            withCopyToContainer(
                Transferable.of(TestKeys.bytes("$name.pub"), 0x1a4),
                "/home/$KEY_USER/.ssh/keys/$name.pub",
            )
        }
        if (passwordsOnlyInteractively) {
            // The image's entrypoint runs /etc/sftp.d scripts before starting sshd.
            withCopyToContainer(
                Transferable.of(INTERACTIVE_ONLY_SCRIPT.encodeToByteArray(), 0x1ed),
                "/etc/sftp.d/kbdint",
            )
            // The image's stock PAM stack requires pam_loginuid, which fails without CAP_AUDIT_CONTROL.
            withCopyToContainer(Transferable.of(INTERACTIVE_PAM.encodeToByteArray(), 0x1a4), "/etc/pam.d/sshd")
        }
        waitingFor(Wait.forLogMessage(".*Server listening on 0\\.0\\.0\\.0 port 22.*", 1))
    }

    fun endpoint(): SftpEndpoint = SftpEndpoint(host, getMappedPort(22))

    companion object {
        const val PASSWORD_USER = "butler"
        const val PASSWORD = "butlerpass"
        const val KEY_USER = "keyuser"

        val shared: OpenSshServer by lazy { OpenSshServer().also { it.start() } }

        val interactiveOnly: OpenSshServer by lazy {
            OpenSshServer(passwordsOnlyInteractively = true).also { it.start() }
        }

        private val INTERACTIVE_ONLY_SCRIPT =
            """
            #!/bin/sh
            printf '%s\n' 'UsePAM yes' 'PasswordAuthentication no' 'KbdInteractiveAuthentication yes' \
                >> /etc/ssh/sshd_config
            """.trimIndent() + "\n"

        private val INTERACTIVE_PAM =
            """
            auth required pam_unix.so nodelay
            account required pam_unix.so
            session required pam_permit.so
            """.trimIndent() + "\n"
    }
}
