package eu.darken.butler.apps.ui.details

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.twotone.KeyboardArrowRight
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.tooling.preview.PreviewWrapper as ComposePreviewWrapper
import androidx.compose.ui.unit.dp
import eu.darken.butler.apps.R
import eu.darken.butler.apps.core.details.AppInfo
import eu.darken.butler.apps.core.details.PackageInfoState
import eu.darken.butler.apps.ui.apps.preview.AppsMockDataProvider
import eu.darken.butler.common.compose.ButlerPreviewWrapper
import eu.darken.butler.common.compose.Preview2
import eu.darken.butler.common.pkgs.apk.ApkArchiveInfo
import eu.darken.butler.common.pkgs.apk.ApkSignature
import eu.darken.butler.common.pkgs.apk.apkVersionText
import eu.darken.butler.common.pkgs.toPkgId

/**
 * The Package info route's list content: manifest data, requested permissions and signing
 * certificates. Permissions and signatures are lazy items, not a card column - an app can declare
 * an arbitrary number of them.
 */
internal fun LazyListScope.packageInfoItems(
    state: PackageInfoState,
    appInfo: AppInfo,
) {
    when (state) {
        PackageInfoState.Loading -> item {
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(vertical = 32.dp),
                contentAlignment = Alignment.Center,
            ) {
                CircularProgressIndicator()
            }
        }

        PackageInfoState.Unavailable -> item {
            Text(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(16.dp),
                text = stringResource(R.string.appdetails_packageinfo_unavailable_msg),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }

        is PackageInfoState.Ready -> {
            val info = state.info

            item {
                DetailSectionCard(title = stringResource(R.string.appdetails_packageinfo_title)) {
                    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        InfoField(
                            label = stringResource(R.string.appdetails_packageinfo_package_label),
                            value = appInfo.packageName,
                        )
                        InfoField(
                            label = stringResource(R.string.appdetails_packageinfo_version_label),
                            value = apkVersionText(info.versionName, info.versionCode),
                        )
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.spacedBy(16.dp),
                        ) {
                            info.minSdk?.let {
                                InfoField(
                                    modifier = Modifier.weight(1f),
                                    label = stringResource(R.string.appdetails_packageinfo_min_sdk_label),
                                    value = "API $it",
                                )
                            }
                            info.targetSdk?.let {
                                InfoField(
                                    modifier = Modifier.weight(1f),
                                    label = stringResource(R.string.appdetails_packageinfo_target_sdk_label),
                                    value = "API $it",
                                )
                            }
                        }
                    }
                }
            }

            val permissions = info.requestedPermissions.distinct().sorted()
            if (permissions.isNotEmpty()) {
                item {
                    SectionHeader(
                        title = stringResource(
                            R.string.appdetails_packageinfo_permissions_header,
                            permissions.size,
                        ),
                    )
                }
                items(permissions.size, key = { "permission-${permissions[it]}" }) { index ->
                    Text(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 16.dp, vertical = 2.dp),
                        text = permissions[index],
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }

            if (info.signatures.isNotEmpty()) {
                item {
                    SectionHeader(title = stringResource(R.string.appdetails_packageinfo_signing_header))
                }
                items(info.signatures.size) { index ->
                    SignatureBlock(signature = info.signatures[index])
                }
            }
        }
    }
}

/** Compact stand-in on the overview; the full data lives on the dedicated route. */
@Composable
internal fun PackageInfoSummary(
    modifier: Modifier = Modifier,
    appInfo: AppInfo,
    onViewAll: () -> Unit,
) {
    Column(modifier = modifier.fillMaxWidth()) {
        Text(
            text = appInfo.packageName,
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(vertical = 4.dp),
        )
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .clickable(onClick = onViewAll)
                .padding(top = 8.dp, bottom = 4.dp),
            horizontalArrangement = Arrangement.spacedBy(4.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                text = stringResource(R.string.appdetails_packageinfo_view_action),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.primary,
                modifier = Modifier.weight(1f),
            )
            Icon(
                imageVector = Icons.AutoMirrored.TwoTone.KeyboardArrowRight,
                contentDescription = null,
                modifier = Modifier.size(24.dp),
                tint = MaterialTheme.colorScheme.primary,
            )
        }
    }
}

@Composable
private fun SectionHeader(
    modifier: Modifier = Modifier,
    title: String,
) {
    Text(
        modifier = modifier.padding(horizontal = 16.dp, vertical = 8.dp),
        text = title,
        style = MaterialTheme.typography.titleSmall,
        color = MaterialTheme.colorScheme.primary,
    )
}

@Composable
private fun SignatureBlock(
    modifier: Modifier = Modifier,
    signature: ApkSignature,
) {
    Column(
        modifier = modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 2.dp),
        verticalArrangement = Arrangement.spacedBy(2.dp),
    ) {
        signature.subjectDn?.let {
            Text(
                text = it,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurface,
            )
        }
        Text(
            text = signature.sha256,
            style = MaterialTheme.typography.bodySmall,
            fontFamily = FontFamily.Monospace,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

internal val previewPackageInfo = ApkArchiveInfo(
    id = "com.android.chrome".toPkgId(),
    versionName = "121.0.6167.101",
    versionCode = 616710103,
    minSdk = 26,
    targetSdk = 34,
    requestedPermissions = listOf(
        "android.permission.INTERNET",
        "android.permission.CAMERA",
        "android.permission.ACCESS_FINE_LOCATION",
    ),
    signatures = listOf(
        ApkSignature(
            subjectDn = "CN=Android, O=Google Inc., C=US",
            sha256 = "F0:FD:6C:5B:41:0F:25:CB:25:C3:B5:33:46:C8:97:2F",
        ),
    ),
)

@Preview2
@ComposePreviewWrapper(ButlerPreviewWrapper::class)
@Composable
private fun PackageInfoSummaryPreview() {
    PackageInfoSummary(
        appInfo = AppsMockDataProvider.Presets.chrome,
        onViewAll = {},
    )
}

@Composable
private fun PackageInfoItemsPreview(state: PackageInfoState) {
    LazyColumn(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        packageInfoItems(state = state, appInfo = AppsMockDataProvider.Presets.chrome)
    }
}

@Preview2
@ComposePreviewWrapper(ButlerPreviewWrapper::class)
@Composable
private fun PackageInfoItemsReadyPreview() {
    PackageInfoItemsPreview(PackageInfoState.Ready(previewPackageInfo))
}

@Preview2
@ComposePreviewWrapper(ButlerPreviewWrapper::class)
@Composable
private fun PackageInfoItemsLoadingPreview() {
    PackageInfoItemsPreview(PackageInfoState.Loading)
}

@Preview2
@ComposePreviewWrapper(ButlerPreviewWrapper::class)
@Composable
private fun PackageInfoItemsUnavailablePreview() {
    PackageInfoItemsPreview(PackageInfoState.Unavailable)
}
