package eu.darken.butler.editor.core

import eu.darken.butler.common.ca.CaString
import eu.darken.butler.common.ca.toCaString
import eu.darken.butler.common.files.APath
import eu.darken.butler.editor.R
import eu.darken.butler.editor.core.engine.ContentSource
import eu.darken.butler.workspace.contracts.editor.EditorArguments
import eu.darken.butler.workspace.core.WorkspaceDisplay

/**
 * Tab identity of an Editor workspace derived from its arguments alone: the file name, else the
 * suggested name of a scratch tab, else "Untitled" — the same fallback the live tab shows, so a
 * dormant tab never displays a more generic label than its live counterpart.
 */
fun deriveEditorDisplay(arguments: EditorArguments): WorkspaceDisplay {
    val filePath = (arguments as? EditorArguments.Default)?.filePath
    return WorkspaceDisplay(
        title = filePath?.name?.toCaString() ?: editorScratchTitle(arguments),
        subtitle = editorLocationSubtitle(filePath),
    )
}

/**
 * Where a file lives, as shown under its name: the containing folder, never the full path — the
 * name is already the line above it.
 *
 * The ONE rule for this second line. The dormant placeholder, the tab identity in the workspace
 * manager and rail, and the editor's own toolbar all read it from here, so the same tab cannot
 * describe itself two ways depending on which surface is looking.
 */
fun editorLocationSubtitle(filePath: APath<*>?): CaString? = filePath?.parent?.userReadablePath

/**
 * Tab identity for what this tab currently holds.
 *
 * An engine emits an in-memory source BEFORE its file is loaded, and keeps doing so if loading
 * fails or hangs — so the source type alone cannot say whether this is a scratch buffer.
 * [identityPath] decides instead: it is the file the TAB claims
 * ([eu.darken.butler.workspace.core.Workspace.Info.contentPath]), which survives loading and
 * failure but is cleared when the user cancels the open or closes the file. That is the same
 * signal a session save reads, so a restored tab is named like the live one.
 */
internal fun editorContentDisplay(
    contentSource: ContentSource,
    identityPath: APath<*>?,
    scratchTitle: CaString,
): WorkspaceDisplay = when (contentSource) {
    is ContentSource.File -> WorkspaceDisplay(
        title = contentSource.path.name.toCaString(),
        subtitle = editorLocationSubtitle(contentSource.path),
    )
    is ContentSource.Memory -> when (identityPath) {
        // A blank name is no name: it would leave the tab card with nothing to click
        null -> WorkspaceDisplay(
            title = contentSource.suggestedName?.takeIf { it.isNotBlank() }?.toCaString() ?: scratchTitle,
        )
        else -> WorkspaceDisplay(
            title = identityPath.name.toCaString(),
            subtitle = editorLocationSubtitle(identityPath),
        )
    }
}

/**
 * Name for a buffer that has no file behind it (never opened one, or the user closed it).
 *
 * A blank suggested title is ignored: a share with an empty EXTRA_SUBJECT arrives as one, and an
 * empty title would leave the tab unidentifiable in the workspace manager.
 */
internal fun editorScratchTitle(arguments: EditorArguments): CaString =
    (arguments as? EditorArguments.Default)?.suggestedTitle?.takeIf { it.isNotBlank() }?.toCaString()
        ?: R.string.editor_file_untitled.toCaString()
