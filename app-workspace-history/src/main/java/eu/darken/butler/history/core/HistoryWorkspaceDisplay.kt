package eu.darken.butler.history.core

import eu.darken.butler.common.ca.toCaString
import eu.darken.butler.history.R
import eu.darken.butler.workspace.contracts.history.HistoryArguments
import eu.darken.butler.workspace.core.WorkspaceDisplay

/**
 * Tab identity of a History workspace derived from its arguments alone. The live tab publishes a
 * subtitle on every emission, so the derivation carries one too — otherwise the subtitle would
 * appear the moment a dormant tab is restored.
 */
fun deriveHistoryDisplay(arguments: HistoryArguments) = WorkspaceDisplay(
    title = HistoryWorkspace.derivedTitle(arguments.filter),
    subtitle = R.string.history_workspace_subtitle.toCaString(),
)
