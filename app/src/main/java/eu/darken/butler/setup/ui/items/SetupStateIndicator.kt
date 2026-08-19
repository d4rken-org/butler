package eu.darken.butler.setup.ui.items

import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.twotone.CheckCircle
import androidx.compose.material.icons.twotone.Error
import androidx.compose.material.icons.twotone.PauseCircle
import androidx.compose.material.icons.twotone.RadioButtonUnchecked
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import eu.darken.butler.setup.core.SetupModule
import eu.darken.butler.setup.core.root.RootSetupModule
import eu.darken.butler.setup.core.shizuku.ShizukuSetupModule

@Composable
fun SetupStateIndicator(
    state: SetupModule.State,
    isRequired: Boolean
) {
    when (state) {
        is SetupModule.State.Loading -> {
            CircularProgressIndicator(
                modifier = Modifier.size(24.dp),
                strokeWidth = 2.dp
            )
        }
        is SetupModule.State.Current -> {
            // Special handling for Root/Shizuku connection status
            val (icon, tint) = when (state.type) {
                SetupModule.Type.ROOT -> {
                    val rootState = state as? RootSetupModule.Result
                    when {
                        rootState?.useRoot != true -> {
                            Icons.TwoTone.PauseCircle to MaterialTheme.colorScheme.onSurface.copy(alpha = 0.4f)
                        }
                        !rootState.isInstalled -> {
                            Icons.TwoTone.Error to MaterialTheme.colorScheme.error
                        }
                        rootState.ourService -> {
                            Icons.TwoTone.CheckCircle to MaterialTheme.colorScheme.primary
                        }
                        else -> {
                            Icons.TwoTone.Error to MaterialTheme.colorScheme.tertiary
                        }
                    }
                }
                SetupModule.Type.SHIZUKU -> {
                    val shizukuState = state as? ShizukuSetupModule.Result
                    when {
                        shizukuState?.useShizuku != true -> {
                            Icons.TwoTone.PauseCircle to MaterialTheme.colorScheme.onSurface.copy(alpha = 0.4f)
                        }
                        !shizukuState.isInstalled || !shizukuState.isCompatible -> {
                            Icons.TwoTone.Error to MaterialTheme.colorScheme.error
                        }
                        shizukuState.ourService -> {
                            Icons.TwoTone.CheckCircle to MaterialTheme.colorScheme.primary
                        }
                        // Ahead of basicService: the pending-looking indicator must not outlive a
                        // probe that already concluded our service will not come up.
                        shizukuState.serviceState.isTerminalFailure -> {
                            Icons.TwoTone.Error to MaterialTheme.colorScheme.error
                        }
                        shizukuState.basicService -> {
                            Icons.TwoTone.RadioButtonUnchecked to MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f)
                        }
                        else -> {
                            Icons.TwoTone.Error to MaterialTheme.colorScheme.tertiary
                        }
                    }
                }
                else -> {
                    // Default status handling for other permissions
                    when {
                        state.isComplete -> Icons.TwoTone.CheckCircle to MaterialTheme.colorScheme.primary
                        isRequired -> Icons.TwoTone.Error to MaterialTheme.colorScheme.error
                        else -> Icons.TwoTone.RadioButtonUnchecked to MaterialTheme.colorScheme.onSurface.copy(alpha = 0.4f)
                    }
                }
            }

            Icon(
                imageVector = icon,
                contentDescription = null,
                modifier = Modifier.size(24.dp),
                tint = tint
            )
        }
    }
}