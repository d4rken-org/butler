package eu.darken.rar

import java.io.ByteArrayInputStream
import java.lang.management.ManagementFactory

/** Bytes allocated by the current thread, where the JVM reports them. */
internal object Allocation {
    private val threads = ManagementFactory.getThreadMXBean() as? com.sun.management.ThreadMXBean

    fun current(): Long? = threads
        ?.takeIf { it.isThreadAllocatedMemorySupported && it.isThreadAllocatedMemoryEnabled }
        ?.currentThreadAllocatedBytes

    /** What [block] allocated on its second run, the first one paying for class loading; null if unknown. */
    fun of(block: () -> Unit): Long? {
        block()
        val before = current() ?: return null
        block()
        return current()?.minus(before)
    }
}

/**
 * One reader over a hostile archive, judged by more than its outcome: what it reserved from the
 * memory budget and what it allocated, measured against [LIMITS], and whether it closed every stream.
 */
internal object SweepCheck {
    /** Tight limits that every sweep subject fits. */
    val LIMITS = RarLimits(
        maxWindowBytes = 1L shl 20,
        maxHeaderSize = 64 * 1024,
        maxPendingFilterBytes = 1L shl 20,
        maxKdfLog2 = 16,
    )

    /**
     * What one run may allocate besides key derivation (PBKDF2 allocates per iteration). Under
     * [LIMITS] a run needs a few hundred KiB; an allocation sized by a declared 32-bit field does not fit.
     */
    const val ALLOCATION_BOUND = 32L shl 20

    class Result(val outcome: Throwable?, val violations: List<String>)

    /** Fails a run that reserves more than [limit] at once, reserves twice without releasing, or ends holding anything. */
    private class CheckedBudget(private val limit: Long) : RarMemoryBudget {
        var balance = 0L
        val violations = mutableListOf<String>()

        override fun acquire(bytes: Long) {
            if (bytes <= 0 || bytes > limit) violations += "reserved $bytes bytes, limit $limit"
            if (balance != 0L) violations += "reserved $bytes bytes while holding $balance"
            balance += bytes
        }

        override fun release(bytes: Long) {
            balance -= bytes
        }
    }

    /** [MemoizedDerivation], counting what its derivations allocate so a run can leave it out. */
    private class MeasuredDerivation : Rar5KeyDerivation {
        var allocated = 0L

        override fun derive(password: ByteArray, salt: ByteArray, log2Count: Int, cancellation: () -> Unit): Rar5Keys {
            val before = Allocation.current()
            try {
                return MemoizedDerivation.derive(password, salt, log2Count, cancellation)
            } finally {
                if (before != null) allocated += Allocation.current()!! - before
            }
        }
    }

    /**
     * Walks [first] in [mode] with [options] (memory budget and `listOnly` are replaced); [volumes]
     * must be the options' volume provider, if any, so its streams can be checked.
     */
    fun run(first: ByteArray, mode: ReadMode, options: RarOptions = RarOptions(limits = LIMITS), volumes: TestVolumes? = null): Result {
        val budget = CheckedBudget(options.limits.maxWindowBytes)
        val derivation = MeasuredDerivation()
        val stream = TrackingInputStream(ByteArrayInputStream(first))
        val configured = options.copy(memoryBudget = budget).forMode(mode)
        val buffer = ByteArray(8192)
        val before = Allocation.current()
        val outcome = try {
            RarReader(stream, configured, Rar5Window.DEFAULT_CHUNK_SIZE, derivation).use { HostileRun.drive(it, mode, buffer) }
            null
        } catch (t: Throwable) {
            t
        }
        val allocated = before?.let { Allocation.current()!! - it - derivation.allocated }
        val violations = buildList {
            if (outcome != null && outcome !is RarException) add("foreign ${outcome::class.java.name}: ${outcome.message}")
            if (!stream.closed || volumes?.allClosed == false) add("a stream was left open")
            addAll(budget.violations)
            if (budget.balance != 0L) add("${budget.balance} bytes still reserved after close")
            if (allocated != null && allocated > ALLOCATION_BOUND) add("allocated $allocated bytes")
        }
        return Result(outcome, violations)
    }

    /** Running out of archive data is corrupt; with a header password nothing proved yet, a likely wrong one. */
    fun isPrematureEnd(outcome: Throwable?): Boolean = outcome is RarCorruptException ||
        (outcome is RarWrongPasswordException && !outcome.certain && outcome.suppressed.any { it is RarCorruptException })

    /**
     * Applies [check] to every input and fails with the first ten problems it reports; [check] returns
     * null for an acceptable run.
     */
    fun assertAll(label: String, inputs: Sequence<Pair<String, ByteArray>>, check: (ByteArray) -> String?) {
        val failures = inputs.mapNotNull { (what, bytes) -> check(bytes)?.let { "$what: $it" } }.take(10).toList()
        if (failures.isNotEmpty()) throw AssertionError("$label:\n${failures.joinToString("\n")}")
    }

    fun truncations(bytes: ByteArray): Sequence<Pair<String, ByteArray>> =
        (0 until bytes.size).asSequence().map { "length $it" to bytes.copyOf(it) }

    fun bitFlips(bytes: ByteArray): Sequence<Pair<String, ByteArray>> =
        (0 until bytes.size * 8).asSequence().map { bit -> "bit $bit" to flip(bytes, bit) }

    fun flip(bytes: ByteArray, bit: Int): ByteArray =
        bytes.copyOf().also { it[bit / 8] = (it[bit / 8].toInt() xor (1 shl (bit % 8))).toByte() }

    /** A run's problems; for a truncation shorter than [end], also an outcome other than a premature end. */
    fun judge(result: Result, truncatedTo: Int? = null, end: Int = Int.MAX_VALUE): String? {
        val problems = result.violations.toMutableList()
        if (truncatedTo != null && truncatedTo < end && !isPrematureEnd(result.outcome)) {
            problems += "a truncation ended with ${result.outcome?.let { it::class.java.simpleName } ?: "success"}"
        }
        if (problems.isEmpty()) return null
        result.outcome?.let { problems += "outcome ${it::class.java.simpleName}: ${it.message}" }
        return problems.joinToString("; ")
    }
}
