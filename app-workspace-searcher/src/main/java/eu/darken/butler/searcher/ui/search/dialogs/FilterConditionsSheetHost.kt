package eu.darken.butler.searcher.ui.search.dialogs

import androidx.compose.runtime.Composable
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import eu.darken.butler.searcher.core.FilterCondition

@Composable
fun FilterConditionsSheetHost(
    dialogState: SearcherDialogState,
    onDismiss: () -> Unit,
    onConditionApply: (existing: FilterCondition?, new: FilterCondition) -> Unit,
    topInset: Dp = 0.dp,
    bottomInset: Dp = 0.dp,
) {
    val sizeState = dialogState as? SearcherDialogState.EditSizeCondition
    SizeConditionEditSheet(
        visible = sizeState != null,
        existingCondition = sizeState?.existing,
        onDismiss = onDismiss,
        onApply = { onConditionApply(sizeState?.existing, it) },
        topInset = topInset,
        bottomInset = bottomInset,
    )

    val dateState = dialogState as? SearcherDialogState.EditDateCondition
    DateConditionEditSheet(
        visible = dateState != null,
        existingCondition = dateState?.existing,
        onDismiss = onDismiss,
        onApply = { onConditionApply(dateState?.existing, it) },
        topInset = topInset,
        bottomInset = bottomInset,
    )

    val typeState = dialogState as? SearcherDialogState.EditTypeCondition
    TypeConditionEditSheet(
        visible = typeState != null,
        existingCondition = typeState?.existing,
        onDismiss = onDismiss,
        onApply = { onConditionApply(typeState?.existing, it) },
        topInset = topInset,
        bottomInset = bottomInset,
    )
}
