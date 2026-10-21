package eu.darken.butler.common.files.archive

import eu.darken.butler.common.files.APath
import eu.darken.butler.common.files.archive.ArchiveHandlerContext.Companion.COPY_BUFFER_SIZE
import eu.darken.butler.common.files.archive.ArchiveHandlerContext.Companion.MAX_ENTRIES
import eu.darken.butler.common.files.archive.ArchiveHandlerContext.Companion.SPECIAL_UNIX_TYPES
import eu.darken.butler.common.files.archive.ArchiveHandlerContext.Companion.UNIX_TYPE_MASK
import eu.darken.butler.common.files.archive.ArchiveHandlerContext.Companion.VERIFY_DRAIN_LIMIT
import eu.darken.butler.common.files.errors.PathException
import eu.darken.butler.common.files.errors.ReadException
import eu.darken.rar.RarCorruptException
import eu.darken.rar.RarException
import eu.darken.rar.RarHostOs
import eu.darken.rar.RarLimits
import eu.darken.rar.RarMemoryBudget
import eu.darken.rar.RarMissingVolumeException
import eu.darken.rar.RarOptions
import eu.darken.rar.RarPasswordProvider
import eu.darken.rar.RarPasswordRequest
import eu.darken.rar.RarPasswordRequiredException
import eu.darken.rar.RarReader
import eu.darken.rar.RarRedirectionType
import eu.darken.rar.RarUnsupportedException
import eu.darken.rar.RarUnsupportedReason
import eu.darken.rar.RarVolumeNames
import eu.darken.rar.RarWrongPasswordException
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import java.io.Closeable
import java.io.FilterInputStream
import java.io.IOException
import java.io.InputStream
import java.util.concurrent.TimeUnit
import java.util.concurrent.locks.ReentrantLock
import kotlin.concurrent.withLock

/**
 * RAR5, read-only. Every read is one pass from the start of the container stream, so stream-only
 * storage works. Only the first volume of a multi-part archive is read.
 */
internal class RarArchiveHandler(
    private val context: ArchiveHandlerContext,
    windowBudgetBytes: Long = defaultWindowBudgetBytes(),
) : ArchiveHandler {

    private val passwordStore: ArchivePasswordStore get() = context.passwordStore

    /** Shared by every reader of this handler, which lives as long as the singleton service. */
    internal val windowBudget = WindowBudget(windowBudgetBytes)

    private val limits = RarLimits(maxWindowBytes = windowBudgetBytes)

    override val capabilities: ArchiveCapabilities = CAPABILITIES

    override val mayDecryptUnflaggedEntries: Boolean = false

    override val writer: ArchiveWriter? = null

    override val sequentialExtractor: SequentialExtractor? = null

    override suspend fun scan(container: APath<*>, format: ArchiveFormat): ScanResult {
        var skippedUnsafe = 0
        var skippedSpecial = 0
        val metas = ArrayList<ArchiveEntryMeta>()
        val headerEncrypted: Boolean
        val generation: Int
        openSession(container, listOnly = true).use { session ->
            headerEncrypted = session.reader.archive.headerEncrypted
            generation = session.generation
            while (true) {
                currentCoroutineContext().ensureActive()
                val entry = session.rar { session.reader.nextEntry() } ?: break
                if (entry.ordinal >= MAX_ENTRIES) {
                    throw ReadException("Archive has too many entries (limit $MAX_ENTRIES)", container)
                }
                val redirection = entry.redirection
                val isLink = when (redirection?.type) {
                    null -> false
                    RarRedirectionType.UNIX_SYMLINK, RarRedirectionType.WINDOWS_SYMLINK, RarRedirectionType.JUNCTION -> true
                    // Their data is the referenced file's, which the reader never delivers for them.
                    RarRedirectionType.HARDLINK, RarRedirectionType.FILE_COPY, RarRedirectionType.UNKNOWN -> {
                        skippedSpecial++
                        continue
                    }
                }
                val unixType = (entry.attributes and UNIX_TYPE_MASK.toLong()).toInt()
                if (entry.hostOs == RarHostOs.UNIX && unixType in SPECIAL_UNIX_TYPES) {
                    skippedSpecial++
                    continue
                }
                val segments = ArchiveEntrySafety.parseEntryName(entry.name)
                if (segments == null) {
                    skippedUnsafe++
                    continue
                }
                val isDirectory = entry.isDirectory && !isLink
                metas += ArchiveEntryMeta(
                    segments = segments,
                    rawName = entry.name,
                    isDirectory = isDirectory,
                    size = entry.size.takeIf { !isDirectory && !isLink },
                    modifiedAt = entry.mtime,
                    isEncrypted = entry.isEncrypted,
                    isSymlink = isLink,
                    linkTarget = redirection?.target?.takeIf { isLink && it.isNotEmpty() },
                    ordinal = entry.ordinal,
                )
            }
        }
        return ScanResult(
            entries = metas,
            skippedUnsafe = skippedUnsafe,
            skippedSpecial = skippedSpecial,
            headerEncrypted = headerEncrypted,
            passwordGeneration = generation.takeIf { headerEncrypted },
        )
    }

    override suspend fun openEntry(container: APath<*>, index: ArchiveIndex, meta: ArchiveEntryMeta): InputStream {
        val ordinal = meta.ordinal ?: throw ReadException("Entry has no RAR position: ${meta.rawName}", container)
        val session = openSession(container, listOnly = false)
        try {
            session.seek(ordinal, meta)
            // Derives the key and reserves the window, so both wait on this coroutine's cancellation.
            val stream = session.rar { session.reader.entryStream() }
            session.detach()
            return GuardedStream(stream, session, ownsSession = true)
        } catch (e: Throwable) {
            runCatching { session.close() }
            throw e
        }
    }

    /** One pass in archive order, matched by ordinal, the only identity that survives duplicate names. */
    override suspend fun useEntryStreams(
        container: APath<*>,
        index: ArchiveIndex,
        entries: Collection<ArchiveEntryMeta>,
        action: suspend (ArchiveEntryMeta, InputStream) -> Unit,
    ) {
        val wanted = entries.associateBy {
            it.ordinal ?: throw ReadException("Entry has no RAR position: ${it.rawName}", container)
        }.toMutableMap()
        if (wanted.isEmpty()) return
        openSession(container, listOnly = false).use { session ->
            while (wanted.isNotEmpty()) {
                currentCoroutineContext().ensureActive()
                val entry = session.rar { session.reader.nextEntry() } ?: break
                val meta = wanted.remove(entry.ordinal) ?: continue
                if (entry.name != meta.rawName) {
                    throw ReadException("Entry vanished from archive: ${meta.rawName}", container)
                }
                val stream = session.rar { session.reader.entryStream() }
                action(meta, GuardedStream(stream, session, ownsSession = false))
            }
        }
        if (wanted.isNotEmpty()) {
            throw ReadException("Entries vanished from archive: ${wanted.values.take(3).map { it.rawName }}", container)
        }
    }

    /**
     * Encrypted headers are checked when the reader opens. Otherwise the first encrypted entry with
     * data is read up to [VERIFY_DRAIN_LIMIT]: its check value, or failing data, disproves a wrong
     * password. Never evicts; a failure that is only likely a wrong password is thrown as one.
     */
    override suspend fun verifyPassword(
        container: APath<*>,
        password: CharArray,
        index: suspend () -> ArchiveIndex,
    ): Boolean {
        val caller = CallerJob(currentCoroutineContext()[Job])
        val provider = RarPasswordProvider { password.copyOf() }
        try {
            openReader(container, provider, caller, listOnly = false).use { reader ->
                if (reader.archive.headerEncrypted) return true
                while (true) {
                    currentCoroutineContext().ensureActive()
                    val entry = reader.nextEntry() ?: return true
                    if (!entry.isEncrypted || entry.isDirectory || entry.redirection != null || entry.size == 0L) continue
                    reader.entryStream().use { stream ->
                        val buffer = ByteArray(COPY_BUFFER_SIZE)
                        var total = 0L
                        while (total < VERIFY_DRAIN_LIMIT) {
                            currentCoroutineContext().ensureActive()
                            val read = stream.read(buffer)
                            if (read == -1) break
                            total += read
                        }
                    }
                    return true
                }
            }
        } catch (e: Exception) {
            val failure = e.unwrapRarFailure()
            if (failure !is RarWrongPasswordException) throw mapRarError(e, container, passwordStore.generation(container))
            if (failure.certain) return false
            throw ArchivePasswordRequiredException(container, attemptFailed = true, mayBeDamaged = true, cause = failure)
        }
    }

    private suspend fun openSession(container: APath<*>, listOnly: Boolean): Session {
        val password = SnapshotPassword(passwordStore.snapshot(container))
        val caller = CallerJob(currentCoroutineContext()[Job])
        val reader = try {
            openReader(container, password, caller, listOnly)
        } catch (e: Exception) {
            password.wipe()
            throw mapRarError(e, container, password.generation)
        }
        return Session(container, reader, password, caller)
    }

    /** The reader owns the container stream and closes it, also when its constructor throws. */
    private suspend fun openReader(
        container: APath<*>,
        password: RarPasswordProvider,
        caller: CallerJob,
        listOnly: Boolean,
    ): RarReader {
        val budget = object : RarMemoryBudget {
            override fun acquire(bytes: Long) = windowBudget.acquire(bytes, caller::check)
            override fun release(bytes: Long) = windowBudget.release(bytes)
        }
        return RarReader(
            context.gatewaySwitch.openInputStream(container),
            RarOptions(
                passwordProvider = password,
                limits = limits,
                memoryBudget = budget,
                listOnly = listOnly,
                cancellation = caller::check,
            ),
        )
    }

    private suspend fun Session.seek(ordinal: Int, meta: ArchiveEntryMeta) {
        while (true) {
            currentCoroutineContext().ensureActive()
            val entry = rar { reader.nextEntry() } ?: break
            if (entry.ordinal < ordinal) continue
            if (entry.ordinal == ordinal && entry.name == meta.rawName) return
            break
        }
        throw ReadException("Entry vanished from archive: ${meta.rawName}", container)
    }

    /**
     * A wrong password evicts the cached one if it is still the one the reader was opened with
     * ([generation]); without a check value in the archive the failure may as well be damage.
     */
    private fun mapRarError(e: Throwable, container: APath<*>, generation: Int): Throwable {
        if (e is CancellationException || e is PathException) return e
        return when (val failure = e.unwrapRarFailure()) {
            is CancellationException -> failure
            is RarPasswordRequiredException -> ArchivePasswordRequiredException(container, cause = failure)
            is RarWrongPasswordException -> {
                passwordStore.evictIfGeneration(container, generation)
                ArchivePasswordRequiredException(
                    container,
                    attemptFailed = true,
                    mayBeDamaged = !failure.certain,
                    cause = failure,
                )
            }
            is RarCorruptException -> ArchiveDamagedException(container, failure)
            is RarUnsupportedException -> ArchiveUnsupportedException(container, failure.reason.toReason(), failure)
            is RarMissingVolumeException -> ArchiveMissingPartException(
                container = container,
                partName = RarVolumeNames.volumeName(container.name, failure.volumeIndex),
                cause = failure,
            )
            else -> e
        }
    }

    /** A failed reader rethrows its first failure wrapped in a plain IOException on every later call. */
    private fun Throwable.unwrapRarFailure(): Throwable {
        val cause = cause
        return if (this !is RarException && this is IOException && (cause is RarException || cause is CancellationException)) cause else this
    }

    private fun RarUnsupportedReason.toReason(): ArchiveUnsupportedException.Reason = when (this) {
        RarUnsupportedReason.Rar4 -> ArchiveUnsupportedException.Reason.Rar4
        is RarUnsupportedReason.DictionaryTooLarge -> ArchiveUnsupportedException.Reason.DictionaryTooLarge(required, limit)
        // lib-rar counts volumes from 0.
        is RarUnsupportedReason.NotFirstVolume -> ArchiveUnsupportedException.Reason.NotFirstPart(volumeNumber + 1)
        is RarUnsupportedReason.AlgorithmVersion,
        is RarUnsupportedReason.UnknownMethod,
        is RarUnsupportedReason.UnknownFilter,
        is RarUnsupportedReason.UnsupportedEncryption,
        is RarUnsupportedReason.LimitExceeded -> ArchiveUnsupportedException.Reason.Feature
    }

    /**
     * The coroutine a reader works for. [job] is null while no coroutine waits on the reader, i.e.
     * between the reads of a stream returned by [openEntry]: the call that opened it has completed.
     */
    private class CallerJob(@Volatile var job: Job?) {
        fun check() {
            job?.ensureActive()
        }
    }

    /** The cached password a reader was opened with, handed out as copies the reader wipes. */
    private class SnapshotPassword(snapshot: ArchivePasswordStore.Snapshot) : RarPasswordProvider {
        val generation: Int = snapshot.generation

        @Volatile private var password: CharArray? = snapshot.password

        override fun password(request: RarPasswordRequest): CharArray? = password?.copyOf()

        fun wipe() {
            password?.fill(Char.MIN_VALUE)
            password = null
        }
    }

    private inner class Session(
        val container: APath<*>,
        val reader: RarReader,
        private val password: SnapshotPassword,
        private val caller: CallerJob,
    ) : Closeable {
        val generation: Int get() = password.generation

        inline fun <T> rar(block: () -> T): T = try {
            block()
        } catch (e: Exception) {
            throw mapRarError(e, container, generation)
        }

        /** For a stream that outlives the call that opened it: no more passwords, no caller to cancel. */
        fun detach() {
            password.wipe()
            caller.job = null
        }

        override fun close() {
            password.wipe()
            reader.close()
        }
    }

    private inner class GuardedStream(
        stream: InputStream,
        private val session: Session,
        private val ownsSession: Boolean,
    ) : FilterInputStream(stream) {
        override fun read(): Int = session.rar { super.read() }
        override fun read(b: ByteArray, off: Int, len: Int): Int = session.rar { super.read(b, off, len) }
        override fun skip(n: Long): Long = session.rar { super.skip(n) }

        override fun close() {
            try {
                super.close()
            } finally {
                if (ownsSession) runCatching { session.close() }
            }
        }
    }

    /**
     * Window memory shared by all readers. [acquire] waits while too little is free, re-checking the
     * caller's cancellation every [POLL_MILLIS], and has no timeout: a read paused on a prompt keeps
     * its reservation.
     */
    internal class WindowBudget(val totalBytes: Long) {
        private val lock = ReentrantLock()
        private val released = lock.newCondition()
        private var free = totalBytes
        private var waiters = 0

        val waiting: Int get() = lock.withLock { waiters }

        fun acquire(bytes: Long, checkCancelled: () -> Unit) {
            check(bytes <= totalBytes) { "Reservation of $bytes bytes exceeds the budget of $totalBytes" }
            lock.withLock {
                if (free < bytes) {
                    waiters++
                    try {
                        while (free < bytes) {
                            checkCancelled()
                            released.await(POLL_MILLIS, TimeUnit.MILLISECONDS)
                        }
                    } finally {
                        waiters--
                    }
                }
                free -= bytes
            }
        }

        fun release(bytes: Long) = lock.withLock {
            free += bytes
            released.signalAll()
        }

        private companion object {
            const val POLL_MILLIS = 100L
        }
    }

    companion object {
        val CAPABILITIES = ArchiveCapabilities(
            access = ArchiveCapabilities.Access.SEQUENTIAL,
            sequentialExtractionFallback = false,
            readEncryption = setOf(
                ArchiveCapabilities.EncryptionScope.ENTRY_DATA,
                ArchiveCapabilities.EncryptionScope.HEADERS,
            ),
            wrongPasswordDistinguishable = true,
            writable = false,
            writeEncryption = false,
        )
        private const val WINDOW_CHUNK_BYTES = 256L * 1024
        private const val MAX_WINDOW_BUDGET_BYTES = 256L * 1024 * 1024

        /**
         * A quarter of the heap, at most 256 MiB, in whole 256 KiB chunks: lib-rar rounds reservations
         * up to whole chunks, so any reservation up to the window limit then fits the budget.
         */
        fun defaultWindowBudgetBytes(): Long {
            val quarter = (Runtime.getRuntime().maxMemory() / 4).coerceAtMost(MAX_WINDOW_BUDGET_BYTES)
            return (quarter / WINDOW_CHUNK_BYTES * WINDOW_CHUNK_BYTES).coerceAtLeast(WINDOW_CHUNK_BYTES)
        }
    }
}
