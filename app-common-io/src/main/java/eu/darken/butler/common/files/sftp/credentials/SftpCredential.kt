package eu.darken.butler.common.files.sftp.credentials

import eu.darken.ssh.SshCredentials

/** A resolved credential, handed out as a private copy the caller may wipe. */
sealed class SftpCredential {

    abstract val username: String

    abstract fun wipe()

    /** Shares this credential's arrays, so [wipe] only after the connection attempt returned. */
    abstract fun toSshCredentials(): SshCredentials

    internal abstract fun copy(): SftpCredential

    class Password(
        override val username: String,
        val password: CharArray,
    ) : SftpCredential() {
        override fun wipe() = password.fill(Char(0))

        override fun toSshCredentials() = SshCredentials.Password(username, password)

        override fun copy() = Password(username, password.copyOf())

        override fun toString(): String = "SftpCredential.Password(<redacted>)"
    }

    /** [keyBytes] is the private key file, e.g. an OpenSSH, PEM or PKCS#8 key. */
    class PrivateKey(
        override val username: String,
        val keyBytes: ByteArray,
        val passphrase: CharArray? = null,
    ) : SftpCredential() {
        override fun wipe() {
            keyBytes.fill(0)
            passphrase?.fill(Char(0))
        }

        override fun toSshCredentials() = SshCredentials.PrivateKey(username, keyBytes, passphrase)

        override fun copy() = PrivateKey(username, keyBytes.copyOf(), passphrase?.copyOf())

        override fun toString(): String = "SftpCredential.PrivateKey(<redacted>)"
    }
}
