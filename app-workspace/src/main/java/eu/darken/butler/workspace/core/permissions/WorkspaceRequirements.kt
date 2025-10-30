package eu.darken.butler.workspace.core.permissions

import android.content.Intent
import android.os.Parcelable
import eu.darken.butler.common.files.APath
import eu.darken.butler.common.files.LocalPath
import eu.darken.butler.setup.core.SetupModule
import kotlinx.parcelize.Parcelize

/**
 * Represents a SAF picker that can be launched to grant access to a specific path.
 */
@Parcelize
data class SAFPickerGrant(
    val intent: Intent,
    val targetPath: LocalPath,
) : Parcelable

data class WorkspaceRequirements(
    val combos: Set<Set<SetupModule.Type>> = emptySet(),
    val complete: Set<SetupModule.Type> = emptySet(),
    val alternativePath: APath<*>? = null,
    val safPickerGrant: SAFPickerGrant? = null,
    val shizukuInstalled: Boolean = false,
    val rootInstalled: Boolean = false,
) {
    val needsSetup: Boolean
        get() = combos.isNotEmpty() &&
                combos.none { combo -> combo.all { it in complete } } &&
                safPickerGrant == null &&
                alternativePath == null

    val needsAction: Boolean
        get() = needsSetup || safPickerGrant != null || alternativePath != null

    val hasSetupOptions: Boolean
        get() = combos.isNotEmpty()

    val relevantTypes: Set<SetupModule.Type>
        get() = combos.flatten().distinct().toSet()
}
