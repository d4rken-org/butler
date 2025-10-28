package eu.darken.butler.editor.core

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.preferencesDataStore
import dagger.hilt.android.qualifiers.ApplicationContext
import eu.darken.butler.common.datastore.PreferenceScreenData
import eu.darken.butler.common.datastore.PreferenceStoreMapper
import eu.darken.butler.common.datastore.createValue
import eu.darken.butler.common.debug.logging.logTag
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class EditorSettings @Inject constructor(
    @ApplicationContext private val context: Context,
) : PreferenceScreenData {

    private val Context.dataStore by preferencesDataStore(name = "settings_editor")

    override val dataStore: DataStore<Preferences>
        get() = context.dataStore

    // Display Settings
    val showLineNumbers = dataStore.createValue("editor.line_numbers.show", true)
    val wordWrap = dataStore.createValue("editor.word_wrap.enabled", false)
    val fontSize = dataStore.createValue("editor.font_size", 14)
    val tabSize = dataStore.createValue("editor.tab_size", 4)
    val showWhitespace = dataStore.createValue("editor.whitespace.show", false)

    val autoSaveInterval = dataStore.createValue("editor.auto_save.interval_ms", 30000L) // 30 seconds
    val autoSaveEnabled = dataStore.createValue("editor.auto_save.enabled", false)

    // Search Settings
    val searchCaseSensitive = dataStore.createValue("editor.search.case_sensitive", false)
    val searchRegex = dataStore.createValue("editor.search.regex", false)
    val searchWrapAround = dataStore.createValue("editor.search.wrap_around", true)
    val maxSearchResults = dataStore.createValue("editor.search.max_results", 1000)

    // Editor Behavior
    val autoIndent = dataStore.createValue("editor.auto_indent", true)
    val highlightCurrentLine = dataStore.createValue("editor.highlight.current_line", true)
    val showMatchingBrackets = dataStore.createValue("editor.brackets.show_matching", true)
    val undoStackSize = dataStore.createValue("editor.undo.stack_size", 100)


    override val mapper = PreferenceStoreMapper(
        // Display Settings
        showLineNumbers,
        wordWrap,
        fontSize,
        tabSize,
        showWhitespace,

        // File Handling Settings
        autoSaveInterval,
        autoSaveEnabled,
        
        // Search Settings
        searchCaseSensitive,
        searchRegex,
        searchWrapAround,
        maxSearchResults,
        
        // Editor Behavior
        autoIndent,
        highlightCurrentLine,
        showMatchingBrackets,
        undoStackSize,
    )


    companion object {
        internal val TAG = logTag("Editor", "Settings")
    }
}
