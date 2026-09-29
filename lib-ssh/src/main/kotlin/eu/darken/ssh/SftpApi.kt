package eu.darken.ssh

import java.io.IOException
import java.security.MessageDigest
import java.util.Base64
import kotlin.time.Duration
import kotlin.time.Duration.Companion.seconds
import kotlin.time.Instant
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.withContext

public data class SftpEndpoint(val host: String, val port: Int = 22) {
    init {
        require(host.isNotBlank() && host.none { it == '/' || it == '\\' || it == '\u0000' })
        require(port in 1..65535)
    }
}

/** An absolute server path, e.g. `SftpPath(listOf("srv", "a\\b"))` is `/srv/a\b`. */
public class SftpPath(segments: List<String> = emptyList()) {
    public val segments: List<String> = java.util.Collections.unmodifiableList(segments.toList())

    init {
        require(this.segments.all(::isValidSegment)) { "Invalid SFTP path segment" }
    }

    public fun child(name: String): SftpPath = SftpPath(segments + name)

    public val parent: SftpPath?
        get() = segments.takeIf { it.isNotEmpty() }?.let { SftpPath(it.dropLast(1)) }

    override fun equals(other: Any?): Boolean = other is SftpPath && segments == other.segments

    override fun hashCode(): Int = segments.hashCode()

    override fun toString(): String = "/" + segments.joinToString("/")

    public companion object {
        public val Root: SftpPath = SftpPath()

        public fun isValidSegment(value: String): Boolean =
            value.isNotEmpty() &&
                value != "." &&
                value != ".." &&
                value.none { it == '/' || it == '\u0000' }

        internal fun parseAbsolute(value: String): SftpPath? {
            if (!value.startsWith('/')) return null
            val segments = value.split('/').filter { it.isNotEmpty() }
            return if (segments.all(::isValidSegment)) SftpPath(segments) else null
        }
    }
}

/** The caller owns every secret array and may erase it once connect returns. */
public sealed interface SshCredentials {
    public val username: String

    public class Password(
        override val username: String,
        public val password: CharArray,
    ) : SshCredentials {
        override fun toString(): String = "Password(<redacted>)"
    }

    /** [keyBytes] is an OpenSSH, PEM or PKCS#8 private key file. */
    public class PrivateKey(
        override val username: String,
        public val keyBytes: ByteArray,
        public val passphrase: CharArray? = null,
    ) : SshCredentials {
        override fun toString(): String = "PrivateKey(<redacted>)"
    }
}

/** A server host key in SSH wire encoding, e.g. type `ssh-ed25519` and its public key blob. */
public class HostKey(public val type: String, blob: ByteArray) {
    private val bytes: ByteArray = blob.copyOf()

    public val blob: ByteArray
        get() = bytes.copyOf()

    /** OpenSSH format, e.g. `SHA256:Utlnml924yfwY1Df/Rf4pu3A8u5JKZ118Cd9/hz+ijM`. */
    public val sha256Fingerprint: String =
        "SHA256:" +
            Base64.getEncoder()
                .withoutPadding()
                .encodeToString(MessageDigest.getInstance("SHA-256").digest(bytes))

    init {
        require(type.isNotEmpty() && embeddedType(bytes) == type) { "Host key blob is not a $type key" }
    }

    override fun equals(other: Any?): Boolean =
        other is HostKey && type == other.type && bytes.contentEquals(other.bytes)

    override fun hashCode(): Int = 31 * type.hashCode() + bytes.contentHashCode()

    override fun toString(): String = "HostKey($type $sha256Fingerprint)"

    internal companion object {
        fun fromBlob(blob: ByteArray): HostKey =
            HostKey(requireNotNull(embeddedType(blob)) { "Malformed host key blob" }, blob)

        private fun embeddedType(blob: ByteArray): String? {
            if (blob.size < 4) return null
            val length =
                (blob[0].toInt() and 0xff shl 24) or
                    (blob[1].toInt() and 0xff shl 16) or
                    (blob[2].toInt() and 0xff shl 8) or
                    (blob[3].toInt() and 0xff)
            if (length <= 0 || length > blob.size - 4) return null
            return String(blob, 4, length, Charsets.US_ASCII)
        }
    }
}

/** Decides the presented host key before any user-authentication request is sent. */
public sealed interface HostKeyPolicy {
    /** Accepts only [expected]; any other key fails with [SshException.Kind.HOST_KEY_MISMATCH]. */
    public data class Pinned(val expected: HostKey) : HostKeyPolicy

    /** Always fails with [SshException.Kind.HOST_KEY_UNKNOWN], carrying the key for the user to confirm. */
    public data object Unknown : HostKeyPolicy
}

public data class SftpConfig(
    val connectTimeout: Duration = 10.seconds,
    val requestTimeout: Duration = 30.seconds,
) {
    init {
        require(connectTimeout.isFinite() && connectTimeout.inWholeMilliseconds in 1..Int.MAX_VALUE)
        require(requestTimeout.isFinite() && requestTimeout.inWholeMilliseconds in 1..Int.MAX_VALUE)
    }
}

public fun interface SftpConnector {
    public suspend fun connect(
        endpoint: SftpEndpoint,
        credentials: SshCredentials,
        hostKeyPolicy: HostKeyPolicy,
    ): SftpSession
}

public interface SftpResource {
    public suspend fun close()
}

public suspend inline fun <T : SftpResource, R> T.use(block: (T) -> R): R {
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

public enum class SftpFileType {
    FILE,
    DIRECTORY,
    SYMLINK,
    OTHER,
}

/** [permissions] holds the mode bits without file type bits (`07777`); `null` when the server omits them. */
public data class SftpEntry(
    val path: SftpPath,
    val type: SftpFileType,
    val size: Long,
    val modifiedAt: Instant,
    val permissions: Int?,
    val uid: Int?,
    val gid: Int?,
)

public data class SftpCapacity(val totalBytes: Long, val freeBytes: Long)

public enum class SftpOpenMode {
    READ,
    READ_WRITE,
    CREATE_NEW,
    OVERWRITE,
}

public interface SftpSession : SftpResource {
    public val connected: Boolean

    /** SSH_FXP_REALPATH: `.` and relative paths resolve against the server's initial directory. */
    public suspend fun canonicalize(path: String): SftpPath

    /** Follows symbolic links. */
    public suspend fun stat(path: SftpPath): SftpEntry

    public suspend fun lstat(path: SftpPath): SftpEntry

    /** Cold enumeration without `.` and `..`; entries describe links themselves, not their targets. */
    public fun list(path: SftpPath): Flow<SftpEntry>

    public suspend fun mkdir(path: SftpPath)

    /** Recursive deletion unlinks symbolic links and never descends through them. */
    public suspend fun delete(path: SftpPath, recursive: Boolean = false)

    /** Never replaces an existing destination. */
    public suspend fun rename(source: SftpPath, destination: SftpPath)

    /** Returns the raw link target, which may be relative or point outside any valid [SftpPath]. */
    public suspend fun readLink(path: SftpPath): String

    public suspend fun symlink(link: SftpPath, target: String)

    public suspend fun openFile(path: SftpPath, mode: SftpOpenMode = SftpOpenMode.READ): SftpFile

    /** SFTP v3 stores whole seconds; sub-second precision is dropped. */
    public suspend fun setModifiedAt(path: SftpPath, instant: Instant)

    /** Sets the `07777` mode bits. */
    public suspend fun setPermissions(path: SftpPath, mode: Int)

    /** Capacity of the file system holding [path], or `null` without `statvfs@openssh.com`. */
    public suspend fun capacity(path: SftpPath): SftpCapacity?

    /** Immediately releases the transport; safe from any thread and idempotent. */
    public fun disconnect()
}

public interface SftpBlockingFile : java.io.Closeable {
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

public interface SftpFile : SftpResource {
    /** Shares this handle's lifetime. For InputStream/Okio adapters driven on an I/O thread. */
    public fun blocking(): SftpBlockingFile

    /** Returns -1 at EOF; a zero-length read returns 0, including at EOF. */
    public suspend fun read(
        offset: Long,
        buffer: ByteArray,
        start: Int = 0,
        length: Int = buffer.size - start,
    ): Int

    /**
     * Sends the entire requested range or throws. A server rejection may surface from a later
     * write, [flush] or [close]. Mutations are never retried.
     */
    public suspend fun write(
        offset: Long,
        buffer: ByteArray,
        start: Int = 0,
        length: Int = buffer.size - start,
    )

    public suspend fun size(): Long

    public suspend fun resize(size: Long)

    /**
     * Waits until the server acknowledged every write. Never sends `fsync@openssh.com`, so this
     * makes no durability claim.
     */
    public suspend fun flush()
}

public class SshException(
    public val kind: Kind,
    public val presentedHostKey: HostKey? = null,
    message: String = "SSH operation failed: $kind",
    cause: Throwable? = null,
) : IOException(message, cause) {
    public enum class Kind {
        MISSING,
        ALREADY_EXISTS,
        ACCESS_DENIED,
        AUTHENTICATION,
        HOST_KEY_UNKNOWN,
        HOST_KEY_MISMATCH,
        NOT_DIRECTORY,
        IS_DIRECTORY,
        DIRECTORY_NOT_EMPTY,
        DISK_FULL,
        TRANSPORT,
        KEY_FORMAT,
        KEY_PASSPHRASE,
        UNSUPPORTED,
        PROTOCOL,
        OTHER,
    }

    internal companion object {
        /** SSH_FX_* status codes (draft-ietf-secsh-filexfer-13); only 0-8 exist in SFTP v3. */
        fun kindOfStatus(code: Int): Kind =
            when (code) {
                2, 10 -> Kind.MISSING
                3, 12 -> Kind.ACCESS_DENIED
                5 -> Kind.PROTOCOL
                6, 7 -> Kind.TRANSPORT
                8 -> Kind.UNSUPPORTED
                11 -> Kind.ALREADY_EXISTS
                14, 15 -> Kind.DISK_FULL
                18 -> Kind.DIRECTORY_NOT_EMPTY
                19 -> Kind.NOT_DIRECTORY
                24 -> Kind.IS_DIRECTORY
                else -> Kind.OTHER
            }
    }
}
