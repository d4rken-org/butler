package eu.darken.butler.apps.core.details

import eu.darken.butler.common.ca.toCaString
import eu.darken.butler.workspace.contracts.apps.AppDetailsArguments
import eu.darken.butler.workspace.core.WorkspaceDisplay

/**
 * A label worth showing as a second identity line. `Pkg.label` itself falls back to the package
 * name, so a "resolved" label equal to the package is no label at all - rendering it would print
 * the same string as title and subtitle.
 */
internal fun normalizedAppLabel(label: String?, packageName: String): String? =
    label?.takeIf { it.isNotBlank() && it != packageName }

/**
 * Tab identity of an App details workspace derived from its arguments alone: the app label cached
 * at creation, with the package name below it. Resolving the label needs the PackageManager, so
 * the live tab refreshes it once the package data has loaded.
 *
 * A blank package name identifies nothing and falls back to the workspace type instead of leaving
 * the tab nameless.
 */
fun deriveAppDetailsDisplay(arguments: AppDetailsArguments): WorkspaceDisplay {
    val pkg = arguments.packageName.takeIf { it.isNotBlank() }
    val label = normalizedAppLabel(arguments.appLabel, arguments.packageName)
    return WorkspaceDisplay(
        title = label?.toCaString() ?: pkg?.toCaString(),
        subtitle = label?.let { pkg?.toCaString() },
    )
}
