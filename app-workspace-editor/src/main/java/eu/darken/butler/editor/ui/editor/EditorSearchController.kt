package eu.darken.butler.editor.ui.editor

import androidx.compose.ui.text.input.TextFieldValue
import eu.darken.butler.common.debug.logging.Logging.Priority.ERROR
import eu.darken.butler.common.debug.logging.asLog
import eu.darken.butler.common.debug.logging.log
import eu.darken.butler.editor.core.EditorWorkspace
import eu.darken.butler.editor.core.engine.SearchOptions
import eu.darken.butler.editor.core.engine.SearchResult
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.launch

/**
 * Owns the search UI state and the single tracked search job. Extracted from the ViewModel so
 * debounce/cancellation/wraparound logic is testable without Hilt or the workspace framework.
 */
class EditorSearchController(
    private val scope: CoroutineScope,
    private val doLaunch: (suspend CoroutineScope.() -> Unit) -> Unit,
    private val workspace: suspend () -> EditorWorkspace,
    private val tag: String,
) {

    private val _queryInput = MutableStateFlow(TextFieldValue(""))
    private val _currentResultIndex = MutableStateFlow(0)
    private val _caseSensitive = MutableStateFlow(false)
    private val _regexEnabled = MutableStateFlow(false)
    private val _wholeWord = MutableStateFlow(false)
    private val _scrollTrigger = MutableStateFlow(0)
    private val _showSearchBar = MutableStateFlow(false)
    private val _replaceQueryInput = MutableStateFlow(TextFieldValue(""))
    private val _showReplaceRow = MutableStateFlow(false)
    private val _replaceNotice = MutableStateFlow<ReplaceNotice?>(null)
    private var searchJob: Job? = null

    // The query/options that produced the CURRENTLY published results. Replace operations use
    // these, never the live input field - the user may have typed a new query whose search
    // hasn't run yet, and replacing against mixed query/results would mutate the wrong match.
    private var activeQuery: String = ""
    private var activeOptions: SearchOptions = SearchOptions()

    /** Outcome of the last replace-all, shown transiently in the search bar. */
    data class ReplaceNotice(
        val count: Int,
        val undoable: Boolean,
    )

    data class SearchUiState(
        val queryInput: TextFieldValue = TextFieldValue(""),
        val currentResultIndex: Int = 0,
        val caseSensitive: Boolean = false,
        val regexEnabled: Boolean = false,
        val wholeWord: Boolean = false,
        val scrollTrigger: Int = 0,
        val showSearchBar: Boolean = false,
        val replaceQueryInput: TextFieldValue = TextFieldValue(""),
        val showReplaceRow: Boolean = false,
        val replaceNotice: ReplaceNotice? = null,
    )

    val state: Flow<SearchUiState> = combine(
        _queryInput,
        _currentResultIndex,
        _caseSensitive,
        _regexEnabled,
        _wholeWord,
        _scrollTrigger,
        _showSearchBar,
        _replaceQueryInput,
        _showReplaceRow,
        _replaceNotice,
    ) { values ->
        SearchUiState(
            queryInput = values[0] as TextFieldValue,
            currentResultIndex = values[1] as Int,
            caseSensitive = values[2] as Boolean,
            regexEnabled = values[3] as Boolean,
            wholeWord = values[4] as Boolean,
            scrollTrigger = values[5] as Int,
            showSearchBar = values[6] as Boolean,
            replaceQueryInput = values[7] as TextFieldValue,
            showReplaceRow = values[8] as Boolean,
            replaceNotice = values[9] as ReplaceNotice?,
        )
    }

    private fun buildSearchOptions() = SearchOptions(
        caseSensitive = _caseSensitive.value,
        useRegex = _regexEnabled.value,
        wholeWord = _wholeWord.value,
    )

    private suspend fun currentResults(): List<SearchResult> =
        (workspace().state.value as? EditorWorkspace.State.Ready)?.editor?.searchResults ?: emptyList()

    /**
     * One tracked search at a time: a new query cancels the previous scan. Typing debounces
     * so every keystroke doesn't start a whole-document scan; option toggles re-search
     * immediately (deliberate single action).
     */
    private fun search(query: String, debounce: Boolean = false) {
        searchJob?.cancel()
        searchJob = scope.launch {
            try {
                if (debounce) delay(SEARCH_DEBOUNCE_MS)
                val options = buildSearchOptions()
                val result = workspace().search(query, options)
                result.onSuccess { searchResults ->
                    activeQuery = query
                    activeOptions = options
                    if (searchResults.isNotEmpty()) {
                        // Start at the first match at or after the cursor instead of always
                        // jumping back to the document start
                        val cursorOffset = (workspace().state.value as? EditorWorkspace.State.Ready)
                            ?.editor?.cursorPosition?.offset ?: 0L
                        val startIndex = searchResults
                            .indexOfFirst { it.position.offset >= cursorOffset }
                            .let { if (it == -1) 0 else it }
                        _currentResultIndex.value = startIndex
                        workspace().setCursorPosition(searchResults[startIndex].position)
                    } else {
                        _currentResultIndex.value = 0
                    }
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                log(tag, ERROR) { "Search failed - ${e.asLog()}" }
            }
        }
    }

    fun updateQuery(textFieldValue: TextFieldValue) {
        _replaceNotice.value = null
        _queryInput.value = textFieldValue
        val query = textFieldValue.text
        if (query.isNotEmpty()) {
            search(query, debounce = true)
        } else {
            // Clearing goes through the same tracked job - an untracked clear could otherwise
            // race a newer search and purge its results
            search("")
        }
    }

    fun nextResult() = doLaunch {
        val results = currentResults()
        if (results.isNotEmpty()) {
            val newIndex = (_currentResultIndex.value + 1) % results.size
            _currentResultIndex.value = newIndex
            _scrollTrigger.value++
            workspace().setCursorPosition(results[newIndex].position)
        }
    }

    fun previousResult() = doLaunch {
        val results = currentResults()
        if (results.isNotEmpty()) {
            val newIndex = if (_currentResultIndex.value == 0) results.size - 1 else _currentResultIndex.value - 1
            _currentResultIndex.value = newIndex
            _scrollTrigger.value++
            workspace().setCursorPosition(results[newIndex].position)
        }
    }

    fun toggleCaseSensitivity() {
        _caseSensitive.value = !_caseSensitive.value
        reSearchIfActive()
    }

    fun toggleRegexMode() {
        _regexEnabled.value = !_regexEnabled.value
        reSearchIfActive()
    }

    fun toggleWholeWord() {
        _wholeWord.value = !_wholeWord.value
        reSearchIfActive()
    }

    private fun reSearchIfActive() {
        // The outcome notice belongs to the previous result set
        _replaceNotice.value = null
        val query = _queryInput.value.text
        if (query.isNotEmpty()) {
            search(query)
        }
    }

    fun showSearchBar() {
        _showSearchBar.value = true
    }

    fun toggleReplaceRow() {
        _showReplaceRow.value = !_showReplaceRow.value
        _replaceNotice.value = null
    }

    fun updateReplaceQuery(textFieldValue: TextFieldValue) {
        _replaceQueryInput.value = textFieldValue
    }

    fun replaceCurrent() = doLaunch {
        _replaceNotice.value = null
        if (activeQuery.isEmpty()) return@doLaunch
        // Index and results flow independently; a desync means we no longer know which match
        // the user is looking at - do nothing rather than silently replacing another one
        val match = currentResults().getOrNull(_currentResultIndex.value) ?: return@doLaunch

        val outcome = workspace()
            .replaceCurrent(activeQuery, activeOptions, match, _replaceQueryInput.value.text)
            .getOrThrow()

        if (outcome.results.isNotEmpty()) {
            _currentResultIndex.value = outcome.nextIndex
            _scrollTrigger.value++
            workspace().setCursorPosition(outcome.results[outcome.nextIndex].position)
        } else {
            _currentResultIndex.value = 0
        }
    }

    fun replaceAll() = doLaunch {
        _replaceNotice.value = null
        if (activeQuery.isEmpty()) return@doLaunch

        val outcome = workspace()
            .replaceAll(activeQuery, activeOptions, _replaceQueryInput.value.text)
            .getOrThrow()

        _currentResultIndex.value = 0
        _replaceNotice.value = ReplaceNotice(count = outcome.count, undoable = outcome.undoable)
    }

    fun closeSearch() {
        activeQuery = ""
        _queryInput.value = TextFieldValue("")
        _replaceQueryInput.value = TextFieldValue("")
        _showReplaceRow.value = false
        _replaceNotice.value = null
        _showSearchBar.value = false
        // Clear via the tracked job so it cannot race a still-running scan
        search("")
    }

    companion object {
        internal const val SEARCH_DEBOUNCE_MS = 200L
    }
}
