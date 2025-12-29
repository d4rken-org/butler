package eu.darken.butler.setup.ui.items

import androidx.compose.runtime.Composable
import androidx.compose.ui.res.stringResource
import eu.darken.butler.R
import eu.darken.butler.setup.core.SetupAction
import eu.darken.butler.setup.core.SetupItem
import eu.darken.butler.setup.core.SetupModule

@Composable
fun SetupActions(
    item: SetupItem,
    onExecuteAction: (SetupAction) -> Unit
) {
    when (item.type) {
        SetupModule.Type.ROOT -> {
            RootShizukuActions(
                item = item,
                onExecuteAction = onExecuteAction,
                switchLabel = stringResource(R.string.setup_use_root_label)
            )
        }
        SetupModule.Type.SHIZUKU -> {
            RootShizukuActions(
                item = item,
                onExecuteAction = onExecuteAction,
                switchLabel = stringResource(R.string.setup_use_shizuku_label)
            )
        }
        else -> {
            DefaultActions(item = item, onExecuteAction = onExecuteAction)
        }
    }
}