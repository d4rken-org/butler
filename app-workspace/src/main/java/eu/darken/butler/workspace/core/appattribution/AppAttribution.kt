package eu.darken.butler.workspace.core.appattribution

import eu.darken.butler.common.pkgs.attribution.AppFolder
import eu.darken.butler.common.pkgs.container.NormalPkg
import eu.darken.butler.common.pkgs.container.UninstalledPkg
import eu.darken.butler.common.pkgs.features.Installed

/** Which app a folder belongs to, as far as the package inventory can tell. */
sealed interface AppAttribution {
    /** Installed or archived. */
    data class App(val pkg: Installed) : AppAttribution

    /** Uninstalled with its data kept, or not known to the system at all. */
    data class Ghost(val pkgName: String) : AppAttribution
}

/**
 * An index over one package inventory snapshot.
 *
 * A folder only becomes a [AppAttribution.Ghost] for a user whose apps were enumerated: the current
 * user, or one with [NormalPkg] entries. See AppInventoryTest for why other package types don't count.
 */
class AppInventory private constructor(
    private val pkgs: Map<Key, Installed>,
    private val coveredUsers: Set<Int>,
    private val currentUserId: Int,
) {

    fun attribute(folder: AppFolder): AppAttribution? {
        val userId = folder.userId
        val pkg = pkgs[Key(folder.pkgName, userId ?: currentUserId)]
        val canGhost = userId != null && userId in coveredUsers
        return when {
            pkg is UninstalledPkg -> if (canGhost) AppAttribution.Ghost(folder.pkgName) else null
            pkg != null -> AppAttribution.App(pkg)
            canGhost -> AppAttribution.Ghost(folder.pkgName)
            else -> null
        }
    }

    private data class Key(val pkgName: String, val userId: Int)

    companion object {
        fun from(pkgs: Collection<Installed>, currentUserId: Int): AppInventory = AppInventory(
            pkgs = pkgs.associateBy { Key(it.packageName, it.userHandle.handleId) },
            coveredUsers = pkgs.filterIsInstance<NormalPkg>().map { it.userHandle.handleId }.toSet() + currentUserId,
            currentUserId = currentUserId,
        )
    }
}
