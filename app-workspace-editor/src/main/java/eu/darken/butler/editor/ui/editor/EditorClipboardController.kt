package eu.darken.butler.editor.ui.editor

import eu.darken.butler.common.SystemClipboardHelper
import eu.darken.butler.common.debug.logging.Logging.Priority.ERROR
import eu.darken.butler.common.debug.logging.Logging.Priority.INFO
import eu.darken.butler.common.debug.logging.Logging.Priority.WARN
import eu.darken.butler.common.debug.logging.asLog
import eu.darken.butler.common.debug.logging.log
import eu.darken.butler.common.files.APath
import eu.darken.butler.common.files.APathLookup
import eu.darken.butler.common.files.TextFileDetector
import eu.darken.butler.common.files.metadata.FileType
import eu.darken.butler.editor.core.EditorWorkspace
import eu.darken.butler.editor.core.engine.ClipboardCapacityException
import eu.darken.butler.editor.core.engine.ContentSource
import eu.darken.butler.editor.core.engine.EditorEngine
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
    // Inserts text guarded by the oversized-selection confirm gate; returns false when the edit was
    // deferred behind the confirm dialog (so paste success isn't logged prematurely). Enqueues on the
    // ViewModel's ordered edit queue and awaits the outcome.
    private val guardedInsert: suspend (String) -> Boolean,
    // Deletes a captured cut through the same ordered queue, awaiting the engine's result: the
    // deletion can't be overtaken by a keystroke typed after it, and it removes exactly the range
    // that was copied even if the selection moved while it waited.
    private val deleteCut: suspend (EditorEngine.CutSnapshot) -> Result<String>,
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
        val text = extractSelection(maxChars = MAX_SYSTEM_CLIPBOARD_CHARS) ?: return@doLaunch
        copyToSystemClipboard(text)
        log(tag) { "Copied ${text.length} characters to system clipboard" }
    }

    fun cutToClipboard() = doLaunch {
        val cut = prepareCut(maxChars = MAX_SYSTEM_CLIPBOARD_CHARS) ?: return@doLaunch
        // Any copy refusal or write failure throws above/inside, so the delete never runs
        copyToSystemClipboard(cut.text)
        deleteCut(cut).getOrElse { e ->
            // A document that moved on deletes nothing; the copy already succeeded
            log(tag, ERROR) { "Cut copied but failed to delete - ${e.asLog()}" }
            return@doLaunch
        }
        log(tag) { "Cut ${cut.text.length} characters to system clipboard" }
    }

    /** Copies selection to Butler clipboard only (for the CopyToButlerClipboard action). */
    fun copyToButlerClipboard() = doLaunch {
        val text = extractSelection(maxChars = BUTLER_CLIPBOARD_PREFILTER_CHARS) ?: return@doLaunch
        addToButlerClipboard(text)
    }

    /** Cuts selection to Butler clipboard only (for the CutToButlerClipboard action). */
    fun cutToButlerClipboard() = doLaunch {
        val cut = prepareCut(maxChars = BUTLER_CLIPBOARD_PREFILTER_CHARS) ?: return@doLaunch
        // Deleting after a rejected copy (size cap throws) would silently drop the text
        addToButlerClipboard(cut.text)
        deleteCut(cut).getOrElse { e ->
            log(tag, ERROR) { "Cut copied but failed to delete - ${e.asLog()}" }
            return@doLaunch
        }
        log(tag) { "Cut ${cut.text.length} characters to Butler clipboard" }
    }

    /**
     * Extracts the selection under the engine's cap: capacity refusals throw (surfacing through
     * the ViewModel's error handler), other failures (e.g. no selection) stay log-only as before.
     */
    private suspend fun extractSelection(maxChars: Long): String? =
        workspace().copySelection(maxChars).getOrElse { e ->
            handleExtractFailure(e)
            null
        }

    /** [extractSelection] plus the range/version identity the cut's deletion is verified against. */
    private suspend fun prepareCut(maxChars: Long): EditorEngine.CutSnapshot? =
        workspace().prepareCut(maxChars).getOrElse { e ->
            handleExtractFailure(e)
            null
        }

    private fun handleExtractFailure(e: Throwable) {
        if (e is ClipboardCapacityException) throw e
        log(tag, ERROR) { "Failed to extract selection - ${e.asLog()}" }
    }

    /** The char cap is a heuristic; setPrimaryClip can still fail across the binder for large clips. */
    private fun copyToSystemClipboard(text: String) {
        try {
            clipboardHelper.copyToClipboard(text)
        } catch (e: Exception) {
            throw ClipboardCapacityException(limitBytes = MAX_SYSTEM_CLIPBOARD_CHARS, cause = e)
        }
        _hasSystemClipboardContent.value = true
    }

    private suspend fun addToButlerClipboard(text: String) {
        // The char pre-filter can't be exact (UTF-8 is 1-3 bytes per UTF-16 unit); this is the
        // authoritative check
        if (text.toByteArray(Charsets.UTF_8).size > ClipboardClip.Text.MAX_SIZE_BYTES) {
            throw ClipboardCapacityException(limitBytes = ClipboardClip.Text.MAX_SIZE_BYTES.toLong())
        }

        val currentSource = (workspace().state.value as? EditorWorkspace.State.Ready)?.editor?.contentSource
        val clip = ClipboardClip.Text(
            origin = id,
            content = text,
            sourcePath = (currentSource as? ContentSource.File)?.path,
        )
        clipboardRepo.add(clip)
        log(tag, INFO) { "Added ${text.length} characters to Butler clipboard" }
    }

    fun pasteFromClipboard() = doLaunch {
        val text = clipboardHelper.getClipboardText()
        if (text != null) {
            if (guardedInsert(text)) log(tag) { "Pasted ${text.length} characters from clipboard" }
        } else {
            log(tag) { "No text content in clipboard to paste" }
        }
    }

    fun pasteFromClipboard(clip: ClipboardClip) = doLaunch {
        log(tag) { "pasteFromClipboard($clip)" }
        when (clip) {
            is ClipboardClip.Text -> {
                if (guardedInsert(clip.content)) {
                    log(tag, INFO) { "Pasted ${clip.content.length} characters from Butler clipboard" }
                }
            }
            is ClipboardClip.Paths -> {
                val textFile = clip.paths.firstOrNull { isLikelyTextFile(it) }
                if (textFile != null) {
                    pasteFileContent(textFile.lookedUp)
                } else {
                    log(tag, WARN) { "No text files found in clipboard paths" }
                }
            }
        }
    }

    /** Paste content from a file in the Butler clipboard into the editor. */
    fun pasteFromClipboardFile(path: APath<*>) = doLaunch {
        pasteFileContent(path)
    }

    /**
     * Shared paste-from-file path: failures (file too large, binary content, I/O) throw so they
     * reach the launching coroutine's error handler and become visible - they were silently
     * logged before.
     */
    private suspend fun pasteFileContent(path: APath<*>) {
        log(tag) { "pasteFileContent($path)" }
        val content = workspace().readFileContent(path).getOrThrow()
        if (guardedInsert(content)) log(tag, INFO) { "Pasted ${content.length} characters from file: ${path.name}" }
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

    /**
     * Cheap, no-I/O suggestion heuristic for the paste sheet. Requires regular-file metadata (so
     * directories/symlinks never surface) and then defers to the shared text table.
     */
    private fun isLikelyTextFile(lookup: APathLookup<*>): Boolean {
        if (lookup.fileType != FileType.FILE) return false
        val name = lookup.name
        // An extensionless file is still offered: the sheet only SUGGESTS, and PasteFileReader
        // rejects real binaries when one is actually picked.
        return name.substringAfterLast('.', "").isEmpty() || TextFileDetector.isTextFile(name)
    }

    companion object {
        /**
         * System-clipboard cap in UTF-16 units (~500KB in the parcel) - comfortable margin under
         * the ~1MB binder transaction limit that makes setPrimaryClip throw.
         */
        const val MAX_SYSTEM_CLIPBOARD_CHARS = 250_000L

        /**
         * Pre-materialization filter for the Butler clipboard: UTF-8 output is always >= 1 byte
         * per UTF-16 unit, so more chars than [ClipboardClip.Text.MAX_SIZE_BYTES] can never fit.
         * Selections passing this may still fail the exact byte check in [addToButlerClipboard].
         */
        val BUTLER_CLIPBOARD_PREFILTER_CHARS = ClipboardClip.Text.MAX_SIZE_BYTES.toLong()
    }
}
