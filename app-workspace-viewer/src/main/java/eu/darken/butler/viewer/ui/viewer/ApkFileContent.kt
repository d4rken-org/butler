package eu.darken.butler.viewer.ui.viewer

import android.graphics.Bitmap
import androidx.compose.foundation.Image
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.twotone.KeyboardArrowRight
import androidx.compose.material.icons.twotone.Android
import androidx.compose.material.icons.twotone.KeyboardArrowDown
import androidx.compose.material.icons.twotone.Save
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.input.nestedscroll.NestedScrollConnection
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.tooling.preview.PreviewWrapper as ComposePreviewWrapper
import androidx.compose.ui.unit.dp
import androidx.core.graphics.createBitmap
import eu.darken.butler.common.compose.ButlerPreviewWrapper
import eu.darken.butler.common.compose.InfoBlock
import eu.darken.butler.common.compose.InfoEntry
import eu.darken.butler.common.compose.InfoGridGutter
import eu.darken.butler.common.compose.Preview2
import eu.darken.butler.common.compose.groupInfoEntries
import eu.darken.butler.common.compose.infoGridColumns
import eu.darken.butler.common.pkgs.apk.ApkArchiveInfo
import eu.darken.butler.common.pkgs.apk.ApkSignature
import eu.darken.butler.common.pkgs.apk.apkVersionText
import eu.darken.butler.common.pkgs.toPkgId
import eu.darken.butler.viewer.R
import eu.darken.butler.viewer.core.ApkInstallState
import eu.darken.butler.viewer.core.VersionComparison
import eu.darken.butler.workspace.ui.common.WorkspacePaddings

/**
 * What an APK archive says about itself. A lazy list, not a scrolling column: the permission list
 * comes from an untrusted manifest and can be arbitrarily long, so items compose on demand.
 */
@Composable
fun ApkFileContent(
    modifier: Modifier = Modifier,
    apkInfo: ApkArchiveInfo,
    installState: ApkInstallState,
    contentPadding: PaddingValues = PaddingValues(0.dp),
    /** Rendered right below the header, for what a container adds on top of its base APK. */
    bundleSummary: (@Composable () -> Unit)? = null,
    initiallyPermissionsExpanded: Boolean = false,
    /**
     * Off where the icon cannot be exported: the exporter reads an APK, and a bundle's own file is a
     * zip. The rendered icon still shows, it just is not a handle for showing or saving one.
     */
    iconActionsEnabled: Boolean = true,
    onShowIcon: () -> Unit = {},
    onSaveIcon: () -> Unit = {},
    barScrollConnections: List<NestedScrollConnection> = emptyList(),
    onToggleChrome: (() -> Unit)? = null,
) {
    var permissionsExpanded by rememberSaveable { mutableStateOf(initiallyPermissionsExpanded) }
    val permissions = remember(apkInfo) { apkInfo.requestedPermissions.distinct().sorted() }
    val itemModifier = Modifier.fillMaxWidth()

    LazyColumn(
        modifier = modifier
            .fillMaxSize()
            .let { base -> barScrollConnections.fold(base) { acc, connection -> acc.nestedScroll(connection) } }
            // Not `clickable`: this is a whole scrolling list, and a click role plus a ripple on it
            // would be wrong. Child clickables (the section headers) still win the tap.
            .let { base ->
                if (onToggleChrome == null) base
                else base.pointerInput(onToggleChrome) { detectTapGestures { onToggleChrome() } }
            },
        contentPadding = contentPadding,
        verticalArrangement = Arrangement.spacedBy(WorkspacePaddings.ListGap),
    ) {
        item {
            ApkHeader(
                modifier = itemModifier,
                apkInfo = apkInfo,
                iconActionsEnabled = iconActionsEnabled,
                onShowIcon = onShowIcon,
                onSaveIcon = onSaveIcon,
            )
        }

        if (bundleSummary != null) {
            item {
                Box(modifier = itemModifier) { bundleSummary() }
            }
        }

        item {
            ApkInfoCard(
                modifier = itemModifier,
                apkInfo = apkInfo,
                installState = installState,
            )
        }

        if (permissions.isNotEmpty()) {
            item {
                SectionHeader(
                    modifier = itemModifier,
                    title = stringResource(R.string.viewer_apk_permissions_header, permissions.size),
                    expanded = permissionsExpanded,
                    onClick = { permissionsExpanded = !permissionsExpanded },
                )
            }
            if (permissionsExpanded) {
                items(permissions.size, key = { permissions[it] }) { index ->
                    SelectionContainer(modifier = itemModifier) {
                        Text(
                            modifier = Modifier.padding(horizontal = 4.dp),
                            text = permissions[index],
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
            }
        }

        if (apkInfo.signatures.isNotEmpty()) {
            item {
                SectionHeader(
                    modifier = itemModifier,
                    title = stringResource(R.string.viewer_apk_signing_header),
                )
            }
            items(apkInfo.signatures.size) { index ->
                SignatureBlock(
                    modifier = itemModifier.padding(horizontal = 4.dp),
                    signature = apkInfo.signatures[index],
                )
            }
        }
    }
}

/**
 * Icon, name and package on one line. Stacking them centred cost most of the first screenful for
 * three short pieces of text, so the icon sits beside them instead.
 */
@Composable
private fun ApkHeader(
    modifier: Modifier = Modifier,
    apkInfo: ApkArchiveInfo,
    iconActionsEnabled: Boolean = true,
    onShowIcon: () -> Unit = {},
    onSaveIcon: () -> Unit = {},
) {
    val label = apkInfo.label ?: apkInfo.id.name
    Row(
        modifier = modifier.padding(vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        val icon = apkInfo.icon
        if (icon != null) {
            Image(
                modifier = Modifier
                    .clip(MaterialTheme.shapes.small)
                    .then(if (iconActionsEnabled) Modifier.clickable(onClick = onShowIcon) else Modifier)
                    .size(64.dp),
                bitmap = icon.asImageBitmap(),
                contentDescription = stringResource(R.string.viewer_apk_icon_content_description, label),
            )
        } else {
            Icon(
                modifier = Modifier.size(64.dp),
                imageVector = Icons.TwoTone.Android,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.primary,
            )
        }
        // Long-press to select. The enclosing list only detects taps, and a press long enough to
        // start a selection is past the tap timeout, so the two gestures do not compete.
        SelectionContainer(modifier = Modifier.weight(1f)) {
            Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                Text(
                    text = label,
                    style = MaterialTheme.typography.titleLarge,
                    color = MaterialTheme.colorScheme.onSurface,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                )
                Text(
                    text = apkInfo.id.name,
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
        if (icon != null && iconActionsEnabled) {
            IconButton(onClick = onSaveIcon) {
                Icon(
                    imageVector = Icons.TwoTone.Save,
                    contentDescription = stringResource(R.string.viewer_apk_icon_save_action),
                    tint = MaterialTheme.colorScheme.primary,
                )
            }
        }
    }
}

@Composable
private fun ApkInfoCard(
    modifier: Modifier = Modifier,
    apkInfo: ApkArchiveInfo,
    installState: ApkInstallState,
) {
    val entries = buildList<InfoEntry> {
        add(
            InfoEntry(
                label = stringResource(R.string.viewer_apk_version_label),
                value = apkVersionText(apkInfo.versionName, apkInfo.versionCode),
                pairable = true,
            ),
        )
        add(
            InfoEntry(
                label = stringResource(R.string.viewer_apk_installed_label),
                value = when (installState) {
                    is ApkInstallState.Installed -> {
                        val version = apkVersionText(installState.versionName, installState.versionCode)
                        when (installState.comparison) {
                            VersionComparison.SAME -> version
                            VersionComparison.APK_NEWER ->
                                "$version\n${stringResource(R.string.viewer_apk_installed_older_hint)}"

                            VersionComparison.INSTALLED_NEWER ->
                                "$version\n${stringResource(R.string.viewer_apk_installed_newer_hint)}"
                        }
                    }

                    ApkInstallState.NotInstalled -> stringResource(R.string.viewer_apk_not_installed_value)
                    ApkInstallState.Unknown -> stringResource(R.string.viewer_apk_installed_unknown_value)
                },
                pairable = true,
            ),
        )
        apkInfo.minSdk?.let {
            add(
                InfoEntry(
                    label = stringResource(R.string.viewer_apk_min_sdk_label),
                    value = "API $it",
                    pairable = true,
                ),
            )
        }
        apkInfo.targetSdk?.let {
            add(
                InfoEntry(
                    label = stringResource(R.string.viewer_apk_target_sdk_label),
                    value = "API $it",
                    pairable = true,
                ),
            )
        }
    }

    Card(
        modifier = modifier,
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceContainerHigh,
        ),
    ) {
        SelectionContainer {
            BoxWithConstraints(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 12.dp, vertical = 10.dp),
            ) {
                val columns = infoGridColumns(maxWidth)
                Column(
                    modifier = Modifier.fillMaxWidth(),
                    verticalArrangement = Arrangement.spacedBy(10.dp),
                ) {
                    groupInfoEntries(entries, columns).forEach { row ->
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.spacedBy(InfoGridGutter),
                            verticalAlignment = Alignment.Top,
                        ) {
                            row.forEach { InfoBlock(entry = it, modifier = Modifier.weight(1f)) }
                            // Keeps a short trailing run of pairable entries in their columns so
                            // the grid stays aligned down the card.
                            if (row.first().pairable) {
                                repeat(columns - row.size) { Spacer(modifier = Modifier.weight(1f)) }
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun SectionHeader(
    modifier: Modifier = Modifier,
    title: String,
    expanded: Boolean? = null,
    onClick: (() -> Unit)? = null,
) {
    Row(
        modifier = modifier
            .then(if (onClick != null) Modifier.clickable(onClick = onClick) else Modifier)
            .padding(horizontal = 4.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        Text(
            modifier = Modifier.weight(1f),
            text = title,
            style = MaterialTheme.typography.titleSmall,
            color = MaterialTheme.colorScheme.primary,
        )
        if (expanded != null) {
            Icon(
                imageVector = if (expanded) {
                    Icons.TwoTone.KeyboardArrowDown
                } else {
                    Icons.AutoMirrored.TwoTone.KeyboardArrowRight
                },
                contentDescription = null,
                tint = MaterialTheme.colorScheme.primary,
            )
        }
    }
}

@Composable
private fun SignatureBlock(
    modifier: Modifier = Modifier,
    signature: ApkSignature,
) {
    SelectionContainer(modifier = modifier) {
        Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
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
}

internal val previewApkInfo = ApkArchiveInfo(
    id = "eu.darken.butler".toPkgId(),
    label = "Butler",
    versionName = "1.4.0",
    versionCode = 140,
    minSdk = 26,
    targetSdk = 36,
    requestedPermissions = listOf(
        "android.permission.INTERNET",
        "android.permission.MANAGE_EXTERNAL_STORAGE",
        "android.permission.POST_NOTIFICATIONS",
    ),
    signatures = listOf(
        ApkSignature(
            subjectDn = "CN=Butler, O=darken, C=DE",
            sha256 = "E6:D0:72:DB:59:1B:AC:9B:71:9F:2A:64:B2:BA:9D:45",
        ),
    ),
)

/** Preview-only stand-in for a real launcher icon, so the header's icon branch is previewable. */
private fun previewIcon(): Bitmap = createBitmap(96, 96).apply { eraseColor(0xFF3DDC84.toInt()) }

@Preview2
@ComposePreviewWrapper(ButlerPreviewWrapper::class)
@Composable
private fun ApkFileContentWithIconPreview() {
    ApkFileContent(
        apkInfo = previewApkInfo.copy(icon = previewIcon()),
        installState = ApkInstallState.NotInstalled,
    )
}

@Preview2
@ComposePreviewWrapper(ButlerPreviewWrapper::class)
@Composable
private fun ApkFileContentLongLabelPreview() {
    ApkFileContent(
        apkInfo = previewApkInfo.copy(
            icon = previewIcon(),
            label = "An Application With A Rather Long Display Name",
            id = "com.example.some.deeply.qualified.package.name".toPkgId(),
        ),
        installState = ApkInstallState.NotInstalled,
    )
}

@Preview2
@ComposePreviewWrapper(ButlerPreviewWrapper::class)
@Composable
private fun ApkFileContentInstalledSameVersionPreview() {
    ApkFileContent(
        apkInfo = previewApkInfo,
        installState = ApkInstallState.Installed(
            versionName = "1.4.0",
            versionCode = 140,
            comparison = VersionComparison.SAME,
        ),
    )
}

@Preview2
@ComposePreviewWrapper(ButlerPreviewWrapper::class)
@Composable
private fun ApkFileContentInstalledOlderPreview() {
    ApkFileContent(
        apkInfo = previewApkInfo,
        installState = ApkInstallState.Installed(
            versionName = "1.3.0",
            versionCode = 130,
            comparison = VersionComparison.APK_NEWER,
        ),
    )
}

@Preview2
@ComposePreviewWrapper(ButlerPreviewWrapper::class)
@Composable
private fun ApkFileContentNotInstalledPreview() {
    ApkFileContent(
        apkInfo = previewApkInfo,
        installState = ApkInstallState.NotInstalled,
    )
}

@Preview2
@ComposePreviewWrapper(ButlerPreviewWrapper::class)
@Composable
private fun ApkFileContentUnknownInstallStatePreview() {
    ApkFileContent(
        apkInfo = previewApkInfo,
        installState = ApkInstallState.Unknown,
    )
}

@Preview2
@ComposePreviewWrapper(ButlerPreviewWrapper::class)
@Composable
private fun ApkFileContentExpandedPermissionsPreview() {
    ApkFileContent(
        apkInfo = previewApkInfo,
        installState = ApkInstallState.NotInstalled,
        initiallyPermissionsExpanded = true,
    )
}
