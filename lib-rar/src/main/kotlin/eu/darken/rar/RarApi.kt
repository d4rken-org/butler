package eu.darken.rar

import java.io.IOException
import java.io.InputStream
import kotlin.time.Instant

/** Archive-wide flags from the main archive header. [volumeNumber] is 0 for the first volume. */
public data class RarArchiveInfo(
    val solid: Boolean,
    val multiVolume: Boolean,
    val volumeNumber: Int,
    val headerEncrypted: Boolean,
    val locked: Boolean,
    val hasRecoveryRecord: Boolean,
)

/**
 * One file or directory header. [ordinal] counts entries from 0 in archive order. [size] is null
 * when the archive declares the unpacked size as unknown. For an entry that is
 * [splitAcrossVolumes], [packedSize] covers the part in the volume that lists it and [checksum] is
 * null: that part's stored value covers its packed bytes, the file's own is in its last part.
 * [attributes] are Windows attributes or a Unix mode, depending on [hostOs]. [version] is the
 * `-ver` file version, null for the current one.
 */
public data class RarEntry(
    val ordinal: Int,
    val name: String,
    val isDirectory: Boolean,
    val size: Long?,
    val packedSize: Long,
    val mtime: Instant?,
    val ctime: Instant?,
    val atime: Instant?,
    val attributes: Long,
    val hostOs: RarHostOs,
    val isEncrypted: Boolean,
    val isSolid: Boolean,
    val redirection: RarRedirection?,
    val checksum: RarChecksum?,
    val version: Int?,
    val splitAcrossVolumes: Boolean,
)

public enum class RarHostOs { WINDOWS, UNIX, OTHER }

public data class RarRedirection(
    val type: RarRedirectionType,
    val target: String,
    val targetIsDirectory: Boolean,
)

public enum class RarRedirectionType { UNIX_SYMLINK, WINDOWS_SYMLINK, JUNCTION, HARDLINK, FILE_COPY, UNKNOWN }

/** For an encrypted entry the archive may store a password-keyed value instead of the plain checksum. */
public sealed class RarChecksum {
    public data class Crc32(val value: Int) : RarChecksum()

    public class Blake2sp(digest: ByteArray) : RarChecksum() {
        private val bytes = digest.copyOf()

        public val digest: ByteArray get() = bytes.copyOf()

        override fun equals(other: Any?): Boolean = other is Blake2sp && bytes.contentEquals(other.bytes)

        override fun hashCode(): Int = bytes.contentHashCode()

        override fun toString(): String = "Blake2sp(${bytes.joinToString("") { "%02x".format(it) }})"
    }
}

public enum class RarPasswordScope { HEADERS, ENTRY }

public data class RarPasswordRequest(
    val scope: RarPasswordScope,
    val ordinal: Int?,
    val entryName: String?,
)

/** Returns the password for [request], or null to give up. The reader wipes the returned array. */
public fun interface RarPasswordProvider {
    public fun password(request: RarPasswordRequest): CharArray?
}

/**
 * Opens volume [volumeIndex] (0 is the first volume), or returns null if it is missing. The reader
 * closes it. The reader asks for 1, 2, … in order, each at most once, when the previous volume ends.
 */
public fun interface RarVolumeProvider {
    public fun open(volumeIndex: Int): InputStream?
}

/** Large allocations go through [acquire] and [release]. [acquire] may block and throw. */
public interface RarMemoryBudget {
    public fun acquire(bytes: Long)

    public fun release(bytes: Long)

    public companion object {
        public val UNLIMITED: RarMemoryBudget = object : RarMemoryBudget {
            override fun acquire(bytes: Long) = Unit

            override fun release(bytes: Long) = Unit
        }
    }
}

public data class RarLimits(
    val maxWindowBytes: Long = 256L * 1024 * 1024,
    val maxHeaderSize: Int = MAX_HEADER_SIZE,
    val maxEntries: Int = 1_000_000,
    val maxNameBytes: Int = 8192,
    val maxExtraRecords: Int = 1024,
    val maxVolumes: Int = 10_000,
    val maxPendingFilters: Int = 8192,
    val maxPendingFilterBytes: Long = 64L * 1024 * 1024,
    val maxKdfLog2: Int = 24,
) {
    init {
        require(maxWindowBytes > 0)
        require(maxHeaderSize in 1..MAX_HEADER_SIZE) { "maxHeaderSize must be in 1..$MAX_HEADER_SIZE" }
        require(maxEntries > 0 && maxNameBytes > 0 && maxExtraRecords > 0 && maxVolumes > 0)
        require(maxPendingFilters > 0 && maxPendingFilterBytes > 0)
        require(maxKdfLog2 in 1..24) { "maxKdfLog2 must be in 1..24" }
    }

    public companion object {
        /** The largest header a 3-byte size field can describe. */
        public const val MAX_HEADER_SIZE: Int = (1 shl 21) - 1
    }
}

/**
 * [cancellation] is called regularly while the reader works; whatever it throws propagates unchanged.
 * With [listOnly], entry data is skipped without decoding and `entryStream()` is unavailable.
 */
public data class RarOptions(
    val passwordProvider: RarPasswordProvider? = null,
    val volumeProvider: RarVolumeProvider? = null,
    val limits: RarLimits = RarLimits(),
    val memoryBudget: RarMemoryBudget = RarMemoryBudget.UNLIMITED,
    val listOnly: Boolean = false,
    val cancellation: () -> Unit = {},
)

public sealed class RarException(message: String, cause: Throwable? = null) : IOException(message, cause)

public class RarPasswordRequiredException(
    public val scope: RarPasswordScope,
    public val ordinal: Int?,
    public val entryName: String?,
) : RarException(
    if (scope == RarPasswordScope.HEADERS) "Archive headers are encrypted" else "Entry $ordinal ($entryName) is encrypted",
)

public class RarWrongPasswordException(
    public val scope: RarPasswordScope,
    public val ordinal: Int?,
    public val entryName: String?,
    /** False when the archive stores no password check and the failure is only likely. */
    public val certain: Boolean,
) : RarException(
    if (scope == RarPasswordScope.HEADERS) "Wrong password for archive headers" else "Wrong password for entry $ordinal ($entryName)",
)

public class RarUnsupportedException(
    public val reason: RarUnsupportedReason,
    message: String = reason.toString(),
) : RarException(message)

public sealed class RarUnsupportedReason {
    public data object Rar4 : RarUnsupportedReason()

    public data class AlgorithmVersion(val version: Int) : RarUnsupportedReason()

    public data class DictionaryTooLarge(val required: Long, val limit: Long) : RarUnsupportedReason()

    public data class UnknownMethod(val method: Int) : RarUnsupportedReason()

    /** A filter [type] beyond the four RAR5 defines, met in an entry's compressed data. */
    public data class UnknownFilter(val type: Int) : RarUnsupportedReason()

    /** The stream given as the first volume is volume [volumeNumber] (0-based) of a set. */
    public data class NotFirstVolume(val volumeNumber: Int) : RarUnsupportedReason()

    /** An encryption algorithm other than AES-256 (0), for the archive headers or an entry. */
    public data class UnsupportedEncryption(val algorithm: Long) : RarUnsupportedReason()

    /** A [RarLimits] value other than the window size was exceeded. */
    public data class LimitExceeded(val limit: String, val value: Long, val max: Long) : RarUnsupportedReason()
}

public class RarMissingVolumeException(public val volumeIndex: Int) : RarException("Volume $volumeIndex is missing")

public class RarCorruptException(message: String, cause: Throwable? = null) : RarException(message, cause)
