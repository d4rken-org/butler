package eu.darken.butler.editor.ui.editor

import eu.darken.butler.common.files.APath
import eu.darken.butler.common.flow.combine
import eu.darken.butler.editor.core.EditorWorkspace
import eu.darken.butler.editor.core.engine.ContentSource
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow

/**
 * Dialog and notice state for the editor page: go-to-line, close confirmation, encoding
 * selection with its discard confirmation, the Save-As overwrite confirmation, and the
 * stale-backup notice dismissal. Extracted from the ViewModel for isolated testing.
 */
class EditorDialogsController(
    private val doLaunch: (suspend CoroutineScope.() -> Unit) -> Unit,
    private val workspace: suspend () -> EditorWorkspace,
) {

    private val _showGoToLineDialog = MutableStateFlow(false)
    private val _showCloseConfirmDialog = MutableStateFlow(false)
    private val _showEncodingDialog = MutableStateFlow(false)
    private val _pendingEncoding = MutableStateFlow<String?>(null)
    private val _pendingSaveAsOverwrite = MutableStateFlow<APath<*>?>(null)
    private val _backupNoticeDismissed = MutableStateFlow(false)
    private val _showReloadConfirmDialog = MutableStateFlow(false)
    private val _externalChangeDismissedGeneration = MutableStateFlow<Int?>(null)
    private val _showLineEndingDialog = MutableStateFlow(false)
    private val _longLinesNoticeDismissed = MutableStateFlow(false)
    private val _showLargeDeleteConfirmDialog = MutableStateFlow(false)

    data class DialogUiState(
        val showGoToLineDialog: Boolean = false,
        val showCloseConfirmDialog: Boolean = false,
        val showEncodingDialog: Boolean = false,
        val pendingEncoding: String? = null,
        val pendingSaveAsOverwrite: APath<*>? = null,
        val backupNoticeDismissed: Boolean = false,
        val showReloadConfirmDialog: Boolean = false,
        val externalChangeDismissedGeneration: Int? = null,
        val showLineEndingDialog: Boolean = false,
        val longLinesNoticeDismissed: Boolean = false,
        val showLargeDeleteConfirmDialog: Boolean = false,
    )

    val state: Flow<DialogUiState> = combine(
        _showGoToLineDialog,
        _showCloseConfirmDialog,
        _showEncodingDialog,
        _pendingEncoding,
        _pendingSaveAsOverwrite,
        _backupNoticeDismissed,
        _showReloadConfirmDialog,
        _externalChangeDismissedGeneration,
        _showLineEndingDialog,
        _longLinesNoticeDismissed,
        _showLargeDeleteConfirmDialog,
    ) { showGoToLineDialog, showCloseConfirmDialog, showEncodingDialog, pendingEncoding,
        pendingSaveAsOverwrite, backupNoticeDismissed, showReloadConfirmDialog,
        externalChangeDismissedGeneration, showLineEndingDialog, longLinesNoticeDismissed,
        showLargeDeleteConfirmDialog ->
        DialogUiState(
            showGoToLineDialog = showGoToLineDialog,
            showCloseConfirmDialog = showCloseConfirmDialog,
            showEncodingDialog = showEncodingDialog,
            pendingEncoding = pendingEncoding,
            pendingSaveAsOverwrite = pendingSaveAsOverwrite,
            backupNoticeDismissed = backupNoticeDismissed,
            showReloadConfirmDialog = showReloadConfirmDialog,
            externalChangeDismissedGeneration = externalChangeDismissedGeneration,
            showLineEndingDialog = showLineEndingDialog,
            longLinesNoticeDismissed = longLinesNoticeDismissed,
            showLargeDeleteConfirmDialog = showLargeDeleteConfirmDialog,
        )
    }

    fun showGoToLineDialog() {
        _showGoToLineDialog.value = true
    }

    fun dismissGoToLineDialog() {
        _showGoToLineDialog.value = false
    }

    fun showCloseConfirmDialog() {
        _showCloseConfirmDialog.value = true
    }

    fun dismissCloseConfirmDialog() {
        _showCloseConfirmDialog.value = false
    }

    fun showEncodingDialog() {
        _showEncodingDialog.value = true
    }

    fun dismissEncodingDialog() {
        _showEncodingDialog.value = false
    }

    fun selectEncoding(charsetName: String) {
        _showEncodingDialog.value = false
        doLaunch {
            val editor = (workspace().state.value as? EditorWorkspace.State.Ready)?.editor ?: return@doLaunch
            val currentEncoding =
                (editor.contentSource as? ContentSource.File)?.detectedCharset?.name() ?: "UTF-8"
            if (currentEncoding.equals(charsetName, ignoreCase = true)) return@doLaunch
            if (editor.isModified) {
                // Reopening rescans from disk; let the user confirm losing unsaved changes
                _pendingEncoding.value = charsetName
            } else {
                workspace().reopenWithCharset(charsetName)
            }
        }
    }

    fun confirmEncodingDiscard() {
        val charsetName = _pendingEncoding.value ?: return
        _pendingEncoding.value = null
        doLaunch {
            workspace().reopenWithCharset(charsetName)
        }
    }

    fun dismissEncodingDiscard() {
        _pendingEncoding.value = null
    }

    /** A new Save-As result supersedes any overwrite decision still pending from an earlier one. */
    fun setPendingSaveAsOverwrite(destination: APath<*>?) {
        _pendingSaveAsOverwrite.value = destination
    }

    /** Returns the pending destination and clears it, or null if none was pending. */
    fun takePendingSaveAsOverwrite(): APath<*>? {
        val destination = _pendingSaveAsOverwrite.value
        _pendingSaveAsOverwrite.value = null
        return destination
    }

    fun dismissSaveAsOverwrite() {
        _pendingSaveAsOverwrite.value = null
    }

    fun dismissBackupNotice() {
        _backupNoticeDismissed.value = true
    }

    fun rearmBackupNotice() {
        _backupNoticeDismissed.value = false
    }

    fun dismissLongLinesNotice() {
        _longLinesNoticeDismissed.value = true
    }

    fun rearmLongLinesNotice() {
        _longLinesNoticeDismissed.value = false
    }

    fun showReloadConfirmDialog() {
        _showReloadConfirmDialog.value = true
    }

    fun dismissReloadConfirmDialog() {
        _showReloadConfirmDialog.value = false
    }

    /** Hides the external-change banner for exactly this detection; a later one re-shows it. */
    fun dismissExternalChange(generation: Int) {
        _externalChangeDismissedGeneration.value = generation
    }

    /** Called whenever the engine reports no external change (reload, save, engine swap). */
    fun rearmExternalChangeNotice() {
        _externalChangeDismissedGeneration.value = null
    }

    fun showLineEndingDialog() {
        _showLineEndingDialog.value = true
    }

    fun dismissLineEndingDialog() {
        _showLineEndingDialog.value = false
    }

    fun showLargeDeleteConfirmDialog() {
        _showLargeDeleteConfirmDialog.value = true
    }

    fun dismissLargeDeleteConfirmDialog() {
        _showLargeDeleteConfirmDialog.value = false
    }
}
