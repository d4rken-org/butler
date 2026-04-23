package eu.darken.butler.apps.ui.details

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.tooling.preview.PreviewWrapper as ComposePreviewWrapper
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import eu.darken.butler.apps.R
import eu.darken.butler.apps.core.details.AppInfo
import eu.darken.butler.apps.ui.apps.preview.AppsMockDataProvider
import eu.darken.butler.common.compose.ButlerPreviewWrapper
import eu.darken.butler.common.compose.Preview2
import eu.darken.butler.common.compose.PreviewWrapper
import eu.darken.butler.common.formatFileSize
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.time.format.FormatStyle
import kotlin.time.toJavaInstant

@OptIn(ExperimentalFoundationApi::class)
@Composable
fun AppInformationFields(
    modifier: Modifier = Modifier,
    app: AppInfo?,
) {
    if (app == null) return

    val context = LocalContext.current
    val clipboardManager = LocalClipboardManager.current
    val hapticFeedback = LocalHapticFeedback.current
    val dateFormatter = DateTimeFormatter.ofLocalizedDate(FormatStyle.SHORT)
        .withZone(ZoneId.systemDefault())

    Column(
        modifier = modifier.fillMaxWidth(),
        verticalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        // Package Name
        InfoField(
            label = stringResource(R.string.apps_package_name_label),
            value = app.packageName,
            onLongClick = {
                clipboardManager.setText(AnnotatedString(app.packageName))
                hapticFeedback.performHapticFeedback(HapticFeedbackType.LongPress)
            }
        )

        // Version and Type row
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(16.dp)
        ) {
            // Version
            if (app.versionName != null) {
                InfoField(
                    modifier = Modifier.weight(1f),
                    label = stringResource(R.string.apps_version_label),
                    value = "${app.versionName} (${app.versionCode})",
                    onLongClick = {
                        clipboardManager.setText(AnnotatedString("${app.versionName} (${app.versionCode})"))
                        hapticFeedback.performHapticFeedback(HapticFeedbackType.LongPress)
                    }
                )
            }

            // Type
            InfoField(
                modifier = Modifier.weight(1f),
                label = stringResource(R.string.apps_type_label),
                value = if (app.isSystemApp) {
                    stringResource(R.string.apps_type_system)
                } else {
                    stringResource(R.string.apps_type_user)
                }
            )
        }

        // Installed and Updated dates row
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(16.dp)
        ) {
            // Installed Date
            app.installedAt?.let {
                InfoField(
                    modifier = Modifier.weight(1f),
                    label = stringResource(R.string.apps_installed_label),
                    value = dateFormatter.format(it.toJavaInstant()),
                )
            }

            // Updated Date or Status
            app.updatedAt?.let {
                if (it != app.installedAt) {
                    InfoField(
                        modifier = Modifier.weight(1f),
                        label = stringResource(R.string.apps_updated_label),
                        value = dateFormatter.format(it.toJavaInstant()),
                    )
                }
            } ?: run {
                // Status as fallback if no update date
                InfoField(
                    modifier = Modifier.weight(1f),
                    label = stringResource(R.string.apps_status_label),
                    value = if (app.isEnabled) {
                        stringResource(R.string.apps_status_enabled)
                    } else {
                        stringResource(R.string.apps_status_disabled)
                    }
                )
            }
        }

        // SDKs row if both available
        if (app.targetSdk != null && app.minSdk != null) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(16.dp)
            ) {
                InfoField(
                    modifier = Modifier.weight(1f),
                    label = stringResource(R.string.apps_target_sdk_label),
                    value = "API ${app.targetSdk}",
                )
                InfoField(
                    modifier = Modifier.weight(1f),
                    label = stringResource(R.string.apps_min_sdk_label),
                    value = "API ${app.minSdk}",
                )
            }
        } else {
            // Show individually if only one is available
            app.targetSdk?.let {
                InfoField(
                    label = stringResource(R.string.apps_target_sdk_label),
                    value = "Android ${getAndroidVersionName(it)} (API $it)",
                )
            }
            app.minSdk?.let {
                InfoField(
                    label = stringResource(R.string.apps_min_sdk_label),
                    value = "Android ${getAndroidVersionName(it)} (API $it)",
                )
            }
        }

        // Installer
        app.installerInfo?.let { installerInfo ->
            val installerName = installerInfo.getLabel(context)
            InfoField(
                label = stringResource(R.string.apps_installer_label),
                value = installerName,
            )
        }

        // App Size if available
        if (app.appSize != null) {
            InfoField(
                label = stringResource(R.string.apps_size_label),
                value = formatFileSize(app.appSize)
            )
        }

        // Show status separately if it wasn't shown in the dates row
        if (app.updatedAt != null && app.updatedAt != app.installedAt) {
            InfoField(
                label = stringResource(R.string.apps_status_label),
                value = if (app.isEnabled) {
                    stringResource(R.string.apps_status_enabled)
                } else {
                    stringResource(R.string.apps_status_disabled)
                }
            )
        }
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun InfoField(
    label: String,
    value: String,
    modifier: Modifier = Modifier,
    onLongClick: (() -> Unit)? = null,
) {
    Column(
        modifier = modifier
            .fillMaxWidth()
            .then(
                if (onLongClick != null) {
                    Modifier.combinedClickable(
                        onClick = {},
                        onLongClick = onLongClick
                    )
                } else {
                    Modifier
                }
            ),
        verticalArrangement = Arrangement.spacedBy(2.dp)
    ) {
        Text(
            text = label.uppercase(),
            style = MaterialTheme.typography.labelSmall.copy(fontSize = 11.sp),
            color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.7f),
            letterSpacing = 0.5.sp
        )
        Text(
            text = value,
            style = MaterialTheme.typography.bodyMedium,
            fontWeight = FontWeight.Normal,
        )
    }
}

private fun getAndroidVersionName(apiLevel: Int): String = when (apiLevel) {
    35 -> "15"
    34 -> "14"
    33 -> "13"
    32 -> "12L"
    31 -> "12"
    30 -> "11"
    29 -> "10"
    28 -> "9"
    27 -> "8.1"
    26 -> "8.0"
    25 -> "7.1"
    24 -> "7.0"
    23 -> "6.0"
    22 -> "5.1"
    21 -> "5.0"
    19 -> "4.4"
    18 -> "4.3"
    17 -> "4.2"
    16 -> "4.1"
    15 -> "4.0.3"
    14 -> "4.0"
    else -> apiLevel.toString()
}

@Preview2
@ComposePreviewWrapper(ButlerPreviewWrapper::class)
@Composable
private fun AppInformationFieldsPreview() {
    AppInformationFields(
        app = AppsMockDataProvider.Presets.chrome
    )
}

@Preview2
@ComposePreviewWrapper(ButlerPreviewWrapper::class)
@Composable
private fun AppInformationFieldsSystemAppPreview() {
    AppInformationFields(
        app = AppsMockDataProvider.Presets.settings
    )
}

@Preview2
@ComposePreviewWrapper(ButlerPreviewWrapper::class)
@Composable
private fun AppInformationFieldsDisabledPreview() {
    AppInformationFields(
        app = AppsMockDataProvider.Presets.disabledApp
    )
}
