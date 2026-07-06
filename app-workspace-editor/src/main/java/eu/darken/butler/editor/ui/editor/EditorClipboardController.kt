package eu.darken.butler.editor.ui.editor

import eu.darken.butler.common.SystemClipboardHelper
import eu.darken.butler.common.debug.logging.Logging.Priority.ERROR
import eu.darken.butler.common.debug.logging.Logging.Priority.INFO
import eu.darken.butler.common.debug.logging.Logging.Priority.WARN
import eu.darken.butler.common.debug.logging.asLog
import eu.darken.butler.common.debug.logging.log
import eu.darken.butler.common.files.APath
import eu.darken.butler.editor.core.EditorWorkspace
import eu.darken.butler.editor.core.engine.ContentSource
import eu.darken.butler.workspace.core.Workspace
import eu.darken.butler.workspace.core.clipboard.ClipboardClip
import eu.darken.butler.workspace.core.clipboard.ClipboardRepo
import eu.darken.butler.workspace.ui.clipboard.ClipboardDisplayState
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.map

/**
 * System- and Butler-clipboard routing for the editor. Extracted from the ViewModel so the
 * text-extension gating, size caps, and copy/cut/paste flows are testable in isolation.
 */
class EditorClipboardController(
    private val id: Workspace.Id,
    private val doLaunch: (suspend CoroutineScope.() -> Unit) -> Unit,
    private val workspace: suspend () -> EditorWorkspace,
    private val clipboardHelper: SystemClipboardHelper,
    private val clipboardRepo: ClipboardRepo,
    private val tag: String,
) {

    private val _hasSystemClipboardContent = MutableStateFlow(clipboardHelper.hasClipboardContent())
    val hasSystemClipboardContent: StateFlow<Boolean> = _hasSystemClipboardContent.asStateFlow()

    private val _clipboardInfoClip = MutableStateFlow<ClipboardClip?>(null)
    val clipboardInfoClip: Flow<ClipboardClip?> = _clipboardInfoClip

    val clipboard: Flow<ClipboardDisplayState> = clipboardRepo.state.map { state ->
        ClipboardDisplayState(entries = state.entries)
    }

    /** Clipboard entries that can be pasted into the editor (files only, not text). */
    val pasteableClipboard: Flow<List<ClipboardClip.Paths>> = clipboardRepo.state
        .map { state ->
            state.entries.filterIsInstance<ClipboardClip.Paths>()
                .filter { clip -> clip.paths.any { path -> isLikelyTextFile(path) } }
        }

    fun refreshClipboardState() {
        _hasSystemClipboardContent.value = clipboardHelper.hasClipboardContent()
    }

    fun copyToClipboard() = doLaunch {
        workspace().copySelection().fold(
            onSuccess = { text ->
                clipboardHelper.copyToClipboard(text)
                _hasSystemClipboardContent.value = true
                log(tag) { "Copied ${text.length} characters to system clipboard" }
            },
            onFailure = { e -> log(tag, ERROR) { "Failed to copy selection - ${e.asLog()}" } },
        )
    }

    fun cutToClipboard() = doLaunch {
        val ws = workspace()
        ws.copySelection().fold(
            onSuccess = { text ->
                clipboardHelper.copyToClipboard(text)
                _hasSystemClipboardContent.value = true
                ws.deleteSelection()
                log(tag) { "Cut ${text.length} characters to system clipboard" }
            },
            onFailure = { e -> log(tag, ERROR) { "Failed to cut selection - ${e.asLog()}" } },
        )
    }

    /** Copies selection to Butler clipboard only (for long-press action). */
    fun copyToButlerClipboard() = doLaunch {
        workspace().copySelection().fold(
            onSuccess = { text -> addToButlerClipboard(text) },
            onFailure = { e -> log(tag, ERROR) { "Failed to copy selection - ${e.asLog()}" } },
        )
    }

    /** Cuts selection to Butler clipboard only (for long-press action). */
    fun cutToButlerClipboard() = doLaunch {
        val ws = workspace()
        ws.copySelection().fold(
            onSuccess = { text ->
                // Deleting after a rejected copy (size cap) would silently drop the text
                if (addToButlerClipboard(text)) {
                    ws.deleteSelection()
                    log(tag) { "Cut ${text.length} characters to Butler clipboard" }
                }
            },
            onFailure = { e -> log(tag, ERROR) { "Failed to cut selection - ${e.asLog()}" } },
        )
    }

    private suspend fun addToButlerClipboard(text: String): Boolean {
        if (text.toByteArray(Charsets.UTF_8).size > ClipboardClip.Text.MAX_SIZE_BYTES) {
            log(tag, WARN) { "Text too large for Butler clipboard: ${text.length} chars" }
            return false
        }

        val currentSource = (workspace().state.value as? EditorWorkspace.State.Ready)?.editor?.contentSource
        val clip = ClipboardClip.Text(
            origin = id,
            content = text,
            sourcePath = (currentSource as? ContentSource.File)?.path,
        )
        clipboardRepo.add(clip)
        log(tag, INFO) { "Added ${text.length} characters to Butler clipboard" }
        return true
    }

    fun pasteFromClipboard() = doLaunch {
        val text = clipboardHelper.getClipboardText()
        if (text != null) {
            workspace().insertText(text)
            log(tag) { "Pasted ${text.length} characters from clipboard" }
        } else {
            log(tag) { "No text content in clipboard to paste" }
        }
    }

    fun pasteFromClipboard(clip: ClipboardClip) = doLaunch {
        log(tag) { "pasteFromClipboard($clip)" }
        when (clip) {
            is ClipboardClip.Text -> {
                workspace().insertText(clip.content)
                log(tag, INFO) { "Pasted ${clip.content.length} characters from Butler clipboard" }
            }
            is ClipboardClip.Paths -> {
                val textFile = clip.paths.firstOrNull { isLikelyTextFile(it) }
                if (textFile != null) {
                    pasteFromClipboardFile(textFile)
                } else {
                    log(tag, WARN) { "No text files found in clipboard paths" }
                }
            }
        }
    }

    /** Paste content from a file in the Butler clipboard into the editor. */
    fun pasteFromClipboardFile(path: APath<*>) = doLaunch {
        log(tag) { "pasteFromClipboardFile($path)" }
        workspace().readFileContent(path).fold(
            onSuccess = { content ->
                workspace().insertText(content)
                log(tag, INFO) { "Pasted ${content.length} characters from file: ${path.name}" }
            },
            onFailure = { e -> log(tag, ERROR) { "Failed to paste from file: ${e.asLog()}" } },
        )
    }

    fun showClipboardInfo(clip: ClipboardClip) {
        log(tag) { "showClipboardInfo($clip)" }
        _clipboardInfoClip.value = clip
    }

    fun dismissClipboardInfo() {
        _clipboardInfoClip.value = null
    }

    fun removeClipboardEntry(clip: ClipboardClip) = doLaunch {
        log(tag) { "removeClipboardEntry(${clip.id})" }
        clipboardRepo.remove(clip.id)
    }

    fun clearAllClipboard() = doLaunch {
        log(tag) { "clearAllClipboard()" }
        clipboardRepo.clear()
    }

    private fun isLikelyTextFile(path: APath<*>): Boolean {
        val ext = path.name.substringAfterLast('.', "").lowercase()
        return ext in TEXT_EXTENSIONS
    }

    companion object {
        internal val TEXT_EXTENSIONS = setOf(
            "txt", "md", "json", "xml", "html", "css", "js", "kt", "java", "py", "sh",
            "yml", "yaml", "csv", "log", "conf", "ini", "properties", "gradle", "toml",
            "c", "cpp", "h", "hpp", "rs", "go", "rb", "php", "sql", "ts", "tsx", "jsx",
        )
    }
}
