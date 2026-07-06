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
    private var searchJob: Job? = null

    data class SearchUiState(
        val queryInput: TextFieldValue = TextFieldValue(""),
        val currentResultIndex: Int = 0,
        val caseSensitive: Boolean = false,
        val regexEnabled: Boolean = false,
        val wholeWord: Boolean = false,
        val scrollTrigger: Int = 0,
        val showSearchBar: Boolean = false,
    )

    val state: Flow<SearchUiState> = combine(
        _queryInput,
        _currentResultIndex,
        _caseSensitive,
        _regexEnabled,
        _wholeWord,
        _scrollTrigger,
        _showSearchBar,
    ) { values ->
        SearchUiState(
            queryInput = values[0] as TextFieldValue,
            currentResultIndex = values[1] as Int,
            caseSensitive = values[2] as Boolean,
            regexEnabled = values[3] as Boolean,
            wholeWord = values[4] as Boolean,
            scrollTrigger = values[5] as Int,
            showSearchBar = values[6] as Boolean,
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
                val result = workspace().search(query, buildSearchOptions())
                result.onSuccess { searchResults ->
                    // Auto-navigate to first result if available
                    _currentResultIndex.value = 0
                    if (searchResults.isNotEmpty()) {
                        workspace().setCursorPosition(searchResults[0].position)
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
        val query = _queryInput.value.text
        if (query.isNotEmpty()) {
            search(query)
        }
    }

    fun showSearchBar() {
        _showSearchBar.value = true
    }

    fun closeSearch() {
        _queryInput.value = TextFieldValue("")
        _showSearchBar.value = false
        // Clear via the tracked job so it cannot race a still-running scan
        search("")
    }

    companion object {
        internal const val SEARCH_DEBOUNCE_MS = 200L
    }
}
