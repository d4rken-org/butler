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
import kotlinx.serialization.json.Json
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.time.Duration.Companion.seconds

@Singleton
class EditorSettings @Inject constructor(
    @ApplicationContext private val context: Context,
    private val json: Json,
) : PreferenceScreenData {

    private val Context.dataStore by preferencesDataStore(name = "settings_editor")

    override val dataStore: DataStore<Preferences>
        get() = context.dataStore

    // Display Settings
    val showLineNumbers = dataStore.createValue("editor.line_numbers.show", true)
    val wordWrap = dataStore.createValue("editor.word_wrap.enabled", false)
    val fontSize = dataStore.createValue("editor.font_size", 14)
    val tabSize = dataStore.createValue("editor.tab_size", 4)

    val syntaxHighlighting = dataStore.createValue("editor.syntax_highlighting.enabled", true)

    val autoSaveInterval = dataStore.createValue("editor.auto_save.interval", 30.seconds, json)
    val autoSaveEnabled = dataStore.createValue("editor.auto_save.enabled", false)

    val undoStackSize = dataStore.createValue("editor.undo.stack_size", 100)
    val undoMaxMemory = dataStore.createValue("editor.undo.memory_max", 10 * 1_048_576L)

    override val mapper = PreferenceStoreMapper(
        showLineNumbers,
        wordWrap,
        fontSize,
        tabSize,
        syntaxHighlighting,

        autoSaveInterval,
        autoSaveEnabled,

        undoStackSize,
        undoMaxMemory,
    )

    companion object {
        internal val TAG = logTag("Editor", "Settings")
    }
}
