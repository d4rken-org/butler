package eu.darken.butler.developer.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.tooling.preview.PreviewWrapper as ComposePreviewWrapper
import androidx.compose.ui.unit.dp
import eu.darken.butler.common.compose.ButlerPreviewWrapper
import eu.darken.butler.common.compose.Preview2
import eu.darken.butler.common.compose.PreviewWrapper
import eu.darken.butler.developer.R
import eu.darken.butler.developer.ui.DeveloperWorkspaceViewModel.OptionsState
import eu.darken.butler.developer.ui.DeveloperWorkspaceViewModel.RootTestResult
import eu.darken.butler.developer.ui.DeveloperWorkspaceViewModel.ShizukuTestResult

@Composable
internal fun OptionsSection(
    optionsState: OptionsState,
    contentPadding: PaddingValues = PaddingValues(0.dp),
    onToggleDebugMode: (Boolean) -> Unit,
    onToggleTraceMode: (Boolean) -> Unit,
    onToggleFloatingLog: (Boolean) -> Unit,
    onTestRoot: () -> Unit,
    onTestShizuku: () -> Unit,
    onHideDeveloperMode: () -> Unit,
) {
    LazyColumn(
        verticalArrangement = Arrangement.spacedBy(8.dp),
        contentPadding = contentPadding,
    ) {
        // Debug Options Card
        item {
            SectionCard(title = stringResource(R.string.developer_options_debug_header)) {
                DeveloperToggleRow(
                    title = stringResource(R.string.developer_options_debug_mode),
                    description = stringResource(R.string.developer_options_debug_mode_desc),
                    checked = optionsState.isDebugMode,
                    onCheckedChange = onToggleDebugMode,
                )
                DeveloperToggleRow(
                    title = stringResource(R.string.developer_options_trace_mode),
                    description = stringResource(R.string.developer_options_trace_mode_desc),
                    checked = optionsState.isTraceMode,
                    onCheckedChange = onToggleTraceMode,
                    enabled = optionsState.isDebugMode,
                )
                DeveloperToggleRow(
                    title = stringResource(R.string.developer_options_floating_log),
                    description = stringResource(R.string.developer_options_floating_log_desc),
                    checked = optionsState.isFloatingLogEnabled,
                    onCheckedChange = onToggleFloatingLog,
                    enabled = optionsState.isDebugMode,
                )
            }
        }

        // Hide Developer Mode Card (only in release builds when unlocked)
        if (optionsState.canHideDeveloperMode) {
            item {
                SectionCard(title = stringResource(R.string.developer_options_visibility_header)) {
                    DeveloperActionRow(
                        title = stringResource(R.string.developer_options_hide_mode),
                        description = stringResource(R.string.developer_options_hide_mode_desc),
                        actionText = stringResource(R.string.developer_options_hide_action),
                        onClick = onHideDeveloperMode,
                    )
                }
            }
        }

        // Service Tests Card
        item {
            SectionCard(title = stringResource(R.string.developer_options_services_header)) {
                ServiceTestRow(
                    title = stringResource(R.string.developer_options_root_test_action),
                    isTesting = optionsState.isRootTesting,
                    onTest = onTestRoot,
                )
                optionsState.rootTestResult?.let { result ->
                    ServiceResultCard(
                        items = listOf(
                            stringResource(R.string.developer_options_root_installed_label) to result.isInstalled.toResultString(),
                            stringResource(R.string.developer_options_root_available_label) to result.isRooted.toResultString(),
                            stringResource(R.string.developer_options_root_base_check_label) to (result.baseCheck
                                ?: stringResource(R.string.developer_options_result_no)),
                        ),
                        isSuccess = result.isRooted,
                    )
                }

                Spacer(modifier = Modifier.height(16.dp))

                ServiceTestRow(
                    title = stringResource(R.string.developer_options_shizuku_test_action),
                    isTesting = optionsState.isShizukuTesting,
                    onTest = onTestShizuku,
                )
                optionsState.shizukuTestResult?.let { result ->
                    ServiceResultCard(
                        items = listOf(
                            stringResource(R.string.developer_options_shizuku_installed_label) to result.isInstalled.toResultString(),
                            stringResource(R.string.developer_options_shizuku_granted_label) to result.isGranted.toResultString(),
                            stringResource(R.string.developer_options_shizuku_compatible_label) to result.isCompatible.toResultString(),
                            stringResource(R.string.developer_options_shizuku_service_label) to result.isServiceAvailable.toResultString(),
                        ),
                        isSuccess = result.isServiceAvailable,
                    )
                }
            }
        }
    }
}

@Composable
private fun DeveloperToggleRow(
    title: String,
    description: String,
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit,
    enabled: Boolean = true,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = title,
                style = MaterialTheme.typography.bodyLarge,
                color = if (enabled) {
                    MaterialTheme.colorScheme.onSurface
                } else {
                    MaterialTheme.colorScheme.onSurface.copy(alpha = 0.5f)
                },
            )
            Text(
                text = description,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurface.copy(alpha = if (enabled) 0.7f else 0.4f),
            )
        }
        Switch(
            checked = checked,
            onCheckedChange = onCheckedChange,
            enabled = enabled,
        )
    }
}

@Composable
private fun DeveloperActionRow(
    title: String,
    description: String,
    actionText: String,
    onClick: () -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = title,
                style = MaterialTheme.typography.bodyLarge,
                color = MaterialTheme.colorScheme.onSurface,
            )
            Text(
                text = description,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.7f),
            )
        }
        Spacer(modifier = Modifier.width(8.dp))
        OutlinedButton(onClick = onClick) {
            Text(text = actionText)
        }
    }
}

@Composable
private fun ServiceTestRow(
    title: String,
    isTesting: Boolean,
    onTest: () -> Unit,
) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        OutlinedButton(
            onClick = onTest,
            enabled = !isTesting,
        ) {
            if (isTesting) {
                CircularProgressIndicator(
                    modifier = Modifier.size(16.dp),
                    strokeWidth = 2.dp,
                )
                Spacer(modifier = Modifier.width(8.dp))
                Text(text = stringResource(R.string.developer_options_test_running))
            } else {
                Text(text = title)
            }
        }
    }
}

@Composable
private fun ServiceResultCard(
    items: List<Pair<String, String>>,
    isSuccess: Boolean,
) {
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .padding(top = 8.dp),
        colors = CardDefaults.cardColors(
            containerColor = if (isSuccess) {
                MaterialTheme.colorScheme.tertiaryContainer
            } else {
                MaterialTheme.colorScheme.errorContainer
            },
        ),
    ) {
        Column(modifier = Modifier.padding(12.dp)) {
            items.forEach { (label, value) ->
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                ) {
                    Text(
                        text = label,
                        style = MaterialTheme.typography.bodySmall,
                        color = if (isSuccess) {
                            MaterialTheme.colorScheme.onTertiaryContainer
                        } else {
                            MaterialTheme.colorScheme.onErrorContainer
                        }.copy(alpha = 0.7f),
                    )
                    Text(
                        text = value,
                        style = MaterialTheme.typography.bodySmall,
                        color = if (isSuccess) {
                            MaterialTheme.colorScheme.onTertiaryContainer
                        } else {
                            MaterialTheme.colorScheme.onErrorContainer
                        },
                    )
                }
            }
        }
    }
}

@Composable
private fun Boolean.toResultString(): String = if (this) {
    stringResource(R.string.developer_options_result_yes)
} else {
    stringResource(R.string.developer_options_result_no)
}

@Composable
private fun Boolean?.toResultString(): String = when (this) {
    true -> stringResource(R.string.developer_options_result_yes)
    false -> stringResource(R.string.developer_options_result_no)
    null -> stringResource(R.string.developer_options_result_unknown)
}

@Preview2
@ComposePreviewWrapper(ButlerPreviewWrapper::class)
@Composable
private fun OptionsSectionPreview() {
    OptionsSection(
        optionsState = OptionsState(
            isDebugMode = false,
            isTraceMode = false,
            isFloatingLogEnabled = false,
            rootTestResult = null,
            isRootTesting = false,
            shizukuTestResult = null,
            isShizukuTesting = false,
            canHideDeveloperMode = false,
        ),
        onToggleDebugMode = {},
        onToggleTraceMode = {},
        onToggleFloatingLog = {},
        onTestRoot = {},
        onTestShizuku = {},
        onHideDeveloperMode = {},
    )
}

@Preview2
@ComposePreviewWrapper(ButlerPreviewWrapper::class)
@Composable
private fun OptionsSectionWithResultsPreview() {
    OptionsSection(
        optionsState = OptionsState(
            isDebugMode = true,
            isTraceMode = true,
            isFloatingLogEnabled = true,
            rootTestResult = RootTestResult(
                isInstalled = true,
                isRooted = true,
                baseCheck = "Magisk 26.1",
            ),
            isRootTesting = false,
            shizukuTestResult = ShizukuTestResult(
                isInstalled = true,
                isGranted = false,
                isCompatible = true,
                isServiceAvailable = false,
            ),
            isShizukuTesting = false,
            canHideDeveloperMode = true,
        ),
        onToggleDebugMode = {},
        onToggleTraceMode = {},
        onToggleFloatingLog = {},
        onTestRoot = {},
        onTestShizuku = {},
        onHideDeveloperMode = {},
    )
}
