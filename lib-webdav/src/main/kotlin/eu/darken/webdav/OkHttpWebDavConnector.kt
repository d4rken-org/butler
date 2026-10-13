package eu.darken.webdav

import java.nio.ByteBuffer
import java.nio.CharBuffer
import java.util.Base64
import java.util.concurrent.TimeUnit
import javax.net.ssl.SSLContext
import javax.net.ssl.X509TrustManager
import okhttp3.ConnectionPool
import okhttp3.Dispatcher
import okhttp3.HttpUrl
import okhttp3.Interceptor
import okhttp3.OkHttpClient
import okhttp3.Response

/**
 * WebDAV over OkHttp. Each session owns its own client, connection pool and TLS context, so sessions
 * with different credentials or certificate policies never share a connection.
 */
public class OkHttpWebDavConnector internal constructor(
    private val config: WebDavConfig,
    systemTrustManager: () -> X509TrustManager,
) : WebDavConnector {
    public constructor(config: WebDavConfig = WebDavConfig()) : this(config, ::platformTrustManager)

    private val systemTrust by lazy(systemTrustManager)

    override suspend fun connect(
        endpoint: WebDavEndpoint,
        credentials: WebDavCredentials,
        certificatePolicy: CertificatePolicy,
    ): WebDavSession {
        val readClient = buildClient(endpoint, credentials, certificatePolicy)
        val session =
            OkHttpWebDavSession(
                endpoint,
                readClient,
                // A lost response does not prove that a mutation failed.
                readClient.newBuilder().retryOnConnectionFailure(false).build(),
            )
        try {
            session.verifyEndpoint()
        } catch (error: Throwable) {
            session.disconnect()
            throw error
        }
        return session
    }

    private fun buildClient(
        endpoint: WebDavEndpoint,
        credentials: WebDavCredentials,
        policy: CertificatePolicy,
    ): OkHttpClient {
        val builder =
            OkHttpClient.Builder()
                .connectTimeout(config.connectTimeout.inWholeMilliseconds, TimeUnit.MILLISECONDS)
                .readTimeout(config.readTimeout.inWholeMilliseconds, TimeUnit.MILLISECONDS)
                .writeTimeout(config.readTimeout.inWholeMilliseconds, TimeUnit.MILLISECONDS)
                .followRedirects(false)
                .followSslRedirects(false)
                // Below Apache's default 5 s KeepAliveTimeout, so mutations, which are never retried,
                // rarely pick a connection the server is about to close.
                .connectionPool(ConnectionPool(5, 4, TimeUnit.SECONDS))
                .dispatcher(Dispatcher())
        if (credentials is WebDavCredentials.Basic) {
            builder.addInterceptor(BasicAuthInterceptor(endpoint.httpUrl, basicHeader(credentials)))
        }
        if (endpoint.isSecure) {
            val trustManager = PolicyTrustManager(systemTrust, policy, endpoint.host)
            val context = SSLContext.getInstance("TLS").apply { init(null, arrayOf(trustManager), null) }
            builder
                .sslSocketFactory(context.socketFactory, trustManager)
                .hostnameVerifier(PolicyHostnameVerifier(policy))
        }
        return builder.build()
    }

    /** Encodes `username:password` as UTF-8 without turning the password into a String. */
    private fun basicHeader(credentials: WebDavCredentials.Basic): String {
        val chars = CharArray(credentials.username.length + 1 + credentials.password.size)
        credentials.username.toCharArray(chars)
        chars[credentials.username.length] = ':'
        credentials.password.copyInto(chars, credentials.username.length + 1)
        val encoded: ByteBuffer = Charsets.UTF_8.newEncoder().encode(CharBuffer.wrap(chars))
        val bytes = ByteArray(encoded.remaining()).also { encoded.get(it) }
        try {
            return "Basic " + Base64.getEncoder().encodeToString(bytes)
        } finally {
            chars.fill('\u0000')
            bytes.fill(0)
            if (encoded.hasArray()) encoded.array().fill(0)
        }
    }
}

/**
 * Sends credentials preemptively, and only to the endpoint's origin. There is no challenge-and-retry:
 * a streaming PUT body cannot be replayed after a 401.
 */
private class BasicAuthInterceptor(private val origin: HttpUrl, private val header: String) : Interceptor {
    override fun intercept(chain: Interceptor.Chain): Response {
        val request = chain.request()
        val url = request.url
        if (url.scheme != origin.scheme || url.host != origin.host || url.port != origin.port) return chain.proceed(request)
        return chain.proceed(request.newBuilder().header("Authorization", header).build())
    }
}
