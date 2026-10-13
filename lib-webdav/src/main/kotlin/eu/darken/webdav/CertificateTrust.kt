package eu.darken.webdav

import java.net.Socket
import java.security.KeyStore
import java.security.cert.CertificateException
import java.security.cert.X509Certificate
import javax.net.ssl.HostnameVerifier
import javax.net.ssl.SSLEngine
import javax.net.ssl.SSLSession
import javax.net.ssl.TrustManagerFactory
import javax.net.ssl.X509ExtendedTrustManager
import javax.net.ssl.X509TrustManager
import okhttp3.internal.tls.OkHostnameVerifier

internal fun platformTrustManager(): X509TrustManager =
    TrustManagerFactory.getInstance(TrustManagerFactory.getDefaultAlgorithm())
        .apply { init(null as KeyStore?) }
        .trustManagers
        .filterIsInstance<X509TrustManager>()
        .first()

/**
 * Thrown from the handshake and carried out of OkHttp as the cause of its `SSLHandshakeException`,
 * so each failed connection reports the leaf it was shown.
 */
internal class CertificatePolicyException(
    val kind: WebDavException.Kind,
    val leaf: X509Certificate,
    cause: Throwable? = null,
) : CertificateException("Server certificate rejected: $kind", cause)

/**
 * Applies [policy] for connections to [host]. Every request of a session goes to the endpoint's origin,
 * so the host name is checked here, where a failure can still carry the leaf.
 */
internal class PolicyTrustManager(
    private val system: X509TrustManager,
    private val policy: CertificatePolicy,
    private val host: String,
) : X509ExtendedTrustManager() {
    override fun checkServerTrusted(chain: Array<X509Certificate>, authType: String) =
        check(chain) { system.checkServerTrusted(chain, authType) }

    override fun checkServerTrusted(chain: Array<X509Certificate>, authType: String, socket: Socket?) =
        check(chain) {
            if (system is X509ExtendedTrustManager) {
                system.checkServerTrusted(chain, authType, socket)
            } else {
                system.checkServerTrusted(chain, authType)
            }
        }

    override fun checkServerTrusted(chain: Array<X509Certificate>, authType: String, engine: SSLEngine?) =
        check(chain) {
            if (system is X509ExtendedTrustManager) {
                system.checkServerTrusted(chain, authType, engine)
            } else {
                system.checkServerTrusted(chain, authType)
            }
        }

    private inline fun check(chain: Array<X509Certificate>, systemCheck: () -> Unit) {
        val leaf = chain.firstOrNull() ?: throw CertificateException("Empty server certificate chain")
        val pin = (policy as? CertificatePolicy.Pinned)?.certificate
        if (pin != null && pin.matches(leaf.encoded)) return
        val kind = if (pin != null) WebDavException.Kind.CERTIFICATE_MISMATCH else WebDavException.Kind.CERTIFICATE_UNTRUSTED
        try {
            systemCheck()
        } catch (error: CertificateException) {
            throw CertificatePolicyException(kind, leaf, error)
        }
        if (!OkHostnameVerifier.verify(host, leaf)) throw CertificatePolicyException(kind, leaf)
    }

    override fun checkClientTrusted(chain: Array<X509Certificate>, authType: String) = refuseClient()

    override fun checkClientTrusted(chain: Array<X509Certificate>, authType: String, socket: Socket?) = refuseClient()

    override fun checkClientTrusted(chain: Array<X509Certificate>, authType: String, engine: SSLEngine?) = refuseClient()

    private fun refuseClient(): Nothing = throw CertificateException("Client certificates are not checked")

    override fun getAcceptedIssuers(): Array<X509Certificate> = system.acceptedIssuers
}

/** Mirrors [PolicyTrustManager] for OkHttp's post-handshake check, including resumed sessions. */
internal class PolicyHostnameVerifier(private val policy: CertificatePolicy) : HostnameVerifier {
    override fun verify(hostname: String, session: SSLSession): Boolean {
        val leaf = runCatching { session.peerCertificates.firstOrNull() }.getOrNull() as? X509Certificate ?: return false
        val pin = (policy as? CertificatePolicy.Pinned)?.certificate
        if (pin != null && pin.matches(leaf.encoded)) return true
        return OkHostnameVerifier.verify(hostname, leaf)
    }
}
