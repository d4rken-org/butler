package eu.darken.butler.setup.ui.items

import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Error
import androidx.compose.material.icons.filled.PauseCircle
import androidx.compose.material.icons.filled.RadioButtonUnchecked
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
                            Icons.Default.PauseCircle to MaterialTheme.colorScheme.onSurface.copy(alpha = 0.4f)
                        }
                        !rootState.isInstalled -> {
                            Icons.Default.Error to MaterialTheme.colorScheme.error
                        }
                        rootState.ourService -> {
                            Icons.Default.CheckCircle to MaterialTheme.colorScheme.primary
                        }
                        else -> {
                            Icons.Default.Error to MaterialTheme.colorScheme.tertiary
                        }
                    }
                }
                SetupModule.Type.SHIZUKU -> {
                    val shizukuState = state as? ShizukuSetupModule.Result
                    when {
                        shizukuState?.useShizuku != true -> {
                            Icons.Default.PauseCircle to MaterialTheme.colorScheme.onSurface.copy(alpha = 0.4f)
                        }
                        !shizukuState.isInstalled || !shizukuState.isCompatible -> {
                            Icons.Default.Error to MaterialTheme.colorScheme.error
                        }
                        shizukuState.ourService -> {
                            Icons.Default.CheckCircle to MaterialTheme.colorScheme.primary
                        }
                        shizukuState.basicService -> {
                            Icons.Default.RadioButtonUnchecked to MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f)
                        }
                        else -> {
                            Icons.Default.Error to MaterialTheme.colorScheme.tertiary
                        }
                    }
                }
                else -> {
                    // Default status handling for other permissions
                    when {
                        state.isComplete -> Icons.Default.CheckCircle to MaterialTheme.colorScheme.primary
                        isRequired -> Icons.Default.Error to MaterialTheme.colorScheme.error
                        else -> Icons.Default.RadioButtonUnchecked to MaterialTheme.colorScheme.onSurface.copy(alpha = 0.4f)
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