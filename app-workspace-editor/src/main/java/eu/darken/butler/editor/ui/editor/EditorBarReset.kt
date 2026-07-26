package eu.darken.butler.editor.ui.editor

import eu.darken.butler.common.files.APath

/**
 * The content identity the Editor's floating bars reset on: the file the TAB claims to hold.
 *
 * Fresh content has to reset the bars' scroll-collapse, or they stay hidden over a document the
 * user has not scrolled yet. Which state change counts as "fresh content" is the whole problem:
 *
 * - NOT the content source's type. A file-backed tab reports an in-memory placeholder until its
 *   async load finishes, so a source-derived identity settles one step AFTER the page has composed
 *   and restored its persisted collapse state - the restore would be reset away on every launch,
 *   and then written back as "expanded".
 * - NOT the content source's value either. It refreshes after every save (size, mtime, line
 *   ending) without the document having changed.
 * - The claimed path does exactly what is needed: it is published synchronously, from the creation
 *   arguments and again on every engine switch, so it is already correct on the first composed
 *   frame, holds still while the file loads and while it is saved, and changes when - and only
 *   when - the tab moves to a different file (open, Save-As, close-file, cancelled open).
 *
 * Null is an identity of its own: a scratch buffer, or a tab whose open was cancelled.
 *
 * Paired with [eu.darken.butler.common.compose.OnValueChange] at the call site, so the initial
 * composition - which is a restore, not a content change - never resets.
 */
internal fun editorBarResetIdentity(state: EditorWorkspaceViewModel.State): APath<*>? = state.contentPath
