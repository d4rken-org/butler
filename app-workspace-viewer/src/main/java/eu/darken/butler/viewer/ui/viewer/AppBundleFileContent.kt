package eu.darken.butler.viewer.ui.viewer

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.twotone.Warning
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.nestedscroll.NestedScrollConnection
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.tooling.preview.PreviewWrapper as ComposePreviewWrapper
import androidx.compose.ui.unit.dp
import eu.darken.butler.common.compose.ButlerPreviewWrapper
import eu.darken.butler.common.compose.InfoBlock
import eu.darken.butler.common.compose.InfoEntry
import eu.darken.butler.common.compose.Preview2
import eu.darken.butler.common.pkgs.apk.ApkArchiveInfo
import eu.darken.butler.common.pkgs.installer.AppInstallFormat
import eu.darken.butler.viewer.R
import eu.darken.butler.viewer.core.ApkInstallState
import eu.darken.butler.viewer.core.VersionComparison

/**
 * A multi-APK container. Everything the base APK says about itself is rendered by [ApkFileContent];
 * this only adds what the container itself contributes.
 */
@Composable
fun AppBundleFileContent(
    modifier: Modifier = Modifier,
    format: AppInstallFormat,
    apkInfo: ApkArchiveInfo,
    installState: ApkInstallState,
    splitCount: Int,
    hasObb: Boolean,
    needsElevationForObb: Boolean,
    contentPadding: PaddingValues = PaddingValues(0.dp),
    barScrollConnections: List<NestedScrollConnection> = emptyList(),
    onToggleChrome: (() -> Unit)? = null,
) {
    ApkFileContent(
        modifier = modifier,
        apkInfo = apkInfo,
        installState = installState,
        contentPadding = contentPadding,
        bundleSummary = {
            AppBundleCard(
                format = format,
                splitCount = splitCount,
                hasObb = hasObb,
                needsElevationForObb = needsElevationForObb,
            )
        },
        // The exporter reads an APK, and this file is a zip, so the icon is display only here.
        iconActionsEnabled = false,
        barScrollConnections = barScrollConnections,
        onToggleChrome = onToggleChrome,
    )
}

@Composable
private fun AppBundleCard(
    modifier: Modifier = Modifier,
    format: AppInstallFormat,
    splitCount: Int,
    hasObb: Boolean,
    needsElevationForObb: Boolean,
) {
    Card(
        modifier = modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerHigh),
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 12.dp, vertical = 10.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            SelectionContainer {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(12.dp),
                    verticalAlignment = Alignment.Top,
                ) {
                    InfoBlock(
                        modifier = Modifier.weight(1f),
                        entry = InfoEntry(
                            label = stringResource(R.string.viewer_bundle_format_label),
                            value = format.extension.uppercase(),
                            pairable = true,
                        ),
                    )
                    InfoBlock(
                        modifier = Modifier.weight(1f),
                        entry = InfoEntry(
                            label = stringResource(R.string.viewer_bundle_splits_label),
                            value = "$splitCount",
                            pairable = true,
                        ),
                    )
                }
            }

            if (hasObb) {
                Text(
                    text = stringResource(R.string.viewer_bundle_expansion_files_hint),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }

            if (needsElevationForObb) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalAlignment = Alignment.Top,
                ) {
                    Icon(
                        modifier = Modifier.size(18.dp),
                        imageVector = Icons.TwoTone.Warning,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.error,
                    )
                    Text(
                        text = stringResource(R.string.viewer_bundle_expansion_needs_elevation_warning),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.error,
                    )
                }
            }
        }
    }
}

@Preview2
@ComposePreviewWrapper(ButlerPreviewWrapper::class)
@Composable
private fun AppBundleFileContentPreview() {
    AppBundleFileContent(
        format = AppInstallFormat.APKS,
        apkInfo = previewApkInfo,
        installState = ApkInstallState.NotInstalled,
        splitCount = 4,
        hasObb = false,
        needsElevationForObb = false,
    )
}

@Preview2
@ComposePreviewWrapper(ButlerPreviewWrapper::class)
@Composable
private fun AppBundleFileContentWithExpansionsPreview() {
    AppBundleFileContent(
        format = AppInstallFormat.XAPK,
        apkInfo = previewApkInfo,
        installState = ApkInstallState.Installed(
            versionName = "1.3.0",
            versionCode = 130,
            comparison = VersionComparison.APK_NEWER,
        ),
        splitCount = 7,
        hasObb = true,
        needsElevationForObb = true,
    )
}

@Preview2
@ComposePreviewWrapper(ButlerPreviewWrapper::class)
@Composable
private fun AppBundleCardPreview() {
    AppBundleCard(
        format = AppInstallFormat.APKM,
        splitCount = 3,
        hasObb = true,
        needsElevationForObb = false,
    )
}
