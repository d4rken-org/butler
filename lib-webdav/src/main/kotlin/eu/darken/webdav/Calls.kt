package eu.darken.webdav

import java.io.IOException
import java.util.concurrent.Executor
import javax.net.ssl.SSLException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.asExecutor
import kotlinx.coroutines.suspendCancellableCoroutine
import okhttp3.Call
import okhttp3.Response

internal val ioExecutor: Executor = Dispatchers.IO.asExecutor()

/**
 * Runs blocking work on an I/O thread. Cancelling the coroutine runs [onCancel], which must release the
 * blocked work, and resumes immediately; a late result is dropped.
 */
internal suspend fun <T> blockingIo(onCancel: () -> Unit, block: () -> T): T =
    suspendCancellableCoroutine { continuation ->
        continuation.invokeOnCancellation { onCancel() }
        ioExecutor.execute { continuation.resumeWith(runCatching(block)) }
    }

/** Cancelling the coroutine cancels this call, which releases blocked socket I/O. */
internal suspend fun <T> Call.blockingIo(block: () -> T): T = blockingIo(::cancel, block)

internal suspend fun Call.await(): Response =
    suspendCancellableCoroutine { continuation ->
        continuation.invokeOnCancellation { cancel() }
        ioExecutor.execute {
            try {
                val response = execute()
                continuation.resume(response) { _, value, _ -> value.close() }
            } catch (error: Throwable) {
                continuation.resumeWith(Result.failure(error))
            }
        }
    }

internal fun Throwable.toWebDavException(): Throwable {
    if (this is WebDavException || this !is IOException) return this
    findPolicyFailure()?.let { policy ->
        val presented = runCatching { ServerCertificate(policy.leaf.encoded) }.getOrNull()
        return WebDavException(policy.kind, presentedCertificate = presented, cause = this)
    }
    val detail = if (this is SSLException) "TLS failure" else "Transport failure"
    return WebDavException(WebDavException.Kind.TRANSPORT, message = "$detail: ${javaClass.simpleName}: $message", cause = this)
}

/**
 * When a host has several addresses, OkHttp throws the first address's failure and attaches the others
 * as suppressed, so a refused IPv6 connection can carry the IPv4 certificate rejection.
 */
private fun Throwable.findPolicyFailure(): CertificatePolicyException? {
    val seen = HashSet<Throwable>()
    val pending = ArrayDeque(listOf(this))
    while (pending.isNotEmpty()) {
        val error = pending.removeFirst()
        if (!seen.add(error)) continue
        if (error is CertificatePolicyException) return error
        error.cause?.let(pending::addLast)
        pending.addAll(error.suppressed)
    }
    return null
}

/** Which request precondition a 412 answers. */
internal enum class Precondition {
    NONE,
    ABSENT,
    MATCH,
}

internal fun statusFailure(code: Int, method: String, precondition: Precondition = Precondition.NONE): WebDavException {
    val kind =
        when (code) {
            401 -> WebDavException.Kind.AUTHENTICATION
            403 -> WebDavException.Kind.ACCESS_DENIED
            404, 410 -> WebDavException.Kind.MISSING
            405 ->
                when (method) {
                    "MKCOL" -> WebDavException.Kind.ALREADY_EXISTS
                    else -> WebDavException.Kind.UNSUPPORTED
                }
            409 -> WebDavException.Kind.CONFLICT
            412 ->
                when (precondition) {
                    Precondition.ABSENT -> WebDavException.Kind.ALREADY_EXISTS
                    Precondition.MATCH -> WebDavException.Kind.CHANGED
                    Precondition.NONE -> WebDavException.Kind.OTHER
                }
            413, 507 -> WebDavException.Kind.DISK_FULL
            423 -> WebDavException.Kind.LOCKED
            501 -> WebDavException.Kind.UNSUPPORTED
            in 100..399 -> WebDavException.Kind.PROTOCOL
            else -> WebDavException.Kind.OTHER
        }
    return WebDavException(kind, httpStatus = code, message = "$method failed: $kind (HTTP $code)")
}

internal fun protocolFailure(message: String, httpStatus: Int? = null, cause: Throwable? = null): WebDavException =
    WebDavException(WebDavException.Kind.PROTOCOL, httpStatus = httpStatus, message = message, cause = cause)
