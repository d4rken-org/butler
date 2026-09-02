package eu.darken.butler.explorer.ui.explorer.items

import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.twotone.FolderShared
import androidx.compose.material3.Icon
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.tooling.preview.PreviewWrapper as ComposePreviewWrapper
import androidx.compose.ui.unit.dp
import coil3.compose.SubcomposeAsyncImage
import eu.darken.butler.common.compose.ButlerPreviewWrapper
import eu.darken.butler.common.compose.Preview2
import eu.darken.butler.common.compose.PreviewWrapper
import eu.darken.butler.common.pkgs.Pkg
import eu.darken.butler.common.storage.saf.StorageProviderApp

/** An app's launcher icon; [fallback] is drawn until it has loaded and when it can't be. */
@Composable
fun AppIconImage(
    modifier: Modifier = Modifier,
    pkg: Pkg,
    fallback: @Composable () -> Unit,
) {
    SubcomposeAsyncImage(
        model = pkg,
        contentDescription = null,
        modifier = modifier,
        loading = { fallback() },
        error = { fallback() },
    )
}

@Preview2
@ComposePreviewWrapper(ButlerPreviewWrapper::class)
@Composable
private fun AppIconImagePreview() {
    AppIconImage(
        modifier = Modifier.size(40.dp),
        pkg = StorageProviderApp(packageName = "com.termux", appLabel = "Termux", lastUpdateTime = 0L),
        fallback = { Icon(imageVector = Icons.TwoTone.FolderShared, contentDescription = null) },
    )
}
