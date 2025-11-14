package eu.darken.butler.apps.ui.details

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import eu.darken.butler.apps.R
import eu.darken.butler.apps.core.details.AppInfo
import eu.darken.butler.common.compose.Preview2
import eu.darken.butler.common.compose.PreviewWrapper
import eu.darken.butler.common.formatFileSize

@Composable
fun AppInformationFields(
    modifier: Modifier = Modifier,
    app: AppInfo?,
) {
    if (app == null) return

    val context = LocalContext.current

    Column(
        modifier = modifier.fillMaxWidth(),
        verticalArrangement = Arrangement.spacedBy(20.dp)
    ) {
        // Package Name
        InfoField(
            label = stringResource(R.string.apps_package_name_label),
            value = app.packageName
        )

        // Version
        if (app.versionName != null) {
            InfoField(
                label = stringResource(R.string.apps_version_label),
                value = "${app.versionName} (${app.versionCode})"
            )
        }

        // App Size
        if (app.appSize != null) {
            InfoField(
                label = stringResource(R.string.apps_size_label),
                value = formatFileSize(app.appSize)
            )
        }

        // Type
        InfoField(
            label = stringResource(R.string.apps_type_label),
            value = if (app.isSystemApp) {
                stringResource(R.string.apps_type_system)
            } else {
                stringResource(R.string.apps_type_user)
            }
        )

        // Status
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

@Composable
private fun InfoField(
    label: String,
    value: String,
    modifier: Modifier = Modifier,
) {
    Column(
        modifier = modifier.fillMaxWidth(),
        verticalArrangement = Arrangement.spacedBy(4.dp)
    ) {
        Text(
            text = label.uppercase(),
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Text(
            text = value,
            style = MaterialTheme.typography.bodyLarge,
            fontWeight = FontWeight.Medium,
        )
    }
}

@Preview2
@Composable
private fun AppInformationFieldsPreview() {
    PreviewWrapper {
        AppInformationFields(
            app = null
        )
    }
}
