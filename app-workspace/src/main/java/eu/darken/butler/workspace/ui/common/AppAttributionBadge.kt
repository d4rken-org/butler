package eu.darken.butler.workspace.ui.common

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.tooling.preview.PreviewWrapper as ComposePreviewWrapper
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import eu.darken.butler.common.compose.ButlerPreviewWrapper
import eu.darken.butler.common.compose.Preview2
import eu.darken.butler.common.compose.TintedAsyncImage
import eu.darken.butler.common.compose.icons.Ghost
import eu.darken.butler.common.pkgs.Pkg
import eu.darken.butler.common.pkgs.container.toStub
import eu.darken.butler.workspace.R
import eu.darken.butler.workspace.core.appattribution.AppAttribution

/** The badge a folder carries for the app [attribution] names. */
@Composable
fun AppAttributionBadge(
    modifier: Modifier = Modifier,
    attribution: AppAttribution,
    size: Dp,
) {
    when (attribution) {
        is AppAttribution.App -> AppBadge(modifier = modifier, pkg = attribution.pkg, size = size)
        is AppAttribution.Ghost -> GhostBadge(modifier = modifier, size = size)
    }
}

/**
 * An app's launcher icon on a surface plate that sets it off from a folder glyph or thumbnail.
 * Decorative: the folder it sits on is already named after the package.
 */
@Composable
fun AppBadge(
    modifier: Modifier = Modifier,
    pkg: Pkg,
    size: Dp,
) {
    val ring = (size / 16).coerceAtLeast(1.dp)
    Box(
        modifier = modifier
            .size(size)
            .background(MaterialTheme.colorScheme.surface, CircleShape)
            .padding(ring),
    ) {
        TintedAsyncImage(
            model = pkg,
            contentDescription = null,
            tint = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.size(size - ring * 2),
        )
    }
}

/** Stands in for [AppBadge] when the app is not installed. */
@Composable
fun GhostBadge(
    modifier: Modifier = Modifier,
    size: Dp,
) {
    Box(
        modifier = modifier
            .size(size)
            .background(MaterialTheme.colorScheme.surface, CircleShape),
        contentAlignment = Alignment.Center,
    ) {
        Icon(
            imageVector = Icons.TwoTone.Ghost,
            contentDescription = stringResource(R.string.workspace_app_not_installed_content_desc),
            tint = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.size(size * 0.75f),
        )
    }
}

@Preview2
@ComposePreviewWrapper(ButlerPreviewWrapper::class)
@Composable
private fun AppAttributionBadgePreview() {
    AppAttributionBadge(attribution = AppAttribution.Ghost("com.example"), size = 36.dp)
}

@Preview2
@ComposePreviewWrapper(ButlerPreviewWrapper::class)
@Composable
private fun AppBadgePreview() {
    AppBadge(pkg = Pkg.Id("com.example").toStub(), size = 36.dp)
}

@Preview2
@ComposePreviewWrapper(ButlerPreviewWrapper::class)
@Composable
private fun GhostBadgePreview() {
    GhostBadge(size = 36.dp)
}
