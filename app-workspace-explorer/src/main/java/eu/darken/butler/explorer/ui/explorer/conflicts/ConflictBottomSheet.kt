package eu.darken.butler.explorer.ui.explorer.conflicts

import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import eu.darken.butler.explorer.core.operations.conflicts.Conflict

@Composable
fun ConflictBottomSheet(
    conflict: Conflict,
    onResolution: (Conflict.Resolution) -> Unit,
    onDismiss: () -> Unit,
) {
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)

    ModalBottomSheet(
        sheetState = sheetState,
        onDismissRequest = onDismiss,
    ) {
        when (conflict) {
            is Conflict.PathAlreadyExists -> PathAlreadyExistsConflictSheet(
                conflict = conflict,
                onResolution = onResolution,
            )
            is Conflict.InsufficientPermission -> InsufficientPermissionConflictSheet(
                conflict = conflict,
                onResolution = onResolution,
            )
            is Conflict.InsufficientSpace -> InsufficientSpaceConflictSheet(
                conflict = conflict,
                onResolution = onResolution,
            )
            is Conflict.UnknownError -> UnknownErrorConflictSheet(
                conflict = conflict,
                onResolution = onResolution,
            )
        }
    }
}