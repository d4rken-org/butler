package eu.darken.butler.saver.core

import eu.darken.butler.common.ca.CaString
import eu.darken.butler.common.ca.caString
import eu.darken.butler.common.ca.toCaString
import eu.darken.butler.common.files.APath
import eu.darken.butler.common.getQuantityString2
import eu.darken.butler.common.pkgs.Pkg
import eu.darken.butler.saver.R
import eu.darken.butler.workspace.contracts.saver.SaverArguments
import eu.darken.butler.workspace.core.WorkspaceDisplay

/**
 * Tab identity of a Saver workspace derived from its arguments alone: how many files were shared
 * and where they are going. Resolving a `content://` URI to a filename is I/O (and its grant is
 * usually dead after a cold start), so the live tab enriches this once the sources resolve.
 */
fun deriveSaverDisplay(arguments: SaverArguments): WorkspaceDisplay? {
    val args = arguments as? SaverArguments.Default ?: return null
    return WorkspaceDisplay(
        title = saverTitle(args.sourceUris.size),
        subtitle = saverLocationSubtitle(args.destinationPath, args.callerPackage),
    )
}

/**
 * Where the files are going, else who sent them.
 *
 * The ONE rule for that line, fed the CURRENT destination by the live tab and the persisted one by
 * the dormant stand-in - the destination changes while the tab is open, so a live tab that fell
 * back to its creation-time destination would disagree with its own session save.
 */
fun saverLocationSubtitle(destinationPath: APath<*>?, callerPackage: Pkg.Id?): CaString? =
    destinationPath?.userReadableName
        ?: callerPackage?.takeIf { !isUnknownCaller(it) }?.name?.toCaString()

/** "3 files" for a batch, else the generic save title — a single file is named by its subtitle. */
fun saverTitle(sourceCount: Int): CaString = when {
    sourceCount > 1 -> caString { ctx ->
        ctx.getQuantityString2(R.plurals.saver_workspace_title_count, sourceCount, sourceCount)
    }
    else -> R.string.saver_workspace_title.toCaString()
}

/** Callers that carry no identity worth showing (ADB/shell shares, empty package names). */
internal fun isUnknownCaller(pkgId: Pkg.Id): Boolean {
    val name = pkgId.name.lowercase()
    return name == "shell" || name == "com.android.shell" || name.isEmpty()
}
