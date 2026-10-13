package eu.darken.butler.common.files.webdav.location

import eu.darken.webdav.ServerCertificate

/**
 * The server certificate the user accepted for a location: the DER encoded leaf and its SHA-256
 * fingerprint, e.g. `3A:0F:…:9C` (32 uppercase hex pairs).
 */
data class TrustedCertificate(
    val der: ByteArray,
    val fingerprint: String,
) {
    init {
        require(der.isNotEmpty()) { "Certificate is empty" }
        require(FINGERPRINT_FORMAT.matches(fingerprint)) { "Not a SHA-256 fingerprint: $fingerprint" }
    }

    /** @throws IllegalArgumentException if the stored bytes are not an X.509 certificate */
    fun toServerCertificate(): ServerCertificate = ServerCertificate(der)

    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other !is TrustedCertificate) return false
        return der.contentEquals(other.der) && fingerprint == other.fingerprint
    }

    override fun hashCode(): Int {
        var result = der.contentHashCode()
        result = 31 * result + fingerprint.hashCode()
        return result
    }

    override fun toString(): String = "TrustedCertificate($fingerprint)"

    companion object {
        private val FINGERPRINT_FORMAT = Regex("^[0-9A-F]{2}(:[0-9A-F]{2}){31}$")

        fun from(certificate: ServerCertificate) = TrustedCertificate(
            der = certificate.encoded,
            fingerprint = certificate.sha256Fingerprint,
        )
    }
}
