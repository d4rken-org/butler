package eu.darken.butler.common.files.sftp.location

import eu.darken.ssh.HostKey

/**
 * The server host key the user accepted for a location, e.g. type `ssh-ed25519`, its SSH wire
 * encoded public key blob and `SHA256:Utlnml924yfwY1Df/Rf4pu3A8u5JKZ118Cd9/hz+ijM`.
 */
data class TrustedHostKey(
    val type: String,
    val blob: ByteArray,
    val fingerprint: String,
) {
    init {
        require(type.isNotEmpty()) { "Host key type is empty" }
        require(fingerprint.startsWith(FINGERPRINT_PREFIX)) { "Not a SHA-256 fingerprint: $fingerprint" }
    }

    /** @throws IllegalArgumentException if the stored blob is not a [type] key */
    fun toHostKey(): HostKey = HostKey(type, blob)

    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other !is TrustedHostKey) return false
        return type == other.type && blob.contentEquals(other.blob) && fingerprint == other.fingerprint
    }

    override fun hashCode(): Int {
        var result = type.hashCode()
        result = 31 * result + blob.contentHashCode()
        result = 31 * result + fingerprint.hashCode()
        return result
    }

    override fun toString(): String = "TrustedHostKey($type $fingerprint)"

    companion object {
        private const val FINGERPRINT_PREFIX = "SHA256:"

        fun from(hostKey: HostKey) = TrustedHostKey(
            type = hostKey.type,
            blob = hostKey.blob,
            fingerprint = hostKey.sha256Fingerprint,
        )
    }
}
