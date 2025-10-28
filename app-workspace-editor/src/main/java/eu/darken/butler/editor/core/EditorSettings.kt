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
import eu.darken.butler.editor.core.engine.ChunkManager
import eu.darken.butler.editor.core.engine.MemoryManager
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

    // Chunk System Settings
    val chunkSize = dataStore.createValue("editor.chunk.size_bytes", ChunkManager.DEFAULT_CHUNK_SIZE)
    val maxMemoryUsage = dataStore.createValue("editor.memory.max_bytes", MemoryManager.DEFAULT_MAX_MEMORY_BYTES)
    val preloadChunks = dataStore.createValue("editor.chunk.preload_count", 3)
    val autoEvictChunks = dataStore.createValue("editor.chunk.auto_evict", true)

    // Performance Settings
    val visibleLineBuffer = dataStore.createValue("editor.visible.line_buffer", 50)
    val backgroundLoading = dataStore.createValue("editor.background.loading", true)
    val memoryPressureThreshold = dataStore.createValue("editor.memory.pressure_threshold", 0.8f)
    val lazyRendering = dataStore.createValue("editor.rendering.lazy", true)

    // File Handling Settings
    val maxFileSize = dataStore.createValue("editor.file.max_size_bytes", 100L * 1024 * 1024) // 100MB
    val autoSaveInterval = dataStore.createValue("editor.auto_save.interval_ms", 30000L) // 30 seconds
    val autoSaveEnabled = dataStore.createValue("editor.auto_save.enabled", false)
    val backupBeforeEdit = dataStore.createValue("editor.backup.before_edit", true)

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

    // Debug and Monitoring
    val showMemoryStats = dataStore.createValue("editor.debug.show_memory_stats", false)
    val showChunkBoundaries = dataStore.createValue("editor.debug.show_chunk_boundaries", false)
    val logChunkOperations = dataStore.createValue("editor.debug.log_chunk_operations", false)
    val performanceMetrics = dataStore.createValue("editor.debug.performance_metrics", false)

    override val mapper = PreferenceStoreMapper(
        // Display Settings
        showLineNumbers,
        wordWrap,
        fontSize,
        tabSize,
        showWhitespace,
        
        // Chunk System Settings
        chunkSize,
        maxMemoryUsage,
        preloadChunks,
        autoEvictChunks,
        
        // Performance Settings
        visibleLineBuffer,
        backgroundLoading,
        memoryPressureThreshold,
        lazyRendering,
        
        // File Handling Settings
        maxFileSize,
        autoSaveInterval,
        autoSaveEnabled,
        backupBeforeEdit,
        
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
        
        // Debug and Monitoring
        showMemoryStats,
        showChunkBoundaries,
        logChunkOperations,
        performanceMetrics,
    )


    companion object {
        internal val TAG = logTag("Editor", "Settings")
    }
}
