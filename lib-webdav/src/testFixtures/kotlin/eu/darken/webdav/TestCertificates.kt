package eu.darken.webdav

import javax.net.ssl.X509TrustManager
import okhttp3.tls.HandshakeCertificates
import okhttp3.tls.HeldCertificate

/** Throwaway certificates for [FakeWebDavServer]; each call creates a new key pair. */
public object TestCertificates {
    /** A self-signed leaf for the given host names, e.g. `leaf("localhost")`. */
    public fun leaf(vararg hostNames: String, issuer: HeldCertificate? = null): HeldCertificate =
        HeldCertificate.Builder()
            .commonName(hostNames.firstOrNull() ?: "butler-test")
            .apply {
                hostNames.forEach(::addSubjectAlternativeName)
                issuer?.let(::signedBy)
            }
            .build()

    public fun authority(name: String = "Butler Test CA"): HeldCertificate =
        HeldCertificate.Builder().certificateAuthority(0).commonName(name).build()

    /** A trust manager that trusts only [authority], standing in for the platform's store. */
    public fun trusting(authority: HeldCertificate): X509TrustManager =
        HandshakeCertificates.Builder().addTrustedCertificate(authority.certificate).build().trustManager
}
