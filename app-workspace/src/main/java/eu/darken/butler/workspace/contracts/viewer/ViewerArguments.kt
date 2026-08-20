package eu.darken.butler.workspace.contracts.viewer

import eu.darken.butler.common.files.APath
import eu.darken.butler.workspace.core.Workspace
import kotlinx.parcelize.Parcelize
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.Transient

/**
 * Arguments for creating a Viewer workspace. The viewer is always bound to exactly one file, so
 * unlike the Editor there is no "empty tab" variant.
 *
 * [filePath] deliberately lives on [Default] rather than on this interface: [Streamed] has no path
 * at all, and a nullable path on the interface would let every consumer keep compiling behind a
 * null check instead of deciding what streamed content should do.
 */
@Serializable
sealed interface ViewerArguments : Workspace.Arguments {

    override val type: Workspace.Type get() = Workspace.Type.VIEWER

    /**
     * @param callerWorkspaceId If set, the viewer is a drill-down of the workspace that opened the
     * file and renders as an overlay in its pane instead of as a tab. Session-transient: caller
     * relationships are not persisted because sub-workspaces are excluded from session save.
     */
    @Serializable
    @SerialName("arguments")
    @Parcelize
    data class Default(
        val filePath: APath<*>,
        /** Text a share attached to this file, see [Streamed.caption]. */
        val caption: String? = null,
        @Transient override val callerWorkspaceId: Workspace.Id? = null,
    ) : ViewerArguments, Workspace.ArgumentsWithCaller, Workspace.ArgumentsWithContentPath {
        // Get-only (no backing field): invisible to kotlinx-serialization and Parcelize, so
        // persisted session arguments are unaffected.
        // Unconditional even for a drill-down: WorkspaceRepo skips dedup for sub-workspace creates
        // and only matches non-sub holders, so an overlay can neither trigger nor satisfy a match.
        override val contentPath: APath<*>? get() = filePath

        /**
         * These arguments describe the whole workspace - one file path - so a viewer overlay can be
         * released together with the tab that opened it and rebuilt exactly as it was. It owes its
         * caller no result, so nobody is waiting on it either.
         */
        override val pausableAsChild: Boolean get() = true

        // The caption is the sender's own text and arguments end up in retained diagnostic logs,
        // so only its presence is reported.
        override fun toString(): String =
            "ViewerArguments.Default(filePath=$filePath, caption=${caption.redacted}, " +
                "callerWorkspaceId=$callerWorkspaceId)"
    }

    /**
     * Content another app handed us as a `content://` URI that we can read but cannot name a path
     * for. The viewer streams it instead of copying it into the cache first.
     *
     * Everything the viewer would normally learn from a gateway lookup is carried here, because
     * there is nothing to look up: [mimeType] decides which renderer runs (a provider's display
     * name often has no extension, so the usual name-based classification would misroute it), and
     * [sizeBytes] is whatever the provider reported, or null when it reported nothing.
     *
     * @param arrivalId Distinguishes two shares of the same URI. Providers reuse document ids, so
     * without this the image caches would serve the first share's bytes for the second, and a retry
     * could never get past a cached failure.
     * @param caption What the sender wrote about the file. A share that carries both a file and text
     * opens the file, so the text is shown here rather than dropped.
     */
    @Serializable
    @SerialName("streamed")
    @Parcelize
    data class Streamed(
        val uriString: String,
        val displayName: String,
        val mimeType: String,
        val sizeBytes: Long?,
        val arrivalId: String,
        val caption: String? = null,
        @Transient override val callerWorkspaceId: Workspace.Id? = null,
    ) : ViewerArguments, Workspace.ArgumentsWithCaller {

        /**
         * The read grant belongs to the task that received the intent, so it is gone by the time a
         * saved session would be restored. Persisting this would reopen a tab that can only fail.
         */
        override val isPersistable: Boolean get() = false

        /**
         * Deliberately NOT [Workspace.ArgumentsWithContentPath]: dedup compares [APath]s, and a
         * foreign URI is not one. Two shares of the same file legitimately open two tabs.
         */
        override val pausableAsChild: Boolean get() = true

        /** See [Default.toString]. */
        override fun toString(): String =
            "ViewerArguments.Streamed(uriString=$uriString, displayName=$displayName, " +
                "mimeType=$mimeType, sizeBytes=$sizeBytes, arrivalId=$arrivalId, " +
                "caption=${caption.redacted}, callerWorkspaceId=$callerWorkspaceId)"
    }
}

/** Presence instead of content, for text that belongs to the user rather than to Butler. */
private val String?.redacted: String
    get() = if (this != null) "<present>" else "null"
