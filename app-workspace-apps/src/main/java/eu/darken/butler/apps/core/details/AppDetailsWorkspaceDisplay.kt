package eu.darken.butler.apps.core.details

import eu.darken.butler.common.ca.toCaString
import eu.darken.butler.workspace.contracts.apps.AppDetailsArguments
import eu.darken.butler.workspace.core.WorkspaceDisplay

/**
 * Tab identity of an App details workspace derived from its arguments alone: the package name.
 * Resolving it to the app label needs the PackageManager, so the live tab enriches this once the
 * package data has loaded.
 *
 * A blank package name identifies nothing and falls back to the workspace type instead of leaving
 * the tab nameless.
 */
fun deriveAppDetailsDisplay(arguments: AppDetailsArguments) = WorkspaceDisplay(
    title = arguments.packageName.takeIf { it.isNotBlank() }?.toCaString(),
)
