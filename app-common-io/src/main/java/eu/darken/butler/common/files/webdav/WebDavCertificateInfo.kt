package eu.darken.butler.common.files.webdav

import eu.darken.butler.common.files.webdav.location.TrustedCertificate
import eu.darken.webdav.ServerCertificate
import kotlin.time.Instant

/**
 * A server certificate as the UI shows it, e.g. in a trust confirmation: the DER encoded leaf, its
 * SHA-256 fingerprint (`3A:0F:…:9C`), RFC 2253 names (`CN=cloud.example.com,O=Example`) and validity.
 */
class WebDavCertificateInfo private constructor(
    der: ByteArray,
    val sha256Fingerprint: String,
    val subject: String,
    val issuer: String,
    val notBefore: Instant,
    val notAfter: Instant,
) {
    private val bytes = der.copyOf()

    val der: ByteArray
        get() = bytes.copyOf()

    fun toTrustedCertificate(): TrustedCertificate = TrustedCertificate(der = bytes.copyOf(), fingerprint = sha256Fingerprint)

    override fun equals(other: Any?): Boolean = other is WebDavCertificateInfo && bytes.contentEquals(other.bytes)

    override fun hashCode(): Int = bytes.contentHashCode()

    override fun toString(): String = "WebDavCertificateInfo($subject $sha256Fingerprint)"

    companion object {
        internal fun from(certificate: ServerCertificate) = WebDavCertificateInfo(
            der = certificate.encoded,
            sha256Fingerprint = certificate.sha256Fingerprint,
            subject = certificate.subject,
            issuer = certificate.issuer,
            notBefore = certificate.notBefore,
            notAfter = certificate.notAfter,
        )

        /** @throws IllegalArgumentException if the stored bytes are not an X.509 certificate */
        fun from(certificate: TrustedCertificate): WebDavCertificateInfo = from(certificate.toServerCertificate())
    }
}
