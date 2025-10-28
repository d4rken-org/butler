package eu.darken.butler.workspace.core.permissions

import eu.darken.butler.setup.core.SetupModule

data class WorkspaceRequirements(
    val combos: Set<Set<SetupModule.Type>> = emptySet(),
    val complete: Set<SetupModule.Type> = emptySet(),
) {
    val needsSetup: Boolean
        get() = combos.isNotEmpty() && combos.none { combo -> combo.all { it in complete } }
    val relevantTypes: Set<SetupModule.Type>
        get() = combos.flatten().distinct().toSet()
}
