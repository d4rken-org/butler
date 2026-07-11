package eu.darken.butler.editor.ui.settings

import dagger.hilt.android.lifecycle.HiltViewModel
import eu.darken.butler.common.coroutine.DispatcherProvider
import eu.darken.butler.common.datastore.value
import eu.darken.butler.common.debug.logging.log
import eu.darken.butler.common.debug.logging.logTag
import eu.darken.butler.common.flow.combine
import eu.darken.butler.common.ui.ViewModel4
import eu.darken.butler.editor.core.EditorSettings
import javax.inject.Inject
import kotlin.time.Duration.Companion.seconds

@HiltViewModel
class EditorSettingsViewModel
@Inject
constructor(
    dispatcherProvider: DispatcherProvider,
    private val editorSettings: EditorSettings,
) : ViewModel4(dispatcherProvider, logTag("Editor", "Settings", "ViewModel")) {

    val state = combine(
        editorSettings.showLineNumbers.flow,
        editorSettings.wordWrap.flow,
        editorSettings.syntaxHighlighting.flow,
        editorSettings.fontSize.flow,
        editorSettings.tabSize.flow,
        editorSettings.autoSaveEnabled.flow,
        editorSettings.autoSaveInterval.flow,
    ) { showLineNumbers, wordWrap, syntaxHighlighting, fontSize, tabSize, autoSaveEnabled, autoSaveInterval ->
        State(
            showLineNumbers = showLineNumbers,
            wordWrap = wordWrap,
            syntaxHighlighting = syntaxHighlighting,
            fontSize = fontSize,
            tabSize = tabSize,
            autoSaveEnabled = autoSaveEnabled,
            autoSaveIntervalSeconds = autoSaveInterval.inWholeSeconds.toInt(),
        )
    }.asStateFlow()


    fun updateShowLineNumbers(enabled: Boolean) = launch {
        log(tag) { "updateShowLineNumbers($enabled)" }
        editorSettings.showLineNumbers.value(enabled)
    }

    fun updateWordWrap(enabled: Boolean) = launch {
        log(tag) { "updateWordWrap($enabled)" }
        editorSettings.wordWrap.value(enabled)
    }

    fun updateSyntaxHighlighting(enabled: Boolean) = launch {
        log(tag) { "updateSyntaxHighlighting($enabled)" }
        editorSettings.syntaxHighlighting.value(enabled)
    }

    fun updateFontSize(size: Int) = launch {
        log(tag) { "updateFontSize($size)" }
        editorSettings.fontSize.value(size)
    }

    fun updateTabSize(size: Int) = launch {
        log(tag) { "updateTabSize($size)" }
        editorSettings.tabSize.value(size)
    }

    fun updateAutoSaveEnabled(enabled: Boolean) = launch {
        log(tag) { "updateAutoSaveEnabled($enabled)" }
        editorSettings.autoSaveEnabled.value(enabled)
    }

    fun updateAutoSaveInterval(seconds: Int) = launch {
        log(tag) { "updateAutoSaveInterval($seconds)" }
        editorSettings.autoSaveInterval.value(seconds.seconds)
    }

    data class State(
        val showLineNumbers: Boolean = true,
        val wordWrap: Boolean = false,
        val syntaxHighlighting: Boolean = true,
        val fontSize: Int = 14,
        val tabSize: Int = 4,
        val autoSaveEnabled: Boolean = false,
        val autoSaveIntervalSeconds: Int = 30,
    )

    companion object {
        val FONT_SIZE_OPTIONS = listOf(10, 12, 14, 16, 18, 20, 24)
        val TAB_SIZE_OPTIONS = listOf(2, 4, 8)
    }
}