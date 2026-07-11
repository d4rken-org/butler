package eu.darken.butler.editor.core.syntax

import eu.darken.butler.common.coroutine.DispatcherProvider
import eu.darken.butler.common.debug.logging.Logging.Priority.*
import eu.darken.butler.common.debug.logging.asLog
import eu.darken.butler.common.debug.logging.log
import eu.darken.butler.common.debug.logging.logTag
import eu.darken.butler.editor.core.engine.DocumentBuffer
import eu.darken.butler.editor.core.engine.EditorEngine
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.mapLatest
import kotlinx.coroutines.flow.onStart
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.util.TreeMap

/**
 * Produces per-line syntax tokens for the visible window, computed OFF the text-display path:
 * text always renders first, color arrives via a later emission. Nothing here may ever be awaited
 * by the content pipeline (engine window loads, typing echo) - highlighting is a progressive
 * enhancement, not a precondition.
 *
 * Cross-line state (block comments, fenced code, heredocs) is resolved for the window's first
 * line via a checkpoint map plus bounded lookback through the buffer:
 * - Checkpoints ([checkpoints]: line -> state BEFORE that line) are recorded by lookback scans
 *   every [CHECKPOINT_INTERVAL] lines and invalidated wholesale when the buffer's structural
 *   version changes. Sequential scrolling therefore stays exact indefinitely; only a cold jump
 *   more than [LOOKBACK_LINES] past the last scanned region starts from an assumed-Default state
 *   (wrong only when a construct spans more than that above the window - self-corrects once the
 *   opener is scanned).
 * - Lookback reads go through [DocumentBuffer.getDisplayRange] (per-line capped) in
 *   [SCAN_CHUNK_LINES]-line chunks so the buffer lock is never held across a long scan, with the
 *   structural version validated per chunk (search() precedent) - a concurrent edit aborts the
 *   scan instead of committing mixed-version state.
 *
 * Accepted inaccuracies (regression-tested): lines capped at the display limit contribute their
 * visible prefix's end state (a delimiter past the cap is missed); mid-line-anchored window
 * slices (startColumn > 0) are not tokenized and pass state through unchanged.
 */
class EditorHighlighter(
    private val language: Language?,
    enabled: Flow<Boolean>,
    visibleContent: Flow<EditorEngine.VisibleContent>,
    visibleRange: Flow<LongRange>,
    structuralVersion: Flow<Long>,
    private val bufferProvider: () -> DocumentBuffer?,
    dispatcherProvider: DispatcherProvider,
) {

    private data class MemoKey(val line: String, val state: LineState)

    /**
     * Guards both caches: the flow below is cold, so a second collector (workspace state is
     * consumed by more than one subscriber) runs a concurrent transform over the same instance.
     */
    private val cacheMutex = Mutex()
    private val tokenMemo = object : LinkedHashMap<MemoKey, TokenizeResult>(64, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<MemoKey, TokenizeResult>?) =
            size > MEMO_MAX_ENTRIES
    }
    private val checkpoints = TreeMap<Long, LineState>()
    private var checkpointsVersion = Long.MIN_VALUE

    val highlightedLines: Flow<Map<Long, List<Token>>> = when (language) {
        null -> flowOf(emptyMap())
        else -> {
            val tokenizer = SyntaxHighlighting.tokenizerFor(language)
            combine(enabled, visibleContent, visibleRange, structuralVersion) { isEnabled, content, range, _ ->
                if (isEnabled) content to range else null
            }
                .mapLatest { input ->
                    if (input == null) return@mapLatest emptyMap()
                    val buffer = bufferProvider() ?: return@mapLatest emptyMap()
                    try {
                        computeWindowTokens(buffer, tokenizer, input.first, input.second)
                    } catch (e: CancellationException) {
                        throw e
                    } catch (e: Exception) {
                        log(TAG, WARN) { "Highlighting failed for window ${input.second} - ${e.asLog()}" }
                        emptyMap()
                    }
                }
                .flowOn(dispatcherProvider.Default)
                // The workspace state combine must never wait on highlighting for its first
                // emission - text first, color later.
                .onStart { emit(emptyMap()) }
        }
    }

    private suspend fun computeWindowTokens(
        buffer: DocumentBuffer,
        tokenizer: SyntaxTokenizer,
        content: EditorEngine.VisibleContent,
        range: LongRange,
    ): Map<Long, List<Token>> = cacheMutex.withLock {
        var state = resolveStartState(buffer, tokenizer, range.first)
        val result = HashMap<Long, List<Token>>()
        content.text.split('\n').forEachIndexed { index, line ->
            val lineNumber = range.first + index
            if ((content.startColumns[lineNumber] ?: 0L) > 0L) {
                // Mid-line-anchored slice: token offsets would be meaningless against a substring
                // that doesn't start at the real line start - rendered plain, state passed through.
                return@forEachIndexed
            }
            val tokenized = tokenizeMemoized(tokenizer, line, state)
            if (tokenized.tokens.isNotEmpty()) result[lineNumber] = tokenized.tokens
            state = tokenized.endState
        }
        result
    }

    /** State BEFORE [windowStart], from checkpoints + bounded chunked lookback. Never throws. */
    private suspend fun resolveStartState(
        buffer: DocumentBuffer,
        tokenizer: SyntaxTokenizer,
        windowStart: Long,
    ): LineState {
        if (windowStart <= 0L) return LineState.Default

        val version = buffer.getStructuralVersion()
        if (version != checkpointsVersion) {
            checkpoints.clear()
            checkpointsVersion = version
        }
        checkpoints[windowStart]?.let { return it }

        val floor = checkpoints.floorEntry(windowStart)
        val scanStart: Long
        var state: LineState
        if (floor != null && windowStart - floor.key <= MAX_CHECKPOINT_SCAN) {
            scanStart = floor.key
            state = floor.value
        } else {
            // Cold jump: assume Default a bounded distance up. Wrong only for constructs spanning
            // more than LOOKBACK_LINES above the window; corrected once the opener gets scanned.
            scanStart = (windowStart - LOOKBACK_LINES).coerceAtLeast(0L)
            state = LineState.Default
        }

        // Chunked scan: the buffer lock is taken per chunk (never across the whole lookback, so
        // typing/display reads interleave), and a mid-scan mutation aborts without committing.
        val newCheckpoints = mutableListOf<Pair<Long, LineState>>()
        var chunkStart = scanStart
        while (chunkStart < windowStart) {
            val chunkEnd = minOf(chunkStart + SCAN_CHUNK_LINES - 1, windowStart - 1)
            val chunk = buffer.getDisplayRange(chunkStart, chunkEnd).getOrNull() ?: return LineState.Default
            chunk.text.split('\n').forEachIndexed { index, line ->
                val lineNumber = chunkStart + index
                if (lineNumber % CHECKPOINT_INTERVAL == 0L) newCheckpoints += lineNumber to state
                state = tokenizeMemoized(tokenizer, line, state).endState
            }
            if (buffer.getStructuralVersion() != version) return state // stale: don't commit
            chunkStart = chunkEnd + 1
        }

        if (checkpoints.size > MAX_CHECKPOINTS) checkpoints.clear()
        newCheckpoints.forEach { (line, lineState) -> checkpoints[line] = lineState }
        checkpoints[windowStart] = state
        return state
    }

    private fun tokenizeMemoized(tokenizer: SyntaxTokenizer, line: String, state: LineState): TokenizeResult {
        if (line.length > MEMO_MAX_LINE_CHARS) return tokenizer.tokenize(line, state)
        val key = MemoKey(line, state)
        tokenMemo[key]?.let { return it }
        return tokenizer.tokenize(line, state).also { tokenMemo[key] = it }
    }

    companion object {
        private val TAG = logTag("Editor", "Highlighter")

        /** Cold-jump lookback bound: constructs opened further above an unscanned window are missed. */
        internal const val LOOKBACK_LINES = 400L

        /** From a known checkpoint the scan is exact, so a larger catch-up allowance is safe. */
        private const val MAX_CHECKPOINT_SCAN = 2_000L

        /** Lines materialized per buffer-lock acquisition during lookback. */
        private const val SCAN_CHUNK_LINES = 100L

        private const val CHECKPOINT_INTERVAL = 100L
        private const val MAX_CHECKPOINTS = 10_000

        /** Lines longer than this skip the memo (needlessly pins big strings; tokenizing is cheap). */
        private const val MEMO_MAX_LINE_CHARS = 2_000

        private const val MEMO_MAX_ENTRIES = 512
    }
}
