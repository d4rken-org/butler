package eu.darken.butler.workspace.ui.preview

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.size
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.tooling.preview.PreviewWrapper as ComposePreviewWrapper
import androidx.compose.ui.unit.dp
import eu.darken.butler.common.compose.ButlerPreviewWrapper
import eu.darken.butler.common.compose.Preview2
import eu.darken.butler.common.compose.TintedAsyncImage
import eu.darken.butler.common.files.APath
import eu.darken.butler.common.files.APathLookup
import eu.darken.butler.common.files.LocalPath
import eu.darken.butler.common.files.local.LocalPathLookup
import eu.darken.butler.common.files.metadata.FileType
import eu.darken.butler.workspace.core.preview.FolderPreviewObserver
import java.io.File
import kotlin.time.Instant

/**
 * Provided per workspace page via [ProvideFolderPreviews]. Null (default) renders no folder
 * previews — keeps tiles inert in previews and tests.
 */
internal val LocalFolderPreviewObserver = staticCompositionLocalOf<FolderPreviewObserver?> { null }

const val TEST_TAG_FOLDER_PREVIEW_COLLAGE = "workspace.preview.collage"

/** Makes folder preview collages available to all tiles below; call from a workspace page host. */
@Composable
fun ProvideFolderPreviews(
    observer: FolderPreviewObserver,
    content: @Composable () -> Unit,
) {
    CompositionLocalProvider(LocalFolderPreviewObserver provides observer, content = content)
}

@Composable
fun rememberFolderPreviewChildren(dir: APath<*>): List<APathLookup<*>> {
    val observer = LocalFolderPreviewObserver.current ?: return emptyList()
    val children by produceState(initialValue = emptyList<APathLookup<*>>(), dir, observer) {
        observer(dir).collect { value = it }
    }
    return children
}

@Composable
fun FolderPreviewCollage(
    modifier: Modifier = Modifier,
    children: List<APathLookup<*>>,
) {
    FolderPreviewLayout(
        modifier = modifier.testTag(TEST_TAG_FOLDER_PREVIEW_COLLAGE),
        count = children.size,
    ) { index ->
        CollageCell(lookup = children[index])
    }
}

/**
 * Pure adaptive collage geometry: 1 = full tile, 2 = side-by-side, 3 = one large + two stacked,
 * 4+ = 2x2 (extra cells are ignored). Renders nothing for count <= 0.
 */
@Composable
internal fun FolderPreviewLayout(
    modifier: Modifier = Modifier,
    count: Int,
    cell: @Composable (index: Int) -> Unit,
) {
    val gap = 1.dp
    when (count.coerceAtMost(4)) {
        1 -> Box(modifier = modifier.fillMaxSize()) { cell(0) }
        2 -> Row(
            modifier = modifier.fillMaxSize(),
            horizontalArrangement = Arrangement.spacedBy(gap),
        ) {
            Box(modifier = Modifier.weight(1f).fillMaxHeight()) { cell(0) }
            Box(modifier = Modifier.weight(1f).fillMaxHeight()) { cell(1) }
        }
        3 -> Row(
            modifier = modifier.fillMaxSize(),
            horizontalArrangement = Arrangement.spacedBy(gap),
        ) {
            Box(modifier = Modifier.weight(1f).fillMaxHeight()) { cell(0) }
            Column(
                modifier = Modifier.weight(1f).fillMaxHeight(),
                verticalArrangement = Arrangement.spacedBy(gap),
            ) {
                Box(modifier = Modifier.weight(1f).fillMaxWidth()) { cell(1) }
                Box(modifier = Modifier.weight(1f).fillMaxWidth()) { cell(2) }
            }
        }
        4 -> Column(
            modifier = modifier.fillMaxSize(),
            verticalArrangement = Arrangement.spacedBy(gap),
        ) {
            Row(
                modifier = Modifier.weight(1f).fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(gap),
            ) {
                Box(modifier = Modifier.weight(1f).fillMaxHeight()) { cell(0) }
                Box(modifier = Modifier.weight(1f).fillMaxHeight()) { cell(1) }
            }
            Row(
                modifier = Modifier.weight(1f).fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(gap),
            ) {
                Box(modifier = Modifier.weight(1f).fillMaxHeight()) { cell(2) }
                Box(modifier = Modifier.weight(1f).fillMaxHeight()) { cell(3) }
            }
        }
    }
}

@Composable
private fun CollageCell(
    modifier: Modifier = Modifier,
    lookup: APathLookup<*>,
) {
    Box(
        modifier = modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.surfaceVariant),
    ) {
        TintedAsyncImage(
            model = lookup,
            contentDescription = null,
            modifier = Modifier.fillMaxSize(),
            contentScale = ContentScale.Crop,
        )
    }
}

@Composable
private fun PreviewCell(index: Int) {
    val colors = listOf(
        Color(0xFF7E9BD1),
        Color(0xFFB07ED1),
        Color(0xFF7ED1A8),
        Color(0xFFD1B77E),
    )
    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(colors[index % colors.size]),
    )
}

private fun mockPreviewLookup(name: String): APathLookup<*> = LocalPathLookup(
    lookedUp = LocalPath.build(File("/storage/emulated/0/Pictures/$name")),
    fileType = FileType.FILE,
    size = 512_000L,
    modifiedAt = Instant.DISTANT_PAST,
)

@Preview2
@ComposePreviewWrapper(ButlerPreviewWrapper::class)
@Composable
private fun FolderPreviewCollagePairPreview() {
    FolderPreviewCollage(
        modifier = Modifier.size(120.dp),
        children = listOf(
            mockPreviewLookup("sunset.jpg"),
            mockPreviewLookup("clip.mp4"),
        ),
    )
}

@Preview2
@ComposePreviewWrapper(ButlerPreviewWrapper::class)
@Composable
private fun FolderPreviewCollageQuadPreview() {
    FolderPreviewCollage(
        modifier = Modifier.size(120.dp),
        children = listOf(
            mockPreviewLookup("sunset.jpg"),
            mockPreviewLookup("beach.png"),
            mockPreviewLookup("clip.mp4"),
            mockPreviewLookup("hike.webp"),
        ),
    )
}

@Preview2
@ComposePreviewWrapper(ButlerPreviewWrapper::class)
@Composable
private fun FolderPreviewLayoutSinglePreview() {
    FolderPreviewLayout(modifier = Modifier.size(120.dp), count = 1) { PreviewCell(it) }
}

@Preview2
@ComposePreviewWrapper(ButlerPreviewWrapper::class)
@Composable
private fun FolderPreviewLayoutPairPreview() {
    FolderPreviewLayout(modifier = Modifier.size(120.dp), count = 2) { PreviewCell(it) }
}

@Preview2
@ComposePreviewWrapper(ButlerPreviewWrapper::class)
@Composable
private fun FolderPreviewLayoutTriplePreview() {
    FolderPreviewLayout(modifier = Modifier.size(120.dp), count = 3) { PreviewCell(it) }
}

@Preview2
@ComposePreviewWrapper(ButlerPreviewWrapper::class)
@Composable
private fun FolderPreviewLayoutQuadPreview() {
    FolderPreviewLayout(modifier = Modifier.size(120.dp), count = 4) { PreviewCell(it) }
}
