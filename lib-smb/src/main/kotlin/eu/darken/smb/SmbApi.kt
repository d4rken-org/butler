package eu.darken.smb

import java.io.IOException
import kotlin.time.Duration
import kotlin.time.Duration.Companion.seconds
import kotlin.time.Instant
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.withContext

public data class SmbEndpoint(val host: String, val share: String, val port: Int = 445) {
    init {
        require(host.isNotBlank() && host.none { it == '/' || it == '\\' || it == '\u0000' })
        require(port in 1..65535)
        require(SmbPath.isValidSegment(share))
    }
}

public class SmbPath(segments: List<String> = emptyList()) {
    public val segments: List<String> = java.util.Collections.unmodifiableList(segments.toList())

    init {
        require(this.segments.all(::isValidSegment)) { "Invalid SMB path segment" }
    }

    public fun child(name: String): SmbPath = SmbPath(segments + name)

    public val parent: SmbPath?
        get() = segments.takeIf { it.isNotEmpty() }?.let { SmbPath(it.dropLast(1)) }

    override fun equals(other: Any?): Boolean = other is SmbPath && segments == other.segments

    override fun hashCode(): Int = segments.hashCode()

    override fun toString(): String = segments.joinToString("/")

    public companion object {
        public val Root: SmbPath = SmbPath()

        public fun isValidSegment(value: String): Boolean =
            value.isNotBlank() &&
                value != "." &&
                value != ".." &&
                value.none { it == '/' || it == '\\' || it == '\u0000' }
    }
}

public sealed interface SmbCredentials {
    public data object Guest : SmbCredentials

    /** The caller retains ownership of [password] and may erase it after connect returns. */
    public class Password(
        public val username: String,
        public val password: CharArray,
        public val domain: String = "",
    ) : SmbCredentials {
        override fun toString(): String = "Password(<redacted>)"
    }
}

public enum class SmbDialect(public val value: Int) {
    SMB_2_0_2(0x0202),
    SMB_2_1(0x0210),
    SMB_3_0(0x0300),
    SMB_3_0_2(0x0302),
    SMB_3_1_1(0x0311),
}

public data class SmbConfig(
    val dialects: Set<SmbDialect> = SmbDialect.entries.toSet(),
    val connectTimeout: Duration = 10.seconds,
    val requestTimeout: Duration = 30.seconds,
    val requireSigning: Boolean = false,
    val requireEncryption: Boolean = false,
) {
    init {
        require(dialects.isNotEmpty())
        require(connectTimeout.isFinite() && connectTimeout.inWholeMilliseconds in 1..Int.MAX_VALUE)
        require(requestTimeout.isFinite() && requestTimeout.inWholeMilliseconds in 1..Int.MAX_VALUE)
        require(!requireEncryption || dialects.any { it.value >= 0x0300 })
    }
}

public fun interface SmbConnector {
    public suspend fun connect(endpoint: SmbEndpoint, credentials: SmbCredentials): SmbShare
}

public interface SmbResource {
    public suspend fun close()
}

public suspend inline fun <T : SmbResource, R> T.use(block: (T) -> R): R {
    var failure: Throwable? = null
    try {
        return block(this)
    } catch (error: Throwable) {
        failure = error
        throw error
    } finally {
        withContext(NonCancellable) {
            try {
                close()
            } catch (error: Throwable) {
                if (failure == null) throw error
                if (failure !== error) failure.addSuppressed(error)
            }
        }
    }
}

public enum class SmbFileType {
    FILE,
    DIRECTORY,
    REPARSE_POINT,
}

public data class SmbEntry(
    val path: SmbPath,
    val type: SmbFileType,
    val size: Long,
    val modifiedAt: Instant,
    val createdAt: Instant,
)

public data class SmbCapacity(val totalBytes: Long, val freeBytes: Long)

public enum class SmbOpenMode {
    READ,
    READ_WRITE,
    CREATE_NEW,
    OVERWRITE,
}

public interface SmbShare : SmbResource {
    public val connected: Boolean
    public val dialect: SmbDialect

    public suspend fun stat(path: SmbPath): SmbEntry

    /** Cold, paged enumeration. Collecting again starts a fresh enumeration. */
    public fun list(path: SmbPath): Flow<SmbEntry>

    public suspend fun mkdir(path: SmbPath)

    public suspend fun delete(path: SmbPath, recursive: Boolean = false)

    public suspend fun move(source: SmbPath, destination: SmbPath)

    public suspend fun openFile(path: SmbPath, mode: SmbOpenMode = SmbOpenMode.READ): SmbFile

    public suspend fun setModifiedAt(path: SmbPath, instant: Instant)

    public suspend fun capacity(): SmbCapacity

    /** Immediately releases the transport; safe from any thread and idempotent. */
    public fun disconnect()
}

public interface SmbBlockingFile : java.io.Closeable {
    public fun read(
        offset: Long,
        buffer: ByteArray,
        start: Int = 0,
        length: Int = buffer.size - start,
    ): Int

    public fun write(
        offset: Long,
        buffer: ByteArray,
        start: Int = 0,
        length: Int = buffer.size - start,
    )

    public fun size(): Long

    public fun resize(size: Long)

    public fun flush()
}

public interface SmbFile : SmbResource {
    /** Shares this handle's lifetime. For InputStream/Okio adapters driven on an I/O thread. */
    public fun blocking(): SmbBlockingFile

    /** Returns -1 at EOF; a zero-length read returns 0, including at EOF. */
    public suspend fun read(
        offset: Long,
        buffer: ByteArray,
        start: Int = 0,
        length: Int = buffer.size - start,
    ): Int

    /** Writes the entire requested range or throws. Mutations are never retried. */
    public suspend fun write(
        offset: Long,
        buffer: ByteArray,
        start: Int = 0,
        length: Int = buffer.size - start,
    )

    public suspend fun size(): Long

    public suspend fun resize(size: Long)

    public suspend fun flush()
}

public class SmbException(
    public val kind: Kind,
    public val status: Long? = null,
    message: String = "SMB operation failed: $kind",
    cause: Throwable? = null,
) : IOException(message, cause) {
    public enum class Kind {
        MISSING,
        ALREADY_EXISTS,
        ACCESS_DENIED,
        AUTHENTICATION,
        SHARE_ACCESS_DENIED,
        SHARE_MISSING,
        NOT_DIRECTORY,
        IS_DIRECTORY,
        DIRECTORY_NOT_EMPTY,
        DISK_FULL,
        SHARING_VIOLATION,
        TRANSPORT,
        UNSUPPORTED_DIALECT,
        PROTOCOL,
        OTHER,
    }
}
