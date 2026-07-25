package eu.darken.butler.explorer.core

import eu.darken.butler.workspace.contracts.explorer.ExplorerArguments
import eu.darken.butler.workspace.core.WorkspaceDisplay

/**
 * Tab identity of an Explorer workspace derived from its arguments alone: the location those
 * arguments name — the full start path, else the parked navigation target.
 *
 * Resolution AND presentation are shared with navigation ([explorerArgumentsTarget] plus the
 * target's own [ExplorerNavigation.Target.label] and [ExplorerNavigation.Target.description], which
 * the live info flow publishes too), so a dormant tab describes itself exactly like the location
 * its hydration actually opens — including the second line targets such as Trash carry. Pure and
 * synchronous — used for both the dormant stand-in and the live workspace's
 * [eu.darken.butler.workspace.core.initialInfo] seed.
 */
fun deriveExplorerDisplay(arguments: ExplorerArguments): WorkspaceDisplay? = when (arguments) {
    // Pickers are sub-workspaces and never persisted, but the when must be total
    is ExplorerArguments.Picker -> null
    is ExplorerArguments.Default -> explorerArgumentsTarget(arguments.startPath, arguments.startTarget)?.display
}

/**
 * The two lines a navigation target publishes about itself, as ONE value.
 *
 * Read as a whole, never field by field: a target with no [ExplorerNavigation.Target.description]
 * means "this location has no second line", not "keep whatever was there before". Shared by the
 * dormant derivation and the live info flow so both describe a location the same way.
 */
internal val ExplorerNavigation.Target.display: WorkspaceDisplay
    get() = WorkspaceDisplay(title = label, subtitle = description)
