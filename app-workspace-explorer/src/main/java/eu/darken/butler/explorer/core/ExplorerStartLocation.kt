package eu.darken.butler.explorer.core

import eu.darken.butler.common.files.APath
import eu.darken.butler.workspace.contracts.explorer.ExplorerStartTarget

/**
 * Where a workspace starts browsing.
 *
 * Precedence: an explicit [startPath] wins, then the location the tab was parked on when it was
 * persisted ([startTarget]), then the user's default start location. Arguments carrying neither -
 * including every session row saved before [startTarget] existed - keep the setting-driven
 * behaviour.
 *
 * [defaultStartLocation] is only consulted in that last case, so callers may pass null when the
 * arguments already carry a location and skip reading the setting.
 */
internal fun explorerStartTarget(
    startPath: APath<*>?,
    startTarget: ExplorerStartTarget?,
    defaultStartLocation: DefaultStartLocation?,
): ExplorerNavigation.Target = explorerArgumentsTarget(startPath, startTarget) ?: when (defaultStartLocation) {
    is DefaultStartLocation.Device -> ExplorerNavigation.Target.Device
    is DefaultStartLocation.Directory -> ExplorerNavigation.Target.Directory(defaultStartLocation.path)
    is DefaultStartLocation.Home, null -> ExplorerNavigation.Target.Home
}

/**
 * The location the arguments themselves name, or null when they carry none (the default start
 * setting decides then). Single source of the path-before-target precedence: the identity a
 * paused tab shows and the location resuming it navigates to are read from here, so they cannot
 * disagree.
 */
internal fun explorerArgumentsTarget(
    startPath: APath<*>?,
    startTarget: ExplorerStartTarget?,
): ExplorerNavigation.Target? = when {
    startPath != null -> ExplorerNavigation.Target.Directory(startPath)
    startTarget != null -> startTarget.asNavigationTarget
    else -> null
}

/** The persistable stand-in for a live navigation target; directories persist as a path instead. */
internal val ExplorerNavigation.Target.asStartTarget: ExplorerStartTarget?
    get() = when (this) {
        is ExplorerNavigation.Target.Home -> ExplorerStartTarget.HOME
        is ExplorerNavigation.Target.Device -> ExplorerStartTarget.DEVICE
        is ExplorerNavigation.Target.Network -> ExplorerStartTarget.NETWORK
        is ExplorerNavigation.Target.Trash -> ExplorerStartTarget.TRASH
        is ExplorerNavigation.Target.Directory -> null
    }

/** Inverse of [asStartTarget]: a nested trash location restores to the trash root. */
internal val ExplorerStartTarget.asNavigationTarget: ExplorerNavigation.Target
    get() = when (this) {
        ExplorerStartTarget.HOME -> ExplorerNavigation.Target.Home
        ExplorerStartTarget.DEVICE -> ExplorerNavigation.Target.Device
        ExplorerStartTarget.NETWORK -> ExplorerNavigation.Target.Network
        ExplorerStartTarget.TRASH -> ExplorerNavigation.Target.Trash.Root
    }
